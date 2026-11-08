package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.db.mapper.toFullBo
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.ObservedPreferences
import io.github.touhougo.chogumi.core.data.util.WriteFence
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.network.api.UserApi
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** 某个账号屏蔽谁（资料页判断这个人是不是绝交了）；绝交、取消绝交做成后经 [record] 记下新名单。页面藏谁见 [BlockedUsersFeed]。 */
interface BlockedUsersSource {
    suspend fun of(session: AccountSession): BlockedUsers

    /** 记下 [session] 这个账号的完整绝交名单（服务端读到的，或绝交、取消绝交后服务端给的）。 */
    suspend fun record(session: AccountSession, ids: Collection<Int>)

    /**
     * [session] 这个账号本机记下的名单：订阅时先给当前那份，之后每次 [record] 改了名单再出一拍（设置页的屏蔽列表、
     * 任一页的绝交与取消）。还没记下过时不出拍；读不出本机存储时流以异常结束，由调用方收敛。不记名单的来源恒不出拍。
     */
    fun changes(session: AccountSession): Flow<BlockedUsers> = emptyFlow()

    companion object {
        /** 谁也不屏蔽、什么也不记：仓库的缺省，App 里由 DataModule 换成 [BlocklistRepository]。 */
        val NONE = object : BlockedUsersSource {
            override suspend fun of(session: AccountSession) = BlockedUsers.NONE
            override suspend fun record(session: AccountSession, ids: Collection<Int>) = Unit
        }
    }
}

/**
 * 当前账号的绝交名单，跟着换号与名单变化走。有楼层的页面与动态、帖子、长评列表订阅它，藏不藏在显示时按它定
 * （楼层 `visibleThread`，分页列表 [BlockedUsersFilter]）：在这一页长按楼层绝交、在别的页绝交或在设置页取消，
 * 已经显示的楼层与返回栈里的列表当场跟上，不等重新加载。
 */
fun interface BlockedUsersFeed {
    /**
     * 订阅时先给一份（[BlockedUsersSource.of]：缓存，没有缓存时当场读一次），之后每次名单变了再出一拍；换号时换成新账号的名单，
     * 游客恒为谁也不屏蔽。流本身不失败：读不出名单是谁也不屏蔽（名单只是辅助数据，不挡内容），本机存储读不出时停在最后一份。
     *
     * 页面在第一拍之前不画楼层（`shownUnder`），所以第一拍有时限：当场读得太久就先给谁也不屏蔽，读到后再出一拍。
     */
    fun current(): Flow<BlockedUsers>

    companion object {
        /** 谁也不屏蔽：仓库的缺省，App 里由 DataModule 换成 [BlocklistRepository]。 */
        val NONE = BlockedUsersFeed { flowOf(BlockedUsers.NONE) }
    }
}

/**
 * 绝交名单：绝交即屏蔽，名单以 bgm.tv 为准（`GET /p1/blocklist`，带令牌就能读，不需要网页会话），本机按账号缓存一份
 * （偏好 DataStore，同 NSFW 偏好的做法），在网页端绝交的人也会同步过来。
 *
 * - 读：[of] 先给缓存；缓存超过 [STALE_MILLIS] 在后台重读，没有缓存时当场读一次，读不到就谁也不屏蔽（不因此挡住内容）；
 * - 写：绝交、取消绝交（`UserRepository`）做成后经 [record] 记下服务端给的完整名单，订阅 [current] 的页面与列表当场跟上；
 * - 设置页的「屏蔽的用户」：[blockedUsers]。
 */
class BlocklistRepository(
    dataStore: DataStore<Preferences>,
    private val userApi: UserApi,
    private val tokenStore: TokenStore,
    private val webSessions: WebSessionRepository,
    private val clock: AppClock,
    private val scope: CoroutineScope,
) : BlockedUsersSource, BlockedUsersFeed {
    private val prefs = ObservedPreferences(dataStore)

    /** 每个账号最近一次没有缓存时读名单失败的时刻（内存里，只管退避）。 */
    private val failedAt = ConcurrentHashMap<Int, Long>()

    override suspend fun of(session: AccountSession): BlockedUsers {
        val userId = session.userId?.takeIf { session.authenticated } ?: return BlockedUsers.NONE
        val stored = prefs.data.first()
        val cached = stored[PreferenceKeys.blocklistKey(userId)] ?: return readWithoutCache(session, userId)
        val fetchedAt = stored[PreferenceKeys.blocklistFetchedAtKey(userId)] ?: 0L
        if (clock.nowMillis() - fetchedAt > STALE_MILLIS) scope.launch { outcomeOf { refresh(session) } }
        return BlockedUsers(decode(cached))
    }

    /**
     * 没有缓存：当场读一次。读不到就谁也不屏蔽，并在 [RETRY_MILLIS] 内不再读（名单只是辅助数据，
     * 接口一直失败或超时时不能让每次加载内容都多等一个失败的请求）。
     */
    private suspend fun readWithoutCache(session: AccountSession, userId: Int): BlockedUsers {
        val now = clock.nowMillis()
        if (failedAt[userId]?.let { now - it < RETRY_MILLIS } == true) return BlockedUsers.NONE
        return when (val read = outcomeOf { refresh(session) }) {
            is Outcome.Success -> read.value.also { failedAt.remove(userId) }
            is Outcome.Failed -> BlockedUsers.NONE.also { failedAt[userId] = now }
        }
    }

    override fun changes(session: AccountSession): Flow<BlockedUsers> {
        val userId = session.userId?.takeIf { session.authenticated } ?: return emptyFlow()
        return prefs.data.mapNotNull { it[PreferenceKeys.blocklistKey(userId)] }.distinctUntilChanged().map { BlockedUsers(decode(it)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun current(): Flow<BlockedUsers> = tokenStore.sessionFlow
        .flatMapLatest { session ->
            flow {
                emit(firstOf(session))
                emitAll(changes(session))
            }
        }
        .catch { e -> if (e !is Exception) throw e }
        .distinctUntilChanged()

    /**
     * 页面的第一份名单，最多等 [FIRST_WAIT_MILLIS]：页面在它到之前不画楼层，名单接口卡住时不能一直挡着内容。等不到先给谁也不屏蔽；
     * 读取在仓库的作用域里接着跑，读到存下后经 [changes] 再出一拍（只有这种慢的时候，被绝交的人的楼会先出现再消失）。
     */
    private suspend fun firstOf(session: AccountSession): BlockedUsers {
        val read = scope.async { outcomeOf { of(session) }.valueOrNull() ?: BlockedUsers.NONE }
        return withTimeoutOrNull(FIRST_WAIT_MILLIS) { read.await() } ?: BlockedUsers.NONE
    }

    /** 同一账号同时只读一次；写之前发出的名单读取晚到时不盖掉写做成后记下的名单（[WriteFence]）。 */
    private val fence = WriteFence<List<Int>>()

    override suspend fun record(session: AccountSession, ids: Collection<Int>) {
        val userId = session.userId ?: return
        fence.write(session) { store(userId, ids) }
    }

    private suspend fun store(userId: Int, ids: Collection<Int>) {
        prefs.edit {
            it[PreferenceKeys.blocklistKey(userId)] = ids.joinToString(SEPARATOR)
            it[PreferenceKeys.blocklistFetchedAtKey(userId)] = clock.nowMillis()
        }
    }

    /**
     * 设置页「屏蔽的用户」：当前账号绝交的人，名单重读一次，人以接口给的 id 为准（内容过滤用的是同一份）。接口的名单只有数字 id，
     * 逐个把 id 换成用户名（公开主页的跳转，[WebSessionRepository.usernameOfId]）再按用户名查资料，取到昵称与头像。
     * 换不到用户名或查不到资料的人只有 id，名字由 `displayNameOf` 写成 `#id`。
     */
    suspend fun blockedUsers(): Outcome<List<UserBo>> = tokenStore.authenticatedOutcome { session, token ->
        outcomeOf {
            val ids = refresh(session).ids
            val lookups = Semaphore(PROFILE_LOOKUPS)
            coroutineScope {
                ids.map { id ->
                    async {
                        lookups.withPermit {
                            val username = outcomeOf { webSessions.usernameOfId(id) }.valueOrNull()
                            username?.let { resolved ->
                                outcomeOf { userApi.getP1User(resolved, token).toFullBo() }.valueOrNull()
                                    ?: UserBo(id = id, username = resolved, nickname = "")
                            }
                        } ?: UserBo(id = id, username = "", nickname = "")
                    }
                }.awaitAll()
            }
        }
    }

    /**
     * 重读 [session] 的绝交名单并存下；同一账号同时只读一次。换号、登出时结果作废，不写进缓存。
     * 读的途中绝交或取消绝交做成了，这次读到的作废并重读（[WriteFence]）。
     */
    private suspend fun refresh(session: AccountSession): BlockedUsers {
        val userId = requireNotNull(session.userId)
        val ids = fence.read(
            session = session,
            fetch = { tokenStore.withSessionToken(session) { _, token -> userApi.getBlocklist(requireNotNull(token)).blocklist } },
            publish = { store(userId, it) },
        )
        return BlockedUsers(ids.toSet())
    }

    private fun decode(raw: String): Set<Int> = raw.split(SEPARATOR).mapNotNullTo(HashSet()) { it.toIntOrNull() }

    private companion object {
        const val SEPARATOR = ","

        /** 缓存过了这么久就在后台重读：在网页端新绝交的人，过一阵子在 App 里也藏起来。 */
        const val STALE_MILLIS = 60 * 60 * 1000L

        /** 页面等第一份名单最多等多久（我的设计决定，不在 M3 里）：缓存一读就到，只有当场去读的那一次可能等满。 */
        const val FIRST_WAIT_MILLIS = 3_000L

        /** 没有缓存时读名单失败后，隔多久才再读（我的设计决定，不在 M3 里）。 */
        const val RETRY_MILLIS = 60 * 1000L

        /** 设置页同时查几个人的资料。 */
        const val PROFILE_LOOKUPS = 6
    }
}
