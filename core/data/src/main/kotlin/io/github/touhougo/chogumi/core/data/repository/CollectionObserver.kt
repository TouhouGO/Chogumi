package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.SQLITE_IN_CHUNK
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionDao
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map as mapLoad
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart

/**
 * 当前账号逐集状态的一次快照：区块归属用的逐集状态、待看排序用的每部作品最后「看过」时刻，
 * 与生涯节奏用的每集「看过」时刻，出自同一批行，三者不会错拍。
 */
data class EpisodeWatchStateSnapshot(
    val statesBySubject: Map<Int, Map<Int, EpisodeStatus>> = emptyMap(),
    val latestWatchedAtBySubject: Map<Int, Long> = emptyMap(),
    /** subjectId → (episodeId → 标成「看过」的时刻)；只含看过且带时刻的集。 */
    val watchedAtBySubject: Map<Int, Map<Int, Long>> = emptyMap(),
)

/** 当前收藏与逐集数据：从新查询读取，而非复用一个可能还未消费更新的长期订阅。 */
data class CollectionProgressSnapshot(
    val collections: List<UserCollectionBo>,
    val episodes: EpisodeWatchStateSnapshot,
)

/** 进度组合流的一拍：换号先发 [Reset] 作废上一账号的组合值，再读新账号的 [Value]。 */
sealed interface CollectionProgressUpdate {
    data object Reset : CollectionProgressUpdate
    data class Value(val load: Load<CollectionProgressSnapshot>) : CollectionProgressUpdate
}

/**
 * 本地收藏与逐集状态的读取：收藏按账户持久化，所有界面观察同一数据源（写入在 [CollectionStore]、[WatchStateStore]、
 * [CollectionSync]）。只读本地，不发请求。
 */
class CollectionObserver(
    private val subjectDao: SubjectDao,
    private val userCollectionDao: UserCollectionDao,
    private val episodeWatchStateDao: EpisodeWatchStateDao,
    private val tokenStore: TokenStore,
    /** 映射与分组的调度器（生产是 computationDispatcher）：上千部收藏的映射不能跑在订阅方（`viewModelScope` = Main）的线程上。 */
    private val computationDispatcher: CoroutineDispatcher,
) {
    /**
     * 本机观察的账号边界：同一订阅里换号先发空值清掉旧账号，同一账号重新订阅不伪造空拍；
     * 本机读取失败是一拍 [Load.Failed]，失败后停在这里等换号，恢复由收集方的显式重试重新订阅。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> observeAccount(empty: () -> T, observe: (AccountSession) -> Flow<T>): Flow<Load<T>> = channelFlow {
        var previous: AccountSession? = null
        tokenStore.sessionFlow.storageAsLoad().collectLatest { sessionLoad ->
            when (sessionLoad) {
                Load.Pending -> send(Load.Pending)
                is Load.Failed -> send(sessionLoad)
                is Load.Ready -> {
                    val session = sessionLoad.value
                    if (previous != null && previous != session) send(Load.Ready(empty()))
                    previous = session
                    if (!session.authenticated) {
                        send(Load.Ready(empty()))
                    } else {
                        flow { emitAll(observe(session)) }.storageAsLoad(keepAliveOnFailure = true)
                            .closeOnStrayCancellation(this).collect { send(it) }
                    }
                }
            }
        }
    }.flowOn(computationDispatcher)

    /**
     * 观察当前账号的收藏，**只订阅被收藏引用到的条目**。
     *
     * Room 按**整张表**失效：别处往 `subjects` / `user_collections` 写任何一行（探索页一个货架落库、首页刷新的条目摘要），
     * 这里的查询都会重跑一遍。行没变的那几拍在映射前就丢掉（`distinctUntilChanged`），与收藏无关的写入因而不会往下游
     * （首页、进度页、放送表、统计）再推一份同样的收藏表。映射（每部作品解几段 JSON）落在 [computationDispatcher]。
     *
     * 本机读取失败是一拍 [Load.Failed]；账号切换先发空收藏清掉上一账号，同一账号重新订阅不伪造空拍。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCollections(): Flow<Load<List<UserCollectionBo>>> = observeAccount({ emptyList() }, ::collectionsFor)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun collectionsFor(session: AccountSession): Flow<List<UserCollectionBo>> =
        userCollectionDao.observeCurrent(session.userId!!).distinctUntilChanged().flatMapLatest { rows ->
            val ids = rows.map { it.subjectId }
            if (ids.isEmpty()) {
                flowOf(emptyList())
            } else {
                subjectDao.observeByIdsChunked(ids).distinctUntilChanged().map { subjects ->
                    val byId = subjects.associateBy { it.id }
                    rows.map { it.toBo(byId[it.subjectId]?.toBo()) }
                }
            }
        }

    /**
     * 读取当前进度快照。加载完成后与后续失效通知共用此入口，复用原观察流的账号隔离与映射。
     * 新查询的首拍读取已提交数据，不把旧订阅中排队的历史拍当作本轮写库完成后的状态。
     */
    private fun progressFor(session: AccountSession): Flow<Load<CollectionProgressSnapshot>> = combine(
        flow { emitAll(collectionsFor(session)) }.storageAsLoad().onStart { emit(Load.Pending) },
        flow { emitAll(episodeSnapshotFor(session)) }.storageAsLoad().onStart { emit(Load.Pending) },
    ) { collections, episodes ->
        when {
            collections is Load.Failed -> collections
            episodes is Load.Failed -> episodes
            collections is Load.Ready && episodes is Load.Ready ->
                Load.Ready(CollectionProgressSnapshot(collections.value, episodes.value))
            else -> Load.Pending
        }
    }.distinctUntilChanged { a, b -> a is Load.Failed && b is Load.Failed && a == b }

    /**
     * 两路输入属于同一账号。切换查询前先交付重置；失败会结束该账号的组合查询，重试时同时重建两路。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeProgress(): Flow<CollectionProgressUpdate> = channelFlow {
        var previous: AccountSession? = null
        tokenStore.sessionFlow.storageAsLoad().collectLatest { load ->
            when (load) {
                is Load.Failed -> send(CollectionProgressUpdate.Value(load))
                Load.Pending -> Unit
                is Load.Ready -> {
                    val session = load.value
                    if (previous != null && previous != session) send(CollectionProgressUpdate.Reset)
                    previous = session
                    if (!session.authenticated) {
                        send(CollectionProgressUpdate.Value(Load.Ready(CollectionProgressSnapshot(emptyList(), EpisodeWatchStateSnapshot()))))
                    } else {
                        progressFor(session).closeOnStrayCancellation(this).collect { send(CollectionProgressUpdate.Value(it)) }
                    }
                }
            }
        }
    }.flowOn(computationDispatcher)

    suspend fun progressSnapshot(): Load<CollectionProgressSnapshot> =
        observeProgress().first { it is CollectionProgressUpdate.Value && it.load !is Load.Pending }
            .let { (it as CollectionProgressUpdate.Value).load }

    /**
     * [session] 每部已收藏作品的状态（subjectId → 状态），海报右上角的状态角标只读它（经标记协调器叠上在途的标记，
     * 与同账号的本地回显配对）。与 [observeCollections] 同一张表、同一个口径，不联条目表：别处刷新条目详情不会让全 App 的海报跟着重算。
     * 读不出本机收藏表时这个账号不再出拍（海报留着上一拍），换号后照常：它挂在应用级的标记协调器上，异常漏出去会带崩进程。
     * 这里没有页面可承接提示，因此 Storage 失败只静默降级，不发用户通知。
     */
    internal fun observeCollectionTypes(session: AccountSession): Flow<Map<Int, CollectionType>> =
        if (!session.authenticated) flowOf(emptyMap()) else {
            flow { emitAll(userCollectionDao.observeCurrent(session.userId!!)) }.storageAsLoad().map { load ->
                load.mapLoad { rows ->
                    rows.mapNotNull { row -> CollectionType.from(row.type)?.takeIf { it != CollectionType.UNKNOWN }?.let { row.subjectId to it } }.toMap()
                }
            }.mapNotNull { it.valueOrNull() }.distinctUntilChanged()
        }

    /**
     * 逐集观看状态订阅：详情页与 EP 按钮的**唯一响应式真源**。
     *
     * 读 Room [EpisodeWatchStateDao.observeBySubject]，当网络刷新或本地写穿透落库时自动发射最新快照。
     * 本机读取失败是一拍 [Load.Failed]；账号切换先发空状态，同一账号重新订阅不伪造空拍。
     */
    fun observeEpisodeWatchStates(subjectId: Int): Flow<Load<Map<Int, EpisodeStatus>>> =
        observeAccount({ emptyMap() }) { session ->
            episodeWatchStateDao.observeBySubject(subjectId, session.userId!!).map { rows -> rows.toWatchStates() }
        }

    /**
     * 当前账号逐集状态与每部作品最后「看过」时刻（该作品看过的集里最大的 `updatedAt`，没有则 0）。
     * 与 [observeCollections] 同理：行没变的重查在分组前丢掉，分组落在 [computationDispatcher]。
     * 本机读取失败是一拍 [Load.Failed]；账号切换先发空快照，同一账号重新订阅不伪造空拍。
     */
    fun observeEpisodeWatchStateSnapshot(): Flow<Load<EpisodeWatchStateSnapshot>> =
        observeAccount({ EpisodeWatchStateSnapshot() }, ::episodeSnapshotFor)

    private fun episodeSnapshotFor(session: AccountSession): Flow<EpisodeWatchStateSnapshot> =
        episodeWatchStateDao.observeAll(session.userId!!).distinctUntilChanged().map { rows ->
                val bySubject = rows.groupBy { it.subjectId }
                EpisodeWatchStateSnapshot(
                    statesBySubject = bySubject.mapValues { (_, epRows) -> epRows.toWatchStates() },
                    latestWatchedAtBySubject = bySubject.mapValues { (_, epRows) ->
                        epRows.filter { it.status == EpisodeStatus.DONE.value }.maxOfOrNull { it.updatedAt } ?: 0L
                    },
                    watchedAtBySubject = bySubject.mapValues { (_, epRows) ->
                        epRows.filter { it.status == EpisodeStatus.DONE.value && it.updatedAt > 0 }
                            .associate { it.episodeId to it.updatedAt }
                    }.filterValues { it.isNotEmpty() },
                )
        }

    /** [session] 这个账号的本地逐集状态（真源的当前值，含乐观写入）；未登录为空。 */
    suspend fun cachedEpisodeWatchStates(subjectId: Int, session: AccountSession): Map<Int, EpisodeStatus> {
        val accountId = session.userId?.takeIf { session.authenticated } ?: return emptyMap()
        return episodeWatchStateDao.getBySubject(subjectId, accountId).toWatchStates()
    }

    /** [session] 这个账号的本地收藏行（真源的当前值，含乐观写入）；未登录或未收藏为 null。 */
    suspend fun cachedCollection(subjectId: Int, session: AccountSession): UserCollectionBo? {
        val accountId = session.userId?.takeIf { session.authenticated } ?: return null
        return userCollectionDao.getBySubjectId(subjectId, accountId)?.toBo(subjectDao.get(subjectId)?.toBo())
    }
}

/** 只订阅收藏引用到的条目：分块订阅后合并，规避 SQLite 宿主参数上限。 */
private fun SubjectDao.observeByIdsChunked(ids: List<Int>): Flow<List<SubjectEntity>> {
    val chunks = ids.distinct().chunked(SQLITE_IN_CHUNK)
    if (chunks.size == 1) return observeByIds(chunks.single())
    return combine(chunks.map { observeByIds(it) }) { it.toList().flatten() }
}

/**
 * 内层流漏出 `storageAsLoad` 的异常在这里收口，两种都不当成这一账号的结果：
 * - 本协程已被取消（快照拿到值后收工、换号取消上一账号）时，内层收尾抛出的异常随这次取消结束。上游正往下游交值时被取消，
 *   `catch` 算子见下游已抛出取消，不再把随后的本机读取失败收成 [Load.Failed]、而是原样抛出；放它出去，读到了值的快照会抛异常、
 *   长期观察会带着收集方一起结束。
 * - 本协程仍活着时，内层抛出的取消不是本协程被取消：关掉 [channel] 把它交给收集方，不当成这一账号正常结束。
 */
private fun <T> Flow<T>.closeOnStrayCancellation(channel: ProducerScope<*>): Flow<T> = catch { error ->
    if (error is Exception) currentCoroutineContext().ensureActive()
    if (error is CancellationException) channel.close(error) else throw error
}
