package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.Pager
import io.github.touhougo.chogumi.core.data.db.mapper.toCommentThreadBoList
import io.github.touhougo.chogumi.core.data.db.mapper.toDomain
import io.github.touhougo.chogumi.core.data.db.mapper.toTimelineItem
import io.github.touhougo.chogumi.core.data.db.mapper.toUserBo
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.queuedAuthenticatedOutcome
import io.github.touhougo.chogumi.core.data.util.LruMap
import io.github.touhougo.chogumi.core.data.util.forbiddenAs
import io.github.touhougo.chogumi.core.data.util.onWebIfServerError
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.TIMELINE_TEXT_LIMIT
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineMode
import io.github.touhougo.chogumi.core.model.TimelineQuery
import io.github.touhougo.chogumi.core.model.TimelineReactionTarget
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.withMyReaction
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.TimelineApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.toFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 时间线在内存里认得几个用户（发动态的人、评论的人、按名字查过的人）：远大于一页里出现的人数（设计决定）。
 * 名字索引每人最多三条（用户名、数字 ID、查询时用的名字），上限按三倍算。
 */
internal const val TIMELINE_USER_CACHE_SIZE = 200

/**
 * 内存状态按账户会话和 query 隔离；没有 Room 时间线表，也不把 /home 空数组当删除信号。
 *
 * 每份 feed 的列表是一份 [PagedList]，首屏、重拉、续取、失败都由 [Pager] 管（全仓只此一套分页状态机）。
 * 时间线接口按 `until`（上一页最后一条的 ID）续取，不按 offset：Pager 的位置在这里是页码，
 * 页码到游标的对应关系由每份 feed 自己记（[Feed.cursors]）。
 *
 * [Pager] 不是线程安全的：它的请求、回包和本地改动（[refresh]、[loadMore]、乐观改条目、摘下 feed）全部在 [feedDispatcher]
 * （并行度为 1）上执行，同一份列表的两次写不会交错。
 *
 * 全站、好友两条 feed 不显示绝交用户的动态：取到的原样留在 [Pager] 里，显示时按当前名单滤（[BlockedUsersFilter]），
 * 在别处绝交、取消绝交后已加载的 feed 当场跟上。个人时间线是主动点进那个人看的，不滤。
 *
 * 失败只在界面能画出来的地方记：[PagedList.error] 是列表为空时取不到（整页报错 + 重试），[PagedList.loadMoreError]
 * 是续取失败（页脚报错 + 重试）。已有内容时刷新失败不进状态——内容留着，原因由 [TimelineRepository.refresh] 返回给调用方提示。
 */
class TimelineRepository(
    private val timelineApi: TimelineApi,
    private val nextApi: NextApi,
    private val userApi: UserApi,
    private val tokenStore: TokenStore,
    private val webSessions: WebSessionRepository,
    private val scope: CoroutineScope,
    private val feedDispatcher: CoroutineDispatcher,
    blockedUsers: BlockedUsersFeed = BlockedUsersFeed.NONE,
) {
    private data class FeedKey(val session: AccountSession, val query: TimelineQuery)
    private class Feed(val key: FeedKey) {
        lateinit var pager: Pager<TimelineItemBo>

        /** 第 n 页要带的 `until`：第一页没有，其后每页是上一页最后一条的 ID。 */
        val cursors = mutableListOf<Long?>(null)
    }
    private val lock = Any()
    private val feeds = mutableMapOf<FeedKey, Feed>()
    private val observers = mutableMapOf<TimelineQuery, MutableStateFlow<PagedList<TimelineItemBo>>>()
    private val publicFlows = mutableMapOf<TimelineQuery, StateFlow<PagedList<TimelineItemBo>>>()
    private val users = LruMap<Int, UserBo>(TIMELINE_USER_CACHE_SIZE)
    private val userAliases = LruMap<String, UserBo>(TIMELINE_USER_CACHE_SIZE * 3)
    private var activeSession: AccountSession? = null
    /** 写操作（发、回复、删、贴贴）一笔一笔排队；账号在排队之前定下（[queuedAuthenticatedOutcome]）。 */
    private val actionMutex = Mutex()
    private val authorMutex = Mutex()

    /** 名单变了，已加载的全站、好友 feed 按新名单重算（个人时间线不滤，不用重算）。 */
    private val blocked = BlockedUsersFilter(scope + feedDispatcher, blockedUsers) {
        synchronized(lock) { feeds.values.filter { it.key.query.mode != TimelineMode.USER } }.forEach { it.pager.represent() }
    }

    init {
        scope.launch {
            tokenStore.sessionFlow.catch { error ->
                // toFailure 是 suspend，不能在 synchronized 临界区内调用：先求值再进锁。
                val failure = error.toFailure()
                synchronized(lock) {
                    observers.values.forEach { it.value = PagedList(isLoading = false, error = failure) }
                }
            }.collect { session ->
                // 收集队列中的过期快照不能反向激活旧账户。
                if (tokenStore.isCurrent(session)) activate(session)
            }
        }
    }

    /** 同一 query 共享同一个 StateFlow；首次加载 / 重试由 [refresh] 显式触发。 */
    fun observe(query: TimelineQuery): StateFlow<PagedList<TimelineItemBo>> = synchronized(lock) {
        val normalized = normalize(query)
        if (normalized.mode == TimelineMode.USER) dropUnwatchedUserFeeds(except = normalized)
        publicFlows.getOrPut(normalized) { observerOf(normalized).asStateFlow() }
    }

    private fun observerOf(query: TimelineQuery) = observers.getOrPut(query) { MutableStateFlow(PagedList()) }

    /**
     * 从第一页重取。返回**界面上看不到的**失败原因：已有内容时刷新失败（内容留着，调用方用提示条告诉用户）；
     * 成功、或失败已记进 [PagedList.error] 时返回 null。已经有一路重拉在途时等它结束，原因归先发起的那次。
     */
    suspend fun refresh(query: TimelineQuery, userInitiated: Boolean = false): Failure? = withContext(feedDispatcher) {
        val feed = feedOf(normalize(query))
        if (feed.pager.isActive && !observerOf(feed.key.query).value.isLoadingMore) {
            feed.pager.join()
            return@withContext null
        }
        var failure: Failure? = null
        feed.pager.reload(showIndicator = userInitiated) { failure = it }
        feed.pager.join()
        // 换号是主动隔离旧请求，不是用户看得见的网络错误
        failure?.takeIf { it != Failure.SessionChanged }
    }

    /** 续取下一页；失败记进 [PagedList.loadMoreError]（页脚重试）。 */
    suspend fun loadMore(query: TimelineQuery) {
        withContext(feedDispatcher) {
            val feed = feedOf(normalize(query))
            feed.pager.loadMore()
            if (observerOf(feed.key.query).value.isLoadingMore) feed.pager.join()
        }
    }

    /**
     * 个人动态按用户各存一份，看过的人越多占得越多：再打开一个人的动态时，丢掉已经没人订阅（所在页已出栈）的其他人的。
     * 还在栈里的页面一直订阅着，不受影响；全站、好友两条 feed 只有两份，常驻。调用方持有 [lock]。
     */
    private fun dropUnwatchedUserFeeds(except: TimelineQuery) {
        val unwatched = observers.filter { (query, flow) ->
            query.mode == TimelineMode.USER && query != except && flow.subscriptionCount.value == 0
        }.keys
        if (unwatched.isEmpty()) return
        observers.keys.removeAll(unwatched)
        publicFlows.keys.removeAll(unwatched)
        feeds.keys.filter { it.query in unwatched }.forEach { key ->
            // 还在路上的请求作废：先摘下 feed，Pager 的回包写不回状态
            feeds.remove(key)?.let { dropped -> scope.launch(feedDispatcher) { dropped.pager.clear() } }
        }
    }

    private fun normalize(query: TimelineQuery) = if (query.mode == TimelineMode.USER) {
        query.copy(username = query.username?.trim())
    } else query.copy(username = null)

    private fun activate(session: AccountSession) = synchronized(lock) {
        if (activeSession == session) return@synchronized
        val old = feeds.values.toList()
        feeds.clear()
        scope.launch(feedDispatcher) { old.forEach { it.pager.clear() } }
        users.clear()
        userAliases.clear()
        observers.values.forEach { it.value = PagedList() }
        activeSession = session
    }

    /** 当前账户这个 query 的那份 feed（没有就建）；先确认账户会话，换号时旧账户的 feed 在这里清掉。 */
    private suspend fun feedOf(query: TimelineQuery): Feed {
        val session = tokenStore.session()
        tokenStore.commitForSession(session) { activate(session) }
        val key = FeedKey(session, query)
        return synchronized(lock) { feeds.getOrPut(key) { newFeed(key) } }
    }

    private fun newFeed(key: FeedKey): Feed {
        val feed = Feed(key)
        val state = observerOf(key.query)
        feed.pager = Pager(
            scope = scope + feedDispatcher,
            read = { state.value },
            // 已经被摘下（换号、没人看的个人动态）的 feed 回包写不回状态
            write = { transform -> synchronized(lock) { if (feeds[key] === feed) state.value = transform(state.value) } },
            present = { items ->
                val distinct = items.distinctBy(TimelineItemBo::id)
                if (key.query.mode == TimelineMode.USER) distinct else blocked.visible(distinct, TimelineItemBo::uid)
            },
            fetch = { position -> fetchPage(key, feed, position) },
        )
        return feed
    }

    /** 取第 [position] 页（页码，0 起）。 */
    private suspend fun fetchPage(key: FeedKey, feed: Feed, position: Int): Outcome<PagedResult<TimelineItemBo>> = outcomeOf {
        val cursor = synchronized(lock) { feed.cursors.getOrNull(position) }
        val target = if (key.query.mode == TimelineMode.USER) {
            val name = key.query.username?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("个人时间线需要用户名或用户 ID")
            resolveUser(name, key.session)
        } else null
        val token = tokenStore.tokenFor(key.session)
        // 个人时间线是主动点进那个人看的，不按绝交名单滤，也不等名单
        val page = if (target != null) {
            timelineApi.getUserTimeline(target.username, until = cursor, accessToken = token)
        } else {
            val mode = if (!key.session.authenticated || key.query.mode == TimelineMode.ALL) "all" else "friends"
            blocked.alongside { timelineApi.getTimeline(mode, until = cursor, accessToken = token) }
        }
        tokenStore.commitForSession(key.session) {
            synchronized(lock) {
                target?.let { cacheUser(it) }
                page.mapNotNull { it.user?.toUserBo() }.forEach(::cacheUser)
                val items = page.map { it.toTimelineItem(key.session.userId, users[it.uid] ?: target) }
                val next = page.lastOrNull()?.id
                // 过滤隐藏/删除动态后短页仍可能有下一页，只以空页或不前进游标停止。
                val stopped = next == null || (cursor != null && next >= cursor)
                if (!stopped) {
                    while (feed.cursors.size > position + 1) feed.cursors.removeAt(feed.cursors.lastIndex)
                    feed.cursors.add(next)
                }
                PagedResult(items, total = 0, nextOffset = if (stopped) null else position + 1)
            }
        }
    }

    private fun cacheUser(user: UserBo) {
        users[user.id] = user
        userAliases[user.username] = user
        userAliases[user.id.toString()] = user
    }

    private suspend fun resolveUser(name: String, session: AccountSession): UserBo = authorMutex.withLock {
        synchronized(lock) { userAliases[name] }?.let { return@withLock it }
        // v0 支持数字 ID 查询；取返回的规范 username 后再调用 p1 个人 feed。
        val user = userApi.getUser(name, tokenStore.tokenFor(session)).toDomain()
        tokenStore.commitForSession(session) { synchronized(lock) { cacheUser(user); userAliases[name] = user } }
        user
    }

    suspend fun replies(id: Long): Outcome<List<CommentThreadBo>> = outcomeOf {
        val session = tokenStore.session()
        val comments = timelineApi.getReplies(id, tokenStore.tokenFor(session))
        tokenStore.commitForSession(session) {
            synchronized(lock) {
                comments.forEach { comment ->
                    comment.user?.toUserBo()?.let(::cacheUser)
                    comment.replies.mapNotNull { it.user?.toUserBo() }.forEach(::cacheUser)
                }
                comments.toCommentThreadBoList(session.userId)
            }
        }
    }

    suspend fun publish(text: String, turnstileToken: String): Outcome<Long> =
        tokenStore.queuedAuthenticatedOutcome(actionMutex) { session, token ->
            outcomeOf {
                validateContent(text, turnstileToken)
                // 服务端长度检查在 HTML 转义后执行；服务端错误仍原样反馈，不伪造发布成功。
                require(text.length <= TIMELINE_TEXT_LIMIT) { "吐槽超过 $TIMELINE_TEXT_LIMIT 字" }
                val id = timelineApi.publish(text, turnstileToken, token)
                tokenStore.requireCurrent(session)
                // 没有单条读取端点，不合成假动态；刷新本会话已加载 feed。
                refreshLoadedFeeds(session)
                id
            }
        }.forbiddenAs(RejectReason.POSTING_NOT_ALLOWED)

    /**
     * 回复一条动态：[replyTo] 是被回复的那条回复（0 = 回复动态本身）。成功后这条动态的回复数在各列表里加一
     * （没有单条读取端点，回复本身由详情面板重新拉楼层看到）。
     */
    suspend fun reply(id: Long, text: String, replyTo: Int, turnstileToken: String): Outcome<Long> =
        tokenStore.queuedAuthenticatedOutcome(actionMutex) { session, token ->
            outcomeOf {
                validateContent(text, turnstileToken)
                val replyId = timelineApi.reply(id, text, replyTo.toLong(), turnstileToken, token)
                updateItems(session, id) { current -> current.copy(replies = current.replies + 1) }
                replyId
            }
        }.forbiddenAs(RejectReason.POSTING_NOT_ALLOWED)

    /** 发布没有确切结果（超时、断连）时，重取当前账号已加载的各列表：发出去了的话新动态就在里面。 */
    suspend fun refreshLoadedFeeds() = refreshLoadedFeeds(tokenStore.session())

    private fun refreshLoadedFeeds(session: AccountSession) {
        val queries = synchronized(lock) { feeds.keys.filter { it.session == session }.map { it.query } }
        queries.forEach { query -> scope.launch { if (tokenStore.isCurrent(session)) refresh(query) } }
    }

    /**
     * 删除自己的动态。接口在服务端有时 5xx，改用网页会话在自己的时间线页上点删除（[onWebIfServerError]）；
     * 网页回 `ok` 即算做成，不回读：动态读取走服务端缓存，刚删完仍会读到，本地列表直接摘掉这一条。
     */
    suspend fun delete(id: Long): Outcome<Unit> =
        tokenStore.queuedAuthenticatedOutcome(actionMutex) { session, token ->
            val checked = outcomeOf {
                val item = cachedItem(session, id) ?: throw IllegalArgumentException("请先加载待删除的动态")
                require(item.isOwn) { "只能删除本人动态" }
            }
            if (checked is Outcome.Failed) return@queuedAuthenticatedOutcome checked
            val deleted = outcomeOf { timelineApi.delete(id, token) }.onWebIfServerError(RejectReason.TIMELINE_DELETE_UNAVAILABLE) {
                val userKey = requireNotNull(session.username)
                webSessions.eraseTimeline(session, id) { page -> BgmWeb.userTimeline(userKey, page) }
            }
            when (deleted) {
                is Outcome.Success -> outcomeOf { updateItems(session, id) { null } }
                is Outcome.Failed -> deleted
            }
        }

    /**
     * 贴贴：[value] 非 null 是贴（替换我原来那个），null 是取消。按这条动态的 [TimelineItemBo.reactionTarget]
     * 贴到动态本身或那条收藏上；成功后回写缓存，列表行与详情面板同时变。
     */
    suspend fun react(id: Long, value: Int?): Outcome<Unit> =
        tokenStore.queuedAuthenticatedOutcome(actionMutex) { session, token ->
            outcomeOf {
                val item = cachedItem(session, id) ?: throw IllegalArgumentException("请先加载待操作的动态")
                val target = requireNotNull(item.reactionTarget) { "这条动态不能贴贴" }
                require(value == null || value in target.palette) { "不支持的回应类型" }
                when (target) {
                    TimelineReactionTarget.Status -> timelineApi.react(id, value, token)
                    is TimelineReactionTarget.Collect -> nextApi.setSubjectCollectReaction(target.collectId, value, token)
                }
                updateItems(session, id) { current -> current.copy(reactions = current.reactions.withMyReaction(value)) }
            }
        }

    private fun validateContent(text: String, token: String) {
        require(text.isNotBlank()) { "内容不能为空" }
        require(token.isNotBlank()) { "请先完成 Turnstile 验证后再发布" }
    }

    private fun cachedItem(session: AccountSession, id: Long): TimelineItemBo? = synchronized(lock) {
        feeds.keys.filter { it.session == session }.firstNotNullOfOrNull { key -> observerOf(key.query).value.items.find { it.id == id } }
    }

    private suspend fun updateItems(session: AccountSession, id: Long, transform: (TimelineItemBo) -> TimelineItemBo?) {
        tokenStore.commitForSession(session) {
            withContext(feedDispatcher) {
                synchronized(lock) {
                    // 每份 feed 都改，并作废操作之前发出的刷新：它的回包会盖掉这次改动。
                    feeds.filterKeys { it.session == session }.values.forEach { feed ->
                        feed.pager.edit { items -> items.mapNotNull { if (it.id == id) transform(it) else it } }
                    }
                }
            }
        }
    }
}
