package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.mapper.toDomain
import io.github.touhougo.chogumi.core.data.db.mapper.toSnapshot
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.util.SharedFetch
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.statistics.StatisticsInput
import io.github.touhougo.chogumi.core.model.statistics.calculateCollectionStatistics
import io.github.touhougo.chogumi.core.model.statistics.otherWatchedEpisodes
import io.github.touhougo.chogumi.core.data.util.LruMap
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * 他人生涯页需要的数据切面（与 [CollectionStatsDataSource] 同理：界面层的单测只 fake 这两个方法）。
 */
interface OtherCareerDataSource {
    /** TA 某一类公开收藏的完整统计；首次现拉，之后读会话内缓存（只缓存成功结果，失败后再调即重拉）。 */
    suspend fun statisticsFor(
        username: String,
        subjectType: SubjectType,
        currentYear: Int,
        onProgress: (SyncProgress) -> Unit = {},
    ): Outcome<CollectionStatistics>

    /**
     * TA 这一类是否已在会话内缓存里。生涯卡与完整统计页各有一个 VM，
     * 新 VM 据此直接读缓存，而不是把已汇总过的类别再交给用户点一次「汇总」。
     */
    fun hasCached(username: String, subjectType: SubjectType): Boolean

    /**
     * TA 几类公开收藏合起来的统计（生涯卡读它）：每一类走与 [statisticsFor] 同一份缓存，已汇总过的不再拉。
     * 任一类拉取失败即失败。
     */
    suspend fun overallStatisticsFor(username: String, subjectTypes: Collection<SubjectType>, currentYear: Int): Outcome<CollectionStatistics>

    /**
     * TA 某一类的封面拼图用的作品（用户卡片用，不汇总）：已在会话缓存里的类别读缓存，否则只取**第一页**，
     * 在这一页里按 [CollectionStatistics.coverPicks] 同一条规则挑。一页之外的收藏不看，换来的是点头像不下载全量。
     */
    suspend fun coverPicksFor(username: String, subjectType: SubjectType, currentYear: Int): Outcome<List<SubjectBo>>
}

/**
 * 他人公开收藏的**会话内内存缓存**，也是界面层取他人收藏的唯一入口：
 * 主页「收藏」tab、生涯卡与完整统计页读的是同一份，任一处拉过，其余处直接复用。
 *
 * - **绝不落本地收藏表**：拉来的数据只在内存里活到本次 App 会话结束，不写进自己的数据库
 *   （否则切号后 accountId 相同的行会「复活」成自己的数据，见 [fetchCollections]）；
 * - LRU 保留最近 [MAX_USERS] 个用户，按 `username` 分桶；每个用户桶内按类型存；
 * - 统计用**同一个**纯函数 [calculateCollectionStatistics]，只是数据源不同——不存在第二份算法。
 *
 * 公开收藏量超过 [AUTO_SUMMARIZE_LIMIT] 的类别不在这里拦：是否自动汇总由
 * [shouldAutoSummarize] 判定，调用方据此决定「打开就拉」还是「等用户点按钮」。
 */
class OtherUserCollectionsCache(
    private val collectionApi: CollectionApi,
    private val subjectDao: SubjectDao,
    private val tokenStore: TokenStore,
    private val clock: AppClock,
    /**
     * 统计计算的调度器——与 [LocalCareerStatistics] 用**同一个**注入值，
     * 保证他人统计与本人统计都在同一线程策略下计算，不落在调用方线程。
     */
    private val computationDispatcher: CoroutineDispatcher,
) : OtherCareerDataSource {
    private data class CachedCollections(val values: List<UserCollectionBo>, val version: Long)
    private data class FetchKey(val session: AccountSession, val username: String, val type: SubjectType)
    private class WriteOrder(var active: Int = 0, var newestSuccess: Long = Long.MIN_VALUE)
    /** 缓存与在途写入顺序由同一把锁保护，会话变化时整份缓存作废。 */
    private val buckets = LruMap<String, MutableMap<SubjectType, CachedCollections>>(MAX_USERS)
    private var bucketsSession: AccountSession? = null
    private val permits = Semaphore(SYNC_CONCURRENCY)
    private val fetches = SharedFetch<FetchKey, Outcome<List<UserCollectionBo>>>()
    private val versions = AtomicLong()
    private val writeOrders = mutableMapOf<FetchKey, WriteOrder>()
    private val progressGroups = mutableMapOf<FetchKey, ProgressGroup>()

    /** 每个冷读都订阅同一轮的最新进度；最后一个调用结束时删除该组。 */
    private class ProgressGroup {
        val listeners = mutableListOf<(SyncProgress) -> Unit>()
        var latest: SyncProgress? = null
    }

    /**
     * 取某用户某类型的公开收藏；命中缓存直接返回，否则现拉并写入缓存。
     * 锁内只有 map 读写、不挂起，所以用 `synchronized`，[hasCached] 因此可以是普通函数。
     *
     * [refresh] = true 时跳过缓存重拉并覆盖（「收藏」tab 下拉刷新）；
     * [onProgress] 透传 [fetchCollections] 的页进度。
     */
    suspend fun collectionsFor(
        username: String,
        subjectType: SubjectType,
        refresh: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): Outcome<List<UserCollectionBo>> {
        // 私密收藏随会话变；即使重新登录同一账号也不能复用旧请求。
        val session = tokenStore.session()
        val key = FetchKey(session, username, subjectType)
        synchronized(buckets) {
            if (bucketsSession != session) {
                buckets.clear()
                bucketsSession = session
            }
            if (!refresh) buckets[username]?.get(subjectType)?.let { return Outcome.Success(it.values) }
        }

        suspend fun fetch(onChange: (SyncProgress) -> Unit): Outcome<List<UserCollectionBo>> {
            val (version, order) = synchronized(buckets) {
                versions.incrementAndGet() to writeOrders.getOrPut(key) { WriteOrder() }.also { it.active++ }
            }
            try {
                return fetchCollections(session, username, subjectType, onChange).also { result ->
                    if (result is Outcome.Success) synchronized(buckets) {
                        if (bucketsSession == session && version > order.newestSuccess) {
                            order.newestSuccess = version
                            val bucket = buckets.getOrPut(username) { mutableMapOf() }
                            if ((bucket[subjectType]?.version ?: Long.MIN_VALUE) < version) {
                                bucket[subjectType] = CachedCollections(result.value, version)
                            }
                        }
                    }
                }
            } finally {
                synchronized(buckets) {
                    order.active--
                    if (order.active == 0) writeOrders.remove(key, order)
                }
            }
        }
        // 用户刷新明确开始新一轮；较早启动的请求即使晚完成也不能覆盖新结果。
        if (refresh) return fetch(onProgress)

        val listener: (SyncProgress) -> Unit = { onProgress(it) }
        val group = synchronized(progressGroups) {
            progressGroups.getOrPut(key) { ProgressGroup() }.also { it.listeners.add(listener) }
        }
        try {
            synchronized(progressGroups) { group.latest?.let(listener) }
            val result = fetches.fetch(key) {
                synchronized(buckets) {
                    if (bucketsSession == session) buckets[username]?.get(subjectType)?.let { return@fetch Outcome.Success(it.values) }
                }
                synchronized(progressGroups) {
                    val initial = SyncProgress(active = true, completedPages = 0, totalPages = null)
                    group.latest = initial
                    group.listeners.toList().forEach { it(initial) }
                }
                fetch { progress ->
                    synchronized(progressGroups) {
                        val previous = group.latest
                        if (previous == null || progress.completedPages > previous.completedPages ||
                            (progress.completedPages == previous.completedPages && (progress.totalPages ?: 0) >= (previous.totalPages ?: 0))
                        ) {
                            group.latest = progress
                            group.listeners.toList().forEach { it(progress) }
                        }
                    }
                }
            }
            return when (val current = outcomeOf { tokenStore.requireCurrent(session) }) {
                is Outcome.Failed -> current
                is Outcome.Success -> result
            }
        } finally {
            synchronized(progressGroups) {
                group.listeners.remove(listener)
                if (group.listeners.isEmpty()) progressGroups.remove(key, group)
            }
        }
    }

    /**
     * 现拉**他人**某一类型的公开收藏全量，带进度：
     *
     * - **收藏行绝不落本地收藏表**：那张表按 `accountId` 隔离本账号的收藏，他人的行混进去，
     *   切号后会「复活」成自己的数据；
     * - **条目快照照常合并进条目表**：条目表不分账号，与本人同步同一条 [SubjectDao.mergeAll] 路径，
     *   摘要不覆盖详情字段、不给详情续期，详情页与本人收藏因此能复用这些条目；
     * - 服务端 v0 端点匿名可读；私密收藏的可见性由服务端根据查看者身份决定，所以登录时带上自己的令牌；
     * - [onProgress] 每完成一页回调（已完成页 / 已知总页数，未知为 null）；分页与限流同本人收藏（[fetchAllUserCollections]）。
     */
    private suspend fun fetchCollections(
        viewer: AccountSession,
        username: String,
        subjectType: SubjectType,
        onProgress: (SyncProgress) -> Unit,
    ): Outcome<List<UserCollectionBo>> = outcomeOf {
        val all = collectionApi.fetchAllUserCollections(
            tokenStore = tokenStore,
            username = username,
            subjectType = subjectType,
            type = null,
            session = viewer.takeIf { it.authenticated },
            permits = permits,
            progress = PageCounter { completed, planned ->
                onProgress(SyncProgress(active = true, completedPages = completed, totalPages = planned.takeIf { it > 0 }))
            },
        )
        // 取的途中换了视角（含游客登录后）：这份列表不是现在这个查看者的，丢掉
        tokenStore.requireCurrent(viewer)
        val now = clock.nowMillis()
        subjectDao.mergeAll(all.mapNotNull { it.subject?.toSnapshot(now) })
        tokenStore.requireCurrent(viewer)
        all.map { it.toDomain() }
    }

    override fun hasCached(username: String, subjectType: SubjectType): Boolean =
        synchronized(buckets) { buckets[username]?.containsKey(subjectType) == true }

    /**
     * 取某用户某类型的**完整统计**（同一纯函数，他人数据源）。
     *
     * [currentYear] 由调用方给定（本层不读系统时钟）；观看集数用 [otherWatchedEpisodes]
     * 近似（他人取不到逐集表），汇总出的投入时间因此标估算。
     */
    override suspend fun statisticsFor(
        username: String,
        subjectType: SubjectType,
        currentYear: Int,
        onProgress: (SyncProgress) -> Unit,
    ): Outcome<CollectionStatistics> {
        return when (val result = collectionsFor(username, subjectType, onProgress = onProgress)) {
            is Outcome.Success -> Outcome.Success(withContext(computationDispatcher) { statisticsOf(result.value, currentYear) })
            is Outcome.Failed -> result
        }
    }

    override suspend fun overallStatisticsFor(
        username: String,
        subjectTypes: Collection<SubjectType>,
        currentYear: Int,
    ): Outcome<CollectionStatistics> {
        val collections = mutableListOf<UserCollectionBo>()
        for (subjectType in subjectTypes) {
            when (val result = collectionsFor(username, subjectType)) {
                is Outcome.Success -> collections += result.value
                is Outcome.Failed -> return result
            }
        }
        return Outcome.Success(withContext(computationDispatcher) { statisticsOf(collections, currentYear) })
    }

    override suspend fun coverPicksFor(username: String, subjectType: SubjectType, currentYear: Int): Outcome<List<SubjectBo>> {
        val session = tokenStore.session()
        val cached = synchronized(buckets) { buckets[username]?.get(subjectType)?.values?.takeIf { bucketsSession == session } }
        val collections = cached ?: when (val page = recentPage(session, username, subjectType)) {
            is Outcome.Success -> page.value
            is Outcome.Failed -> return page
        }
        return Outcome.Success(withContext(computationDispatcher) { statisticsOf(collections, currentYear).coverPicks })
    }

    /** 第一页公开收藏（推测服务端按更新时间倒序，未验证）；不写缓存（缓存只存完整列表）。 */
    private suspend fun recentPage(viewer: AccountSession, username: String, subjectType: SubjectType): Outcome<List<UserCollectionBo>> = outcomeOf {
        collectionApi.fetchUserCollectionPage(tokenStore, username, subjectType, type = null, session = viewer.takeIf { it.authenticated })
            .data.map { it.toDomain() }
    }

    /** 他人收藏的统计：取不到逐集表，观看集数用 [otherWatchedEpisodes] 近似。 */
    private fun statisticsOf(collections: List<UserCollectionBo>, currentYear: Int): CollectionStatistics =
        StatisticsInput(
            collections = collections,
            watchedMainEpisodes = otherWatchedEpisodes(collections),
            episodesApproximate = true,
            currentYear = currentYear,
        ).calculateCollectionStatistics()

    private companion object {
        /** 会话内保留的用户数上限（设计决定，非 M3）。 */
        const val MAX_USERS = 3
    }
}

/**
 * 是否**自动汇总**他人某一类的公开收藏。
 *
 * [count] 为该类型的公开收藏条数（0 或超出上限都不自动汇总）。
 * 上限 [AUTO_SUMMARIZE_LIMIT] 定义在**唯一一处**，超过它要用户点按钮。
 */
fun shouldAutoSummarize(count: Int): Boolean = count in 1..AUTO_SUMMARIZE_LIMIT

/**
 * 自动汇总的上限：某一类公开收藏不超过这些条就打开「关于」时自动汇总。
 * **设计决定（not in M3）**：1000 条 = v0 每页 50 的 20 页。
 */
const val AUTO_SUMMARIZE_LIMIT = 1000
