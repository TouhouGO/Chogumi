package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionDao
import io.github.touhougo.chogumi.core.data.db.collection.getBySubjectIdsChunked
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.episode.getBySubjectsChunked
import io.github.touhougo.chogumi.core.data.db.mapper.epochSecondsToMillis
import io.github.touhougo.chogumi.core.data.db.mapper.toDomain
import io.github.touhougo.chogumi.core.data.db.mapper.toEntity
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeDao
import io.github.touhougo.chogumi.core.data.db.subject.getBySubjectIdsChunked
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.model.v0.UserEpisodeCollectionDto
import kotlinx.coroutines.sync.Semaphore

/**
 * 本人逐集状态的读写：从服务端刷新、提交逐集变更、本地写穿（乐观回显与回滚），以及同步与首页长篇补全用的逐集记录补拉。
 * 服务端来源的落库不盖本地正写着、或读取发起后写过的条目（[LocalProgressHolds]）。
 */
class WatchStateStore(
    private val collectionApi: CollectionApi,
    private val userCollectionDao: UserCollectionDao,
    private val episodeWatchStateDao: EpisodeWatchStateDao,
    /** 章节表：补拉逐集记录时顺带存章节（首播日、标题从这里来）。 */
    private val episodeDao: EpisodeDao,
    private val tokenStore: TokenStore,
    private val transactions: TransactionRunner,
    private val clock: AppClock,
    private val holds: LocalProgressHolds,
) {
    /** 单集观看状态写入（特别篇逐集切换）。 */
    suspend fun updateEpisodeStatus(
        episodeId: Int,
        status: EpisodeStatus,
        expectedSession: AccountSession? = null,
    ): Outcome<Unit> = outcomeOf<Unit> {
        val session = expectedSession ?: tokenStore.requireSession()
        collectionApi.putEpisodeStatus(episodeId, status.value, tokenStore.requireTokenFor(session))
        tokenStore.requireCurrent(session)
    }

    suspend fun updateEpisodeBatch(
        subjectId: Int,
        episodeIds: List<Int>,
        status: EpisodeStatus,
        expectedSession: AccountSession? = null,
    ): Outcome<Unit> = outcomeOf<Unit> {
        val session = expectedSession ?: tokenStore.requireSession()
        collectionApi.patchEpisodeBatch(subjectId, episodeIds, status.value, tokenStore.requireTokenFor(session))
        tokenStore.requireCurrent(session)
    }

    /**
     * 逐集观看状态网络刷新：从服务端全量拉取并原子替换落库（suspend，返回 [Outcome]）。
     *
     * ## 为什么选 v0 逐集端点而不是 /p1/home 的 eps[]
     *
     * v0 `GET /v0/users/-/collections/{subjectId}/episodes` **返回全部章节并逐集给出 `type`**
     * （未标过的集是显式的 `type = 0`，2026-09-29 实测条目 876：24 集全返回，
     * 18 集 `None` + 6 集 `Done`）。`/p1/home` 的 `eps[]` 则两点都做不到：
     * ① 无记录时**整个不出现 `collection` 键**，读者必须自行把「键不存在」解释为未看；
     * ② 条目章节数 > 72 时被服务端截断成一个 73 元素窗口
     * （实测 975 返回 sort 29..101、123218 返回 sort 9..81，
     * 2026-09-29 实测）。
     * 逐集状态是详情页与首页共用的判定依据，**不能接受截断**，故取 v0。
     *
     * 分页取全后再原子替换（`replaceForSubject`）：中途失败保留原缓存，
     * 避免半份数据被判成「其余集都未看」。落库后由 [CollectionObserver.observeEpisodeWatchStates] 自动驱动界面。
     *
     * 返回服务端给的逐集状态（受保护、没有落库时也照样返回）。服务端一集都没有时（还没录入章节的条目）
     * 逐集表里不会留下这部的任何行，快照里也就永远没有它；调用方要知道「已返回、确实为空」只能看这个返回值。
     */
    suspend fun refreshEpisodeWatchStates(
        subjectId: Int,
        expectedSession: AccountSession? = null,
    ): Outcome<Map<Int, EpisodeStatus>> = outcomeOf {
        val session = expectedSession ?: tokenStore.requireSession()
        val start = holds.beginRead()
        val rows = fetchEpisodeRows(subjectId, session)
        tokenStore.commitForSession(session) {
            holds.applyRead(start) { protected ->
                if (subjectId !in protected) episodeWatchStateDao.replaceForSubject(subjectId, session.userId!!, rows)
            }
        }
        rows.toWatchStates()
    }

    /**
     * 只读取服务端逐集状态，**不落库**：进度提交用它做「服务端现状」来算差量，
     * 不能先把本地乐观状态覆盖掉（那会让界面在提交途中闪回旧进度）。
     */
    suspend fun fetchServerEpisodeWatchStates(
        subjectId: Int,
        expectedSession: AccountSession? = null,
    ): Outcome<Map<Int, EpisodeStatus>> = outcomeOf {
        fetchEpisodeRows(subjectId, expectedSession ?: tokenStore.requireSession()).toWatchStates()
    }

    /** v0 逐集端点分页取全（见 [refreshEpisodeWatchStates] 的取舍说明）。 */
    private suspend fun fetchEpisodeRows(subjectId: Int, session: AccountSession): List<EpisodeWatchStateEntity> {
        val accountId = session.userId!!
        val all = fetchUserEpisodes(subjectId, session, tokenStore.tokenFor(session))
        tokenStore.requireCurrent(session)
        return all.records.map { it.toWatchStateEntity(subjectId, accountId) }
    }

    /** 用户逐集端点分页取全：逐集状态与章节都从这一处取。 */
    private suspend fun fetchUserEpisodes(
        subjectId: Int, session: AccountSession, token: String?, permits: Semaphore? = null,
    ): UserEpisodes {
        var total = -1
        val records = fetchAllPages(EPISODE_PAGE_SIZE, permits = permits) { offset, limit ->
            tokenStore.requireCurrent(session)
            val page = collectionApi.getUserEpisodeCollections(subjectId, limit = limit, offset = offset, accessToken = token)
            if (offset == 0) total = page.total
            page.toSlice()
        }
        return UserEpisodes(records, total == 0 && records.isEmpty())
    }

    /**
     * 拉一部的逐集记录（同步与首页长篇补全共用）：用户逐集端点逐集带着完整的章节（见 [refreshEpisodeWatchStates]
     * 的取舍说明），[writeChapters] 为真时落库会把同一份响应里的章节也存下（首播日、标题从这里来）。
     */
    internal suspend fun fetchWatchHistory(
        subjectId: Int, session: AccountSession, writeChapters: Boolean, permits: Semaphore? = null,
    ): WatchHistory {
        val start = holds.beginRead()
        val all = fetchUserEpisodes(subjectId, session, tokenStore.tokenFor(session), permits)
        tokenStore.requireCurrent(session)
        val episodes = all.records.map { it.episode.toDomain().copy(subjectId = subjectId) }.sortedBy { it.sort }
        return WatchHistory(subjectId, all.records, episodes, all.confirmedEmpty, writeChapters, start)
    }

    /**
     * 若干部取回的逐集记录一个事务落库，每部的逐集状态与章节（要存时）一起写。
     * 受保护的作品（[LocalProgressHolds]：正写着，或这一批最早的一次读取发起后写过）不写逐集状态，章节照写。
     */
    internal suspend fun writeWatchHistories(histories: List<WatchHistory>, session: AccountSession) {
        val accountId = session.userId!!
        val earliest = histories.minByOrNull { it.start.seq }?.start ?: return
        tokenStore.commitForSession(session) {
            holds.applyRead(earliest) { protected ->
                transactions.inTransaction {
                    val now = clock.nowMillis()
                    for (history in histories) {
                        val subjectId = history.subjectId
                        if (subjectId !in protected) {
                            episodeWatchStateDao.replaceForSubject(subjectId, accountId, history.records.map { it.toWatchStateEntity(subjectId, accountId) })
                        }
                        if (history.writeChapters) episodeDao.replaceAll(subjectId, history.episodes.map { it.toEntity(updatedAt = now) }, fetchedAt = now)
                    }
                }
            }
        }
    }

    /**
     * [subjectIds] 里本地逐集记录还与服务端对得上的作品（[watchHistoryCurrent]），只读本地；未登录或没有收藏行的不算。
     * 首页长篇补全据此跳过不必重拉的作品。
     */
    suspend fun currentWatchHistories(subjectIds: List<Int>, session: AccountSession): Set<Int> =
        watchHistoryLedger(subjectIds, session).current

    /** [subjectIds] 里本地已有章节的（[chaptered]）和逐集记录还与服务端对得上的（[current]）。 */
    internal class WatchHistoryLedger(val chaptered: Set<Int>, val current: Set<Int>, val withWatchedRecords: Set<Int>)

    internal suspend fun watchHistoryLedger(subjectIds: List<Int>, session: AccountSession): WatchHistoryLedger {
        val accountId = session.userId?.takeIf { session.authenticated } ?: return WatchHistoryLedger(emptySet(), emptySet(), emptySet())
        val epStatus = userCollectionDao.getBySubjectIdsChunked(subjectIds, accountId).associate { it.subjectId to it.epStatus }
        val chaptered = episodeDao.getBySubjectIdsChunked(subjectIds).mapTo(HashSet()) { it.subjectId }
        val watchedRows = episodeWatchStateDao.getBySubjectsChunked(subjectIds, accountId)
            .groupBy { it.subjectId }.mapValues { (_, rows) -> EpisodeTracking.watchedRecordCount(rows.toWatchStates().values) }
        val current = subjectIds.filterTo(HashSet()) { id ->
            epStatus[id]?.let { watchHistoryCurrent(id in chaptered, watchedRows[id] ?: 0, it) } == true
        }
        return WatchHistoryLedger(chaptered, current, watchedRows.filterValues { it > 0 }.keys)
    }

    /**
     * 一部的章节与逐集状态一路取全、一个事务落库（首页长篇补全用）：用户逐集端点本身带着全部章节，
     * 不必再另拉一遍 `/v0/episodes`。返回按 sort 排好的章节及本次取数确认空章节的结果。
     * 账号按 [expectedSession]（没给就是现在的会话）。
     */
    suspend fun refreshEpisodeHistory(
        subjectId: Int, expectedSession: AccountSession? = null, permits: Semaphore? = null,
    ): Outcome<EpisodeHistory> = outcomeOf {
        val session = expectedSession ?: tokenStore.requireSession()
        val history = fetchWatchHistory(subjectId, session, writeChapters = true, permits = permits)
        writeWatchHistories(listOf(history), session)
        EpisodeHistory(history.episodes, history.confirmedEmpty)
    }

    /**
     * 逐集状态写穿透：把**已经算好的目标状态** [target] 落表，供界面立刻回显。
     *
     * 目标由调用方（[ProgressOrchestrator.applyWatchStates]）用
     * [EpisodeTracking.statesAfterWatchedUpTo] 等唯一判定点算出，本地**不重算**——
     * 同一判定写两处必然分叉，判定只活在一个纯函数里。
     *
     * **写穿严禁用 replaceForSubject**（那会删掉 SP/OP 等非正片行）：这里严格只调用
     * [EpisodeWatchStateDao.upsertAll]。**只 upsert [target] 里实际变化的行**，
     * 非正片与未列出的集一律不碰。
     *
     * **需要 [episodes] 的原因**：逐集表按 `episodeId` 存，只有章节列表能把目标状态
     * 与集 id、以及「哪些集算变化」对应起来。
     *
     * 乐观写入、成功写穿与失败回滚共用；逐集变更和完整状态派生的收藏计数在同一次会话事务中提交。
     *
     * 只写 [session] 这个账号；它已不是当前会话（登出、换号）时什么也不写，返回 [Failure.SessionChanged]。
     */
    suspend fun syncEpisodeWatchStates(
        subjectId: Int,
        episodes: List<EpisodeBo>,
        target: Map<Int, EpisodeStatus>,
        session: AccountSession,
    ): Outcome<Unit> = syncLocalStates(
        subjectId,
        EpisodeTracking.mainEpisodes(episodes).associate { it.id to (target[it.id] ?: EpisodeStatus.NONE) },
        session,
    )

    /** 逐集行与已看计数只在这一次会话事务中派生和提交，保留不在 [target] 的记录。 */
    private suspend fun syncLocalStates(subjectId: Int, target: Map<Int, EpisodeStatus>, session: AccountSession): Outcome<Unit> = outcomeOf {
        val now = clock.nowMillis()
        tokenStore.commitForSession(session) {
            holds.markWritten(subjectId)
            transactions.inTransaction {
                val accountId = session.userId!!
                val existing = episodeWatchStateDao.getBySubject(subjectId, accountId).associateBy { it.episodeId }
                val rows = target.mapNotNull { (episodeId, want) ->
                    if (existing[episodeId]?.status == want.value) null else {
                        EpisodeWatchStateEntity(episodeId, subjectId, accountId, want.value, now)
                    }
                }
                if (rows.isNotEmpty()) episodeWatchStateDao.upsertAll(rows)
                val states = existing.values.toList().toWatchStates() + target
                val count = EpisodeTracking.watchedRecordCount(states.values)
                userCollectionDao.getBySubjectId(subjectId, accountId)?.takeIf { it.epStatus != count }?.let { collection ->
                    userCollectionDao.upsert(collection.copy(epStatus = count, updatedAt = now))
                }
            }
        }
    }

    /**
     * 单集状态写进本地真源（不发请求）：特别篇逐集切换的乐观写入与失败回滚共用。
     * 只修改这一集，并从完整逐集状态派生收藏已看记录数；特别篇计入已看记录，不影响正片锚点。
     */
    suspend fun syncSingleEpisodeWatchState(
        subjectId: Int,
        episodeId: Int,
        status: EpisodeStatus,
        session: AccountSession,
    ): Outcome<Unit> = syncLocalStates(subjectId, mapOf(episodeId to status), session)

    /** 条目级进度写穿本地（不发请求）：[epStatus] / [volStatus] 为 null 表示不改。会话规则同 [syncEpisodeWatchStates]。 */
    suspend fun syncEpisodeProgress(
        subjectId: Int,
        session: AccountSession,
        epStatus: Int? = null,
        volStatus: Int? = null,
    ): Outcome<Unit> = outcomeOf<Unit> {
        tokenStore.commitForSession(session) {
            holds.markWritten(subjectId)
            userCollectionDao.getBySubjectId(subjectId, session.userId!!)?.let {
                userCollectionDao.upsert(it.copy(
                    epStatus = epStatus ?: it.epStatus,
                    volStatus = volStatus ?: it.volStatus,
                    updatedAt = clock.nowMillis(),
                ))
            }
        }
    }
}

/** 取回的一部逐集记录：服务端原样的记录、按 sort 排好的章节、要不要把章节也存下、请求发出的时刻。 */
internal class WatchHistory(
    val subjectId: Int,
    val records: List<UserEpisodeCollectionDto>,
    val episodes: List<EpisodeBo>,
    val confirmedEmpty: Boolean,
    val writeChapters: Boolean,
    val start: ReadStart,
)

private class UserEpisodes(val records: List<UserEpisodeCollectionDto>, val confirmedEmpty: Boolean)

/** 完整逐集取数的章节；只有服务端总数为零且结果为空才确认没有章节。 */
data class EpisodeHistory(val episodes: List<EpisodeBo>, val confirmedEmpty: Boolean)

/**
 * 一部作品的本地逐集记录是否还与服务端对得上（同步的补拉与首页长篇补全共用的唯一判定）：
 * 本地有这部的章节，且「看过」的行数等于收藏的 `epStatus`——服务端的看过记录条数，SP 也算
 * （2026-10-16 实测 6 部作品，含 1191 条记录的 975，全部相等）。在别处标过集、还没拉过，就对不上、要重拉。
 */
internal fun watchHistoryCurrent(hasChapters: Boolean, watchedRows: Int, epStatus: Int): Boolean =
    hasChapters && watchedRows == epStatus

/** 逐集状态分页跨度；v0 该端点 `limit` 上限 1000，取 100 与全项目分页习惯一致。 */
private const val EPISODE_PAGE_SIZE = 100

/** v0 逐集记录 → 逐集表的一行（服务端 `updated_at` 是秒）。 */
private fun UserEpisodeCollectionDto.toWatchStateEntity(subjectId: Int, accountId: Int) = EpisodeWatchStateEntity(
    episodeId = episode.id,
    subjectId = subjectId,
    accountId = accountId,
    status = type,
    updatedAt = updatedAt.epochSecondsToMillis(),
)

/** 逐集实体 → `episodeId -> EpisodeStatus` 映射；未知状态值（服务端新增）按未看处理。 */
internal fun List<EpisodeWatchStateEntity>.toWatchStates(): Map<Int, EpisodeStatus> =
    associate { it.episodeId to (EpisodeStatus.from(it.status) ?: EpisodeStatus.NONE) }
