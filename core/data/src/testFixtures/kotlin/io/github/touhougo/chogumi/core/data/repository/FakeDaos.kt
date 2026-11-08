package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.calendar.CalendarDao
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionDao
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressDao
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressEntity
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressMetaEntity
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectSnapshot
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeDao
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.FetchStampEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android 11（API 30）及以下系统 SQLite 一条语句能绑定的宿主参数上限。Fake 的查询照这个上限拒绝，
 * 生产里漏了分块的 `in (:ids)` 在 JVM 测试里也会红（宿主机的 SQLite 上限高得多，撞不到）。
 */
private const val SQLITE_MAX_VARIABLES = 999

/** 一条带 [ids] 列表和 [scalars] 个标量参数的语句，在旧系统上绑得下才放行。 */
private fun requireBindable(ids: Collection<*>, scalars: Int) {
    check(ids.size + scalars <= SQLITE_MAX_VARIABLES) { "too many SQL variables: ${ids.size + scalars}" }
}

/**
 * Room `@Transaction` 在 Fake 里的对应：DAO 的组合方法（接口默认实现）在同一把锁里串行执行，
 * 事务里再进事务（组合方法调组合方法）不重复加锁。Fake 只实现原子查询，组合规则一律走生产的默认实现。
 */
class FakeTransactions {
    private val mutex = Mutex()
    private val held = Held()

    private class Held : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    suspend fun <T> run(block: suspend () -> T): T =
        if (coroutineContext[Held] === held) block() else mutex.withLock { withContext(held) { block() } }
}

/**
 * 测试用 Fake DAO（JVM 单测无 Room 运行时，接口手写实现；与各仓库测试同包共享）
 *
 * 所有读写都在同一把锁内完成：搜索/浏览会并发取页（每页各自 mergeAll），
 * 无锁的 MutableMap 在并发写入下会丢行（真实 Room 的 `@Transaction` 是串行化的）。
 * 测试断言读 [store]（锁内拷贝的快照）。
 */
class FakeSubjectDao : SubjectDao {
    private val lock = Any()
    private val rows = mutableMapOf<Int, SubjectEntity>()
    private val observed = MutableStateFlow<List<SubjectEntity>>(emptyList())

    /** 测试断言用快照。 */
    val store: Map<Int, SubjectEntity> get() = synchronized(lock) { rows.toMap() }

    private fun publishLocked() { observed.value = rows.values.toList() }

    override suspend fun get(id: Int): SubjectEntity? = synchronized(lock) { rows[id] }
    override suspend fun getByIds(ids: List<Int>): List<SubjectEntity> = synchronized(lock) {
        requireBindable(ids, scalars = 0)
        ids.mapNotNull { rows[it] }
    }
    override suspend fun upsert(subject: SubjectEntity) = synchronized(lock) {
        rows[subject.id] = subject
        publishLocked()
    }
    override suspend fun upsertAll(subjects: List<SubjectEntity>) = synchronized(lock) {
        subjects.forEach { rows[it.id] = it }
        publishLocked()
    }
    override fun observeByIds(ids: List<Int>): Flow<List<SubjectEntity>> {
        requireBindable(ids, scalars = 0)
        return observed.map { list -> list.filter { it.id in ids } }
    }

    private val transactions = FakeTransactions()
    override suspend fun merge(snapshot: SubjectSnapshot): SubjectEntity = transactions.run { super.merge(snapshot) }
    override suspend fun mergeAll(snapshots: List<SubjectSnapshot>): List<SubjectEntity> = transactions.run { super.mergeAll(snapshots) }
}

/** 章节表 Fake：读写在同一把锁内（几部作品的章节会并发写入），[replaceAll] 走生产的默认实现、按事务串行。 */
class FakeEpisodeDao : EpisodeDao {
    private val lock = Any()
    private val rows = mutableMapOf<Int, EpisodeEntity>()
    private val stamps = mutableMapOf<Triple<Int, String, Int>, Long>()
    private val lastWrite = MutableStateFlow<Long?>(null)

    /** 测试断言用快照。 */
    val store: Map<Int, EpisodeEntity> get() = synchronized(lock) { rows.toMap() }

    private fun publishLocked() {
        lastWrite.value = rows.values.maxOfOrNull { it.updatedAt }
    }
    override suspend fun getBySubject(subjectId: Int): List<EpisodeEntity> =
        synchronized(lock) { rows.values.filter { it.subjectId == subjectId }.sortedBy { it.sort } }
    override suspend fun getBySubjectIds(subjectIds: List<Int>): List<EpisodeEntity> = synchronized(lock) {
        requireBindable(subjectIds, scalars = 0)
        rows.values.filter { it.subjectId in subjectIds }.sortedWith(compareBy({ it.subjectId }, { it.sort }))
    }
    override suspend fun upsertAll(episodes: List<EpisodeEntity>) = synchronized(lock) {
        episodes.forEach { rows[it.id] = it }
        publishLocked()
    }
    override suspend fun deleteBySubject(subjectId: Int) = synchronized(lock) {
        rows.values.removeAll { it.subjectId == subjectId }
        publishLocked()
    }
    override fun observeLastWrite(): Flow<Long?> = lastWrite
    override suspend fun fetchedAt(subjectId: Int, section: String, viewerId: Int): Long? =
        synchronized(lock) { stamps[Triple(subjectId, section, viewerId)] }
    override suspend fun putStamp(stamp: FetchStampEntity) = synchronized(lock) {
        stamps[Triple(stamp.subjectId, stamp.section, stamp.viewerId)] = stamp.fetchedAt
    }

    private val transactions = FakeTransactions()
    override suspend fun replaceAll(subjectId: Int, episodes: List<EpisodeEntity>, fetchedAt: Long) =
        transactions.run { super.replaceAll(subjectId, episodes, fetchedAt) }
}

class FakeCalendarDao : CalendarDao {
    val store = mutableListOf<CalendarEntity>()
    override suspend fun getAll() = store.toList()
    override suspend fun upsertAll(items: List<CalendarEntity>) {
        items.forEach { item ->
            store.removeAll { it.weekday == item.weekday && it.subjectId == item.subjectId }
            store.add(item)
        }
    }
    override suspend fun deleteAll() { store.clear() }
}

/**
 * 收藏表 Fake，复合主键 (accountId, subjectId)。
 *
 * 所有读写都在同一把锁内完成：ViewModel 会为多个内容域并发发起请求，
 * 无锁的 MutableMap 在并发 upsert/removeAll 下会丢行（真实 Room 是事务性的）。
 * 测试断言读 [store]（锁内拷贝的快照）。
 */
open class FakeUserCollectionDao : UserCollectionDao {
    private val lock = Any()
    private val rows = mutableMapOf<Pair<Int, Int>, UserCollectionEntity>()
    private val observed = MutableStateFlow<List<UserCollectionEntity>>(emptyList())

    /** 测试断言用快照。 */
    val store: Map<Pair<Int, Int>, UserCollectionEntity> get() = synchronized(lock) { rows.toMap() }

    private fun key(e: UserCollectionEntity) = e.accountId to e.subjectId
    private fun matches(e: UserCollectionEntity, accountId: Int, subjectType: Int, type: Int?) =
        e.accountId == accountId && e.subjectType == subjectType && (type == null || e.type == type)
    private fun publishLocked() { observed.value = rows.values.toList() }

    override suspend fun get(subjectType: Int, type: Int?, accountId: Int): List<UserCollectionEntity> =
        synchronized(lock) {
            rows.values.filter { matches(it, accountId, subjectType, type) && it.detailsLoaded }
                .sortedByDescending { it.updatedAt }
        }

    /** 单条 [getBySubjectId] 的调用次数，用于断言「批量合并不逐条查」。 */
    var getBySubjectIdCallCount = 0
        private set

    override suspend fun getBySubjectId(subjectId: Int, accountId: Int): UserCollectionEntity? =
        synchronized(lock) {
            getBySubjectIdCallCount++
            rows[accountId to subjectId]
        }

    override suspend fun getBySubjectIds(subjectIds: List<Int>, accountId: Int): List<UserCollectionEntity> =
        synchronized(lock) {
            requireBindable(subjectIds, scalars = 1)
            subjectIds.mapNotNull { rows[accountId to it] }
        }

    /**
     * 与真实 Room 查询保持同语义：
     * `where accountId = :accountId and detailsLoaded = 1 order by updatedAt desc`。
     * Fake 若忽略这两个条件，会与生产行为分叉（例如把未加载详情的摘要行也当成完整收藏）。
     */
    override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> =
        observed.map { rows ->
            rows.filter { it.accountId == accountId && it.detailsLoaded }
                .sortedByDescending { it.updatedAt }
        }

    override suspend fun hasRows(accountId: Int): Boolean = synchronized(lock) { rows.keys.any { it.first == accountId } }

    override suspend fun upsertAll(collections: List<UserCollectionEntity>) = synchronized(lock) {
        collections.forEach { rows[key(it)] = it }
        publishLocked()
    }

    override suspend fun upsert(collection: UserCollectionEntity) = synchronized(lock) {
        rows[key(collection)] = collection
        publishLocked()
    }

    override suspend fun subjectIdsIn(subjectType: Int, type: Int?, accountId: Int): List<Int> =
        synchronized(lock) { rows.values.filter { matches(it, accountId, subjectType, type) }.map { it.subjectId } }

    override suspend fun deleteBySubjectIds(subjectIds: List<Int>, accountId: Int) = synchronized(lock) {
        requireBindable(subjectIds, scalars = 1)
        rows.keys.removeAll { (account, subjectId) -> account == accountId && subjectId in subjectIds }
        publishLocked()
    }

    private val transactions = FakeTransactions()
    override suspend fun upsertChanged(collections: List<UserCollectionEntity>) = transactions.run { super.upsertChanged(collections) }
    override suspend fun replaceSnapshot(accountId: Int, subjectType: Int, type: Int?, rows: List<UserCollectionEntity>) =
        transactions.run { super.replaceSnapshot(accountId, subjectType, type, rows) }
}

/** 逐集观看状态 Fake（key 为 (accountId, episodeId)，与真实复合主键一致）。 */
open class FakeEpisodeWatchStateDao : EpisodeWatchStateDao {
    private val lock = Any()
    private val rows = mutableMapOf<Pair<Int, Int>, EpisodeWatchStateEntity>()
    private val observed = MutableStateFlow<List<EpisodeWatchStateEntity>>(emptyList())

    /** 测试断言用快照。 */
    val store: Map<Pair<Int, Int>, EpisodeWatchStateEntity> get() = synchronized(lock) { rows.toMap() }

    /** 测试钩子：每次逐集行变化后（持锁）同步回调。Flow 会合并相邻更新，要看完整的变化历史只能靠它。 */
    @Volatile var onChange: ((Collection<EpisodeWatchStateEntity>) -> Unit)? = null

    private fun publishLocked() {
        observed.value = rows.values.toList()
        onChange?.invoke(rows.values)
    }

    override suspend fun getBySubject(subjectId: Int, accountId: Int): List<EpisodeWatchStateEntity> =
        synchronized(lock) { rows.values.filter { it.accountId == accountId && it.subjectId == subjectId }.sortedBy { it.episodeId } }

    override suspend fun getBySubjects(subjectIds: List<Int>, accountId: Int): List<EpisodeWatchStateEntity> =
        synchronized(lock) {
            requireBindable(subjectIds, scalars = 1)
            rows.values.filter { it.accountId == accountId && it.subjectId in subjectIds }
        }

    override fun observeBySubject(subjectId: Int, accountId: Int): Flow<List<EpisodeWatchStateEntity>> =
        observed.map { list ->
            synchronized(lock) {
                list.filter { it.accountId == accountId && it.subjectId == subjectId }.sortedBy { it.episodeId }
            }
        }

    override fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>> =
        observed.map { list ->
            synchronized(lock) {
                list.filter { it.accountId == accountId }.sortedBy { it.episodeId }
            }
        }

    override suspend fun upsertAll(rows: List<EpisodeWatchStateEntity>) = synchronized(lock) {
        rows.forEach { this.rows[it.accountId to it.episodeId] = it }
        publishLocked()
    }

    override suspend fun deleteBySubject(subjectId: Int, accountId: Int) {
        synchronized(lock) {
            rows.values.removeAll { it.accountId == accountId && it.subjectId == subjectId }
            publishLocked()
        }
    }

    private val transactions = FakeTransactions()
    override suspend fun replaceForSubject(subjectId: Int, accountId: Int, rows: List<EpisodeWatchStateEntity>) =
        transactions.run { super.replaceForSubject(subjectId, accountId, rows) }
    override suspend fun upsertChanged(rows: List<EpisodeWatchStateEntity>) = transactions.run { super.upsertChanged(rows) }
}

/** 首页进度区块 Fake（key 为 (accountId, subjectId)）。 */
class FakeHomeProgressDao : HomeProgressDao {
    private val lock = Any()
    private val rows = mutableMapOf<Pair<Int, Int>, HomeProgressEntity>()
    private val meta = mutableMapOf<Int, HomeProgressMetaEntity>()

    /** 每次写入加一，[observeByAccount] 跟着它重读（同 Room 的失效通知）。 */
    private val version = MutableStateFlow(0)

    val store: Map<Pair<Int, Int>, HomeProgressEntity> get() = synchronized(lock) { rows.toMap() }

    override suspend fun getByAccount(accountId: Int): List<HomeProgressEntity> =
        synchronized(lock) { rows.values.filter { it.accountId == accountId }.sortedByDescending { it.updatedAt } }

    override fun observeByAccount(accountId: Int): Flow<List<HomeProgressEntity>> = version.map { getByAccount(accountId) }

    override suspend fun getMeta(accountId: Int): HomeProgressMetaEntity? = synchronized(lock) { meta[accountId] }

    override suspend fun upsertAll(rows: List<HomeProgressEntity>) {
        synchronized(lock) { rows.forEach { this.rows[it.accountId to it.subjectId] = it } }
        version.update { it + 1 }
    }

    override suspend fun upsertMeta(meta: HomeProgressMetaEntity) = synchronized(lock) { this.meta[meta.accountId] = meta }

    override suspend fun deleteByAccount(accountId: Int) {
        synchronized(lock) {
            rows.values.removeAll { it.accountId == accountId }
        }
        version.update { it + 1 }
    }
}
