package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.toDomain
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.NoticeBo
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.model.p1.ClearNotifyRequestDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 社区通知仓库。
 *
 * 依据 NextAPI `/p1/notify` 与 `/p1/clear-notify` 契约。
 *
 * ### 关于 `/p1/notify?unread=true` 中 `total` 语义的说明：
 * OpenAPI 规范将 `total` 泛化描述为「总条数/总页数」，但经 bangumi-server 服务端源码
 * （`routes/private/routes/misc.ts` 中 `total: await Notify.count(auth.userID)`，底层查询 `chiiUsers` 表的 `user_new_notify` 未读通知数字段）
 * 与真实账号 GET `/p1/notify?unread=true&limit=40` 实测验证：
 * 返回的 `total` **即为当前用户的未读通知总数**（与 `unread` 取值无关），
 * 而非数据库历史全部通知条数。因此该 `total` 直接作为全 App 未读通知数的单一真源。
 *
 * ### 未读短信：
 * 短信只有旧站网页有，未读条数读网页顶栏自己读的 `/json/notify`（[WebSessionRepository.unreadMessages]），与通知数一同刷新，
 * 由 [unreadMessages] 发出。没有网页会话时恒为 0、不发请求。角标是两者之和（[unreadTotal]）。
 *
 * ### 专注模式与未登录闸门：
 * 一切以 `appFeatures.community` 与 `session.authenticated` 为唯二闸门。
 * 任意一项为 false 时，底栏无社区入口、无未读角标、不发起 `/p1/notify` 与 `/json/notify` 网络请求，且 [unreadCount]、[unreadMessages]（因而 [unreadTotal]）强制为 0。
 */
class NotificationRepository(
    private val nextApi: NextApi,
    private val tokenStore: TokenStore,
    private val webSessions: WebSessionRepository,
    private val preferencesRepository: PreferencesRepository,
    scope: CoroutineScope,
) {
    private val _unreadCount = MutableStateFlow(0)
    /** 全 App 唯一的未读通知数流；角标读的是它与未读短信的和（[unreadTotal]）。 */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _unreadMessages = MutableStateFlow(0)
    /** 全 App 唯一的未读短信数流（网页上的未读条数）。 */
    val unreadMessages: StateFlow<Int> = _unreadMessages.asStateFlow()

    /** 未读通知 + 未读短信：两处角标（顶栏铃铛圆点、底栏/导航轨数字）都读它，铃铛打开的面板里两样都在。 */
    val unreadTotal: StateFlow<Int> = combine(unreadCount, unreadMessages) { notices, messages -> notices + messages }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * 未读短信在服务端变过的次数（全部已读、打开了一个会话）：读取只在它开始以来没变过时才发布，
     * 变化之前发出、之后才回来的旧读数不覆盖新状态。
     */
    private var messagesEpoch = 0L

    /** 只保护访问归属与结果发布，不持有它等待网络。 */
    private val refreshMutex = Mutex()
    private var currentAccess: Access? = null
    private var accessGeneration = 0L
    private var writeGeneration = 0L
    private var readGeneration = 0L
    private var publishedRead = 0L
    private var activeWrites = 0

    private data class Access(val session: AccountSession, val community: Boolean) {
        val allowed: Boolean get() = community && session.authenticated
    }

    private data class Round(
        val access: Access,
        val accessEpoch: Long,
        val writeGeneration: Long,
        val readGeneration: Long,
        val readingDuringWrite: Boolean,
    )

    init {
        scope.launch {
            combine(preferencesRepository.appFeatures, tokenStore.sessionFlow) { features, session ->
                Access(session, features.community)
            }.distinctUntilChanged()
                // 读不出开关或会话（本机存储失败）：观察到此结束，进程不崩；之后每次刷新仍在锁内重读访问
                .catch { e -> if (e !is Exception) throw e }
                .collect {
                    // 输入可能在组合后已过时，锁内读取当前快照而不是把迟到输入恢复成当前归属。
                    resynchronizeAccess()
                }
        }
    }

    /**
     * 读取之外（访问观察、失败之后）重新对齐访问：读不出本机的会话或开关时什么都不改，
     * 失败不出这个函数（它跑在应用级观察与页面协程里，裸抛会结束观察或带崩页面）。
     */
    private suspend fun resynchronizeAccess() {
        outcomeOf { refreshMutex.withLock { synchronizeAccess() } }
    }

    /** 完整会话或访问开关改变都开启新一代；同账号重登与合并掉中间态的直接换号也清零。 */
    private suspend fun synchronizeAccess(): Access {
        val session = tokenStore.session()
        val access = Access(session, preferencesRepository.appFeatures.first().community)
        if (access != currentAccess) {
            currentAccess = access
            accessGeneration++
            activeWrites = 0
            _unreadCount.value = 0
            messagesEpoch++
            _unreadMessages.value = 0
        }
        return access
    }

    private suspend fun beginRound(write: Boolean = false): Round? = refreshMutex.withLock {
        val access = synchronizeAccess()
        if (!access.allowed) return@withLock null
        if (write) {
            writeGeneration++
            activeWrites++
        } else {
            readGeneration++
        }
        Round(access, accessGeneration, writeGeneration, readGeneration, activeWrites > 0)
    }

    /** 同一访问代与写入代才接受读取；清已读在途或之后的旧读取不能覆盖它。 */
    private suspend fun publishRead(round: Round, total: Int): Boolean = refreshMutex.withLock {
        val access = synchronizeAccess()
        if (!round.isCurrent(access)) return@withLock false
        if (round.writeGeneration == writeGeneration && !round.readingDuringWrite && activeWrites == 0 && round.readGeneration >= publishedRead) {
            _unreadCount.value = total
            publishedRead = round.readGeneration
        }
        true
    }

    private fun Round.isCurrent(access: Access): Boolean =
        access.allowed && access == this.access && accessEpoch == accessGeneration

    /** 清已读的收尾不受调用方取消打断；每个完成的写入都推进代数，阻止在它期间发起的读取倒灌。 */
    private suspend fun finishWrite(round: Round, succeeded: Boolean, all: Boolean) = withContext(NonCancellable) {
        refreshMutex.withLock {
            val access = synchronizeAccess()
            if (round.isCurrent(access)) {
                activeWrites--
                writeGeneration++
                if (succeeded) _unreadCount.value = if (all) 0 else (_unreadCount.value - 1).coerceAtLeast(0)
            }
        }
    }

    /** 前台、社区页与通知面板按需刷新通知与短信的未读数；各自失败保留本会话已有数，访问失效的回包一律丢弃。 */
    suspend fun refreshUnreadCount() {
        val unread = outcomeOf {
            val round = beginRound() ?: return@outcomeOf
            val total = tokenStore.withSessionToken(round.access.session) { _, token ->
                nextApi.getNotifications(unread = true, limit = 1, accessToken = token).total
            }
            publishRead(round, total)
            Unit
        }
        if (unread is Outcome.Failed) resynchronizeAccess()
        refreshUnreadMessages()
    }

    /**
     * 服务端的未读短信变了（打开了一个会话，网页就算读过）：之前发出的读取作废，重读一次。
     * 由做了这件事的仓库调用（[PmRepository]）。
     */
    suspend fun messagesChanged() {
        refreshMutex.withLock { messagesEpoch++ }
        refreshUnreadMessages()
    }

    private suspend fun refreshUnreadMessages() {
        val read = outcomeOf {
            val (access, epoch) = refreshMutex.withLock {
                val access = synchronizeAccess()
                if (!access.allowed) return@outcomeOf
                access to messagesEpoch
            }
            val count = webSessions.unreadMessages(access.session)?.count ?: 0
            refreshMutex.withLock {
                if (synchronizeAccess() == access && epoch == messagesEpoch) _unreadMessages.value = count
            }
        }
        if (read is Outcome.Failed) resynchronizeAccess()
    }

    /**
     * 未读短信全部标为已读（网页会话，[WebSessionRepository.markMessagesRead]），成功后归零。
     * 未登录或社区关闭时直接成功；没有网页会话是「要账号密码登录」。
     */
    suspend fun markAllMessagesRead(): Outcome<Unit> = outcomeOf {
        val access = refreshMutex.withLock {
            val access = synchronizeAccess()
            if (!access.allowed) return@outcomeOf
            messagesEpoch++
            access
        }
        webSessions.markMessagesRead(access.session)
        withContext(NonCancellable) {
            refreshMutex.withLock {
                if (synchronizeAccess() == access) {
                    messagesEpoch++
                    _unreadMessages.value = 0
                }
            }
        }
    }

    /** 最近的已读与未读通知。访问关闭时返回空；清已读后迟到的列表内容仍可读，但计数不能倒退。 */
    suspend fun getNotifications(limit: Int = 40): Outcome<List<NoticeBo>> = outcomeOf {
        val round = beginRound() ?: return@outcomeOf emptyList()
        val page = tokenStore.withSessionToken(round.access.session) { _, token ->
            nextApi.getNotifications(limit = limit, accessToken = token)
        }
        if (publishRead(round, page.total)) page.data.map { it.toDomain() } else emptyList()
    }

    /** 全部标为已读，成功后归零；写入与读取共用结果代数。 */
    suspend fun markAllAsRead(): Outcome<Unit> = clearNotifications(all = true, request = ClearNotifyRequestDto())

    /** 一条标为已读，成功后减一；未登录或社区关闭时直接成功。 */
    suspend fun markRead(id: Int): Outcome<Unit> = clearNotifications(all = false, request = ClearNotifyRequestDto(id = listOf(id)))

    private suspend fun clearNotifications(all: Boolean, request: ClearNotifyRequestDto): Outcome<Unit> = outcomeOf {
        val round = beginRound(write = true) ?: return@outcomeOf
        var succeeded = false
        try {
            tokenStore.withSessionToken(round.access.session) { _, token ->
                nextApi.clearNotifications(request, requireNotNull(token))
            }
            succeeded = true
        } finally {
            finishWrite(round, succeeded, all)
        }
    }
}
