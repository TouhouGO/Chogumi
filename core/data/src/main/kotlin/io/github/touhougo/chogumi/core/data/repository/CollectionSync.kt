package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionDao
import io.github.touhougo.chogumi.core.model.statistics.countsForLineup
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 本人收藏的全量同步：五类收藏、逐集记录与阵容一起补齐（[refreshAllUserCollections]）。 */
class CollectionSync(
    private val collectionApi: CollectionApi,
    private val collectionStore: CollectionStore,
    private val userCollectionDao: UserCollectionDao,
    private val tokenStore: TokenStore,
    private val syncPreferenceStore: SyncPreferenceStore,
    /** 时间源：同步新鲜度与成功时刻用。 */
    private val clock: AppClock,
    /** 逐集记录的取回与落库（[WatchStateStore.fetchWatchHistory] / [WatchStateStore.writeWatchHistories]）。 */
    private val watchStates: WatchStateStore,
    /** 快照落库时不盖本地正写着或取第一页后写过的条目。 */
    private val holds: LocalProgressHolds,
    /** 生涯阵容读写的角色与制作人员缓存（条目详情页的同一份）。 */
    private val lineupSource: SubjectLineupSource,
) : CollectionSyncing {
    /**
     * 收藏同步进度（已完成页 / 总页数），供统计页顶部进度条读取。
     *
     * [SyncProgress.totalPages] 在拿到首屏 `total` 之前为 null（**不定进度**），
     * 拿到后转**确定进度**——与原型左栏「不定 → 确定」一致。
     */
    private val _syncProgress = MutableStateFlow(SyncProgress())
    override val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    /**
     * 本进程内**已自动同步过**的账号集合。
     *
     * 「一次打开 App 只自动同步一次」的进程内那一半判据：本类是 Koin single，
     * 其生命周期即进程生命周期；`force = false` 的自动路径命中这里即跳过，
     * 与原型的「打开一次 App 只自动同步一次」一致。重启 App（新实例）后为空，重新允许同步。
     * 与「距上次成功 < 30 分钟」一起收在 [isSyncFresh] 一处判定。
     */
    private val processSyncedAccounts: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** 是否有一次全量同步在跑；[refreshAllUserCollections] 的「同一时刻只跑一次」只认它。 */
    private val syncRunning = AtomicBoolean(false)

    /**
     * 全类型收藏同步。
     *
     * ## 同步怎么跑
     *
     * 1. **五类同时开始，共用**一个全局并发上限 [SYNC_CONCURRENCY]（不是每类各 4、合计 20）。
     *    每类的每一页（第一页也算）受同一个 `Semaphore` 约束——先取第 1 页拿到 total，再并发取余页。
     * 2. **新鲜度**：非强制时，距上次成功不到 [SYNC_FRESHNESS_MILLIS] 就跳过整次同步
     *    （「一次打开 App 只自动同步一次、30 分钟内不重来」）。成功时刻写进 [syncPreferenceStore]。
     * 3. **进度**：完成页 / 总页数经 [syncProgress] 暴露，供统计页画顶部进度条。
     *    首屏（总页数未知）先不定进度，拿到 total 后转确定进度。
     *
     * [force] = true 绕过新鲜度（下拉刷新 / 统计页 ⟳）。类型失败即整次失败并保留其他类型缓存。
     *
     * 4. **同一时刻只跑一次**：生涯卡与统计页各自会请求同步，已有一次在跑时后来者直接返回
     *    （强制的也一样——在跑的那次就是最新的），进度照旧由 [syncProgress] 播报，不开第二路分页。
     * 5. **逐集记录**：收藏都落库后，补拉逐集表和收藏对不上的作品（[syncWatchHistory]），共用同一个并发上限。
     * 6. **阵容**：再给算进阵容、角色或制作人员还没缓存的作品补拉这两样（[syncLineups]），同一个并发上限。
     */
    override suspend fun refreshAllUserCollections(force: Boolean): Outcome<Unit> {
        if (!syncRunning.compareAndSet(false, true)) return Outcome.Success(Unit)
        return try {
            syncAllUserCollections(force)
        } finally {
            syncRunning.set(false)
        }
    }

    private suspend fun syncAllUserCollections(force: Boolean): Outcome<Unit> = try {
        outcomeOf<Unit> {
            val session = tokenStore.requireSession()
            val accountId = session.userId!!
            if (!force && isSyncFresh(accountId)) return Outcome.Success(Unit)
            _syncProgress.value = SyncProgress(active = true, completedPages = 0, totalPages = null)

            val semaphore = Semaphore(SYNC_CONCURRENCY)
            // 五类的页数合在一条进度里：拿到各自的 total 才登记，登记之前总页数是 0
            val pages = PageCounter { completed, planned ->
                _syncProgress.value = SyncProgress(active = true, completedPages = completed, totalPages = planned)
            }
            val results = coroutineScope {
                ContentDomains.map { subjectType ->
                    async { syncSubjectType(session, subjectType, semaphore, pages) }
                }.awaitAll()
            }
            results.firstNotNullOfOrNull { it }?.let { return Outcome.Failed(it) }
            syncWatchHistory(session, semaphore)
            syncLineups(session, semaphore)

            // 途中换了号，这个账号的后半段（逐集记录、阵容）没做完：不记成已同步
            tokenStore.requireCurrent(session)
            syncPreferenceStore.markSynced(accountId, clock.nowMillis())
            // 本进程已成功同步过这一账号——即便之后超过 30 分钟，同一次 App 打开也不再自动重同步。
            processSyncedAccounts.add(accountId)
        }
    } finally {
        _syncProgress.value = SyncProgress(active = false, completedPages = 0, totalPages = null)
    }

    /** 同步一类的全部分页并原子落库；成功返回 null，失败返回原因。分页与限流、进度见 [fetchAllUserCollections]。 */
    private suspend fun syncSubjectType(
        session: AccountSession,
        subjectType: SubjectType,
        semaphore: Semaphore,
        pages: PageCounter,
    ): Failure? = outcomeOf<Unit> {
        val start = holds.beginRead()
        val all = collectionApi.fetchAllUserCollections(
            tokenStore = tokenStore,
            username = session.username!!,
            subjectType = subjectType,
            type = null,
            session = session,
            permits = semaphore,
            progress = pages,
        )
        collectionStore.applySnapshot(session, subjectType, null, all, start)
    }.failureOrNull()

    /**
     * 收藏同步完后补齐逐集记录（生涯节奏的图读它）：只拉逐集表和收藏对不上的作品——服务端的「看过几集」
     * （`epStatus`，看过的记录条数）和本地「看过」的行数不同，或者本地还没有这部的章节（要首播日）。
     * 第一次几乎全拉，之后只拉在别处标过集的作品；拉过一次两边就对上了，不会每次重拉。
     *
     * 一部失败不影响别的，也不让整次同步失败：没对上的下次同步还会拉；被占用的作品不写逐集状态。
     * 进度沿用 [syncProgress]，一部算一页。
     */
    private suspend fun syncWatchHistory(session: AccountSession, semaphore: Semaphore) {
        val accountId = session.userId!!
        val collections = userCollectionDao.observeCurrent(accountId).first().filter { row ->
            row.epStatus > 0 && row.type != CollectionType.WISH.value &&
                SubjectType.from(row.subjectType)?.let(EpisodeTracking::tracksEpisodes) == true
        }
        if (collections.isEmpty()) return
        val ids = collections.map { it.subjectId }
        val ledger = watchStates.watchHistoryLedger(ids, session)
        val chaptered = ledger.chaptered
        val stale = ids.filterNot { it in ledger.current }
        if (stale.isEmpty()) return
        val done = AtomicInteger(0)
        _syncProgress.value = SyncProgress(active = true, completedPages = 0, totalPages = stale.size)
        // 取回仍按部并发；落库攒够 [WATCH_HISTORY_BATCH] 部一个事务：逐集表每失效一次，订阅它的整表快照与统计就整表重算一次
        val pending = ArrayList<WatchHistory>(WATCH_HISTORY_BATCH)
        val pendingLock = Mutex()
        suspend fun flush() {
            if (pending.isEmpty()) return
            val batch = pending.toList()
            pending.clear()
            outcomeOf { watchStates.writeWatchHistories(batch, session) }
        }
        coroutineScope {
            stale.map { subjectId ->
                async {
                    val fetched = outcomeOf {
                        watchStates.fetchWatchHistory(subjectId, session, writeChapters = subjectId !in chaptered, permits = semaphore)
                    }.valueOrNull()
                    if (fetched != null) pendingLock.withLock {
                        pending += fetched
                        if (pending.size >= WATCH_HISTORY_BATCH) flush()
                    }
                    _syncProgress.value = SyncProgress(active = true, completedPages = done.incrementAndGet(), totalPages = stale.size)
                }
            }.awaitAll()
        }
        pendingLock.withLock { flush() }
    }

    /**
     * 生涯阵容的数据：算进阵容的收藏（[countsForLineup]）里，角色或制作人员还没缓存的作品各拉一次，
     * 存进条目详情页的同一份缓存。拉过就不再拉（缓存被清掉后才会再拉）；一部失败不影响别的，也不让同步失败。
     * 进度沿用 [syncProgress]，一部算一页。
     */
    private suspend fun syncLineups(session: AccountSession, semaphore: Semaphore) {
        val counted = userCollectionDao.observeCurrent(session.userId!!).first().filter { row ->
            val type = CollectionType.from(row.type) ?: return@filter false
            val subjectType = SubjectType.from(row.subjectType) ?: return@filter false
            countsForLineup(type, subjectType)
        }
        val missing = lineupSource.missingLineups(counted.map { it.subjectId })
        if (missing.isEmpty()) return
        val done = AtomicInteger(0)
        _syncProgress.value = SyncProgress(active = true, completedPages = 0, totalPages = missing.size)
        coroutineScope {
            missing.map { subjectId ->
                async {
                    // 途中换了号：不再为上一个账号的作品按新账号的视角发请求
                    if (!tokenStore.isCurrent(session)) return@async
                    outcomeOf { lineupSource.fetchLineup(subjectId, session, semaphore) }
                    _syncProgress.value = SyncProgress(active = true, completedPages = done.incrementAndGet(), totalPages = missing.size)
                }
            }.awaitAll()
        }
    }

    override suspend fun lastSyncedAt(): Long? {
        val session = tokenStore.session().takeIf { it.authenticated } ?: return null
        return syncPreferenceStore.lastSyncedAt(session.userId!!)
    }

    /**
     * 自动同步是否应当跳过。**两条判据收在这一处**：
     * 1. 本进程已自动同步过该账号（一次打开 App 只自动同步一次）；
     * 2. 距上次成功不到 [SYNC_FRESHNESS_MILLIS]，且本地库里有这个账号的收藏：同步时刻在偏好里、会随备份恢复，
     *    收藏在被备份排除的数据库里，换机后只有时刻没有数据，不能算新鲜。
     * 从没成功同步过（没有记录）时判据 2 恒为不新鲜，但仍受判据 1 约束。
     */
    private suspend fun isSyncFresh(accountId: Int): Boolean {
        if (accountId in processSyncedAccounts) return true
        val last = syncPreferenceStore.lastSyncedAt(accountId) ?: return false
        return clock.nowMillis() - last < SYNC_FRESHNESS_MILLIS && userCollectionDao.hasRows(accountId)
    }
}

/**
 * 自动同步的新鲜期。**本项目设计决定（not in M3）**：距上次成功不到 30 分钟就跳过。
 * 强制刷新（下拉 / ⟳）传 `force = true` 绕过。
 */
private const val SYNC_FRESHNESS_MILLIS = 30 * 60 * 1000L

/** 同步补拉逐集记录时几部一个事务落库（设计决定）：首次同步上百部时，逐集表的失效从每部一次降到每批一次。 */
private const val WATCH_HISTORY_BATCH = 20
