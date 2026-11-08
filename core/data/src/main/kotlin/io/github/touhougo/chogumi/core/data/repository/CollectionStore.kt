package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionDao
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.collection.getBySubjectIdsChunked
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.mapper.epochSecondsToMillis
import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toDomain
import io.github.touhougo.chogumi.core.data.db.mapper.toEntity
import io.github.touhougo.chogumi.core.data.db.mapper.toJson
import io.github.touhougo.chogumi.core.data.db.mapper.toSnapshot
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.db.subject.getByIdsChunked
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.data.util.LruMap
import io.github.touhougo.chogumi.core.data.util.cachedNetworkCall
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.mayHaveReachedServer
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CollectionModify
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.CollectionNotCollectedException
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectInterestDto
import io.github.touhougo.chogumi.core.network.model.v0.CollectionModifyDto
import io.github.touhougo.chogumi.core.network.model.v0.UserCollectionDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow


/**
 * 本人收藏记录的读写：某类收藏的列表、单部的收藏、改收藏，以及首页、放送表顺带带回的收藏合并。
 * 只有完整成功分页才能替换一个分区；服务端来源的落库不盖本地正写着、或读取发起后写过的条目（[LocalProgressHolds]）。
 *
 * [transactions] 把「一次写两张表」的落库包进一个数据库事务（生产是 Room 的 `withTransaction`）；见 [TransactionRunner]。
 */
class CollectionStore(
    private val collectionApi: CollectionApi,
    private val subjectDao: SubjectDao,
    private val userCollectionDao: UserCollectionDao,
    private val episodeWatchStateDao: EpisodeWatchStateDao,
    private val tokenStore: TokenStore,
    private val syncPreferenceStore: SyncPreferenceStore,
    private val transactions: TransactionRunner,
    private val clock: AppClock,
    /** 列表映射的调度器（生产是 computationDispatcher）：上千部收藏的映射不占收集方（主线程）。 */
    private val computationDispatcher: CoroutineDispatcher,
    private val holds: LocalProgressHolds,
    private val webSessions: WebSessionRepository,
) {
    /** 某个账号某类收藏（类型 × 状态）最近一次完整取回的时刻；只留最近 [USER_COLLECTIONS_FETCH_MEMO] 组。 */
    private data class CollectionsKey(val accountId: Int, val subjectType: SubjectType, val type: CollectionType?)
    private val collectionsFetchedAt = LruMap<CollectionsKey, Long>(USER_COLLECTIONS_FETCH_MEMO)

    /**
     * 本人某类收藏：先出本地的，过了新鲜期 [CacheTtl.USER_COLLECTIONS] 再取全部页覆盖（[cachedNetworkCall]）；
     * [refresh]（下拉刷新）直接取。缓存的时刻是这一组上次完整取回与上次全量同步（[SyncPreferenceStore.lastSyncedAt]）里较晚的那个；
     * 本地写入（改收藏、标进度）直接写进同一张表，不必等过期。
     */
    fun getUserCollections(subjectType: SubjectType, type: CollectionType?, refresh: Boolean = false): Flow<Load<List<UserCollectionBo>>> = flow {
        val session = tokenStore.requireSession()
        val accountId = session.userId!!
        val key = CollectionsKey(accountId, subjectType, type)
        val cachedAt = maxOf(
            synchronized(collectionsFetchedAt) { collectionsFetchedAt[key] } ?: 0L,
            syncPreferenceStore.lastSyncedAt(accountId) ?: 0L,
        )
        emitAll(cachedNetworkCall(
            readCache = { userCollectionDao.get(subjectType.value, type?.value, accountId).ifEmpty { null } },
            mapCache = { it.toBoList(subjectDao) },
            // 取回、落库与回读是一步：落库要和取回固定在同一个会话里，回读的才是带条目快照的完整结果
            fetchNetwork = {
                val start = holds.beginRead()
                val all = collectionApi.fetchAllUserCollections(tokenStore, session.username!!, subjectType, type, session)
                applySnapshot(session, subjectType, type, all, start)
                // 回读在锁外：读的是这个账号自己的行，不需要占着会话锁；用一次批量查询，不逐条 get。
                userCollectionDao.get(subjectType.value, type?.value, accountId).toBoList(subjectDao)
            },
            writeCache = { synchronized(collectionsFetchedAt) { collectionsFetchedAt[key] = clock.nowMillis() } },
            refresh = refresh,
            ttlMillis = CacheTtl.USER_COLLECTIONS,
            cachedAt = { cachedAt },
            clock = clock,
            workContext = computationDispatcher,
        ))
    }.failuresAsLoad()

    /**
     * 成功全量快照的唯一应用入口：保护判断（[LocalProgressHolds.applyRead]，[start] 是取第一页之前的时刻）、
     * 保留行与分区替换在同一会话和同一把锁内。
     */
    internal suspend fun applySnapshot(
        session: AccountSession,
        subjectType: SubjectType,
        type: CollectionType?,
        all: List<UserCollectionDto>,
        start: ReadStart,
    ) {
        tokenStore.commitForSession(session) {
            holds.applyRead(start) { protected ->
                transactions.inTransaction {
                    val accountId = session.userId!!
                    val now = clock.nowMillis()
                    subjectDao.mergeAll(all.mapNotNull { it.subject?.toSnapshot(now) })
                    val rows = all.filterNot { it.subjectId in protected }.map { it.toDomain().toEntity(accountId) }
                    // 受保护的行原样保留；只交本分区的，分区外的行不归这次快照管
                    val protectedRows = userCollectionDao.getBySubjectIdsChunked(protected.toList(), accountId)
                        .filter { it.subjectType == subjectType.value && (type == null || it.type == type.value) }
                    userCollectionDao.replaceSnapshot(accountId, subjectType.value, type?.value, rows + protectedRows)
                }
            }
        }
    }

    /**
     * 当前账号对一部作品的收藏：有完整缓存先回显，再取服务端的。就绪的值为 null = 确认还没收藏
     * （v0 单条收藏的 404：「用户不存在或者条目未收藏」，问的是自己，只能是没收藏）；其余失败照常是 [Load.Failed]。
     */
    fun getCollection(subjectId: Int): Flow<Load<UserCollectionBo?>> = flow<Load<UserCollectionBo?>> {
        val session = tokenStore.requireSession()
        val cached = userCollectionDao.getBySubjectId(subjectId, session.userId!!)
        if (cached?.detailsLoaded == true) emit(Load.Ready(cached.toBo(subjectDao.get(subjectId)?.toBo()), fromCache = true))
        else emit(Load.Pending)
        val start = holds.beginRead()
        val fetched = outcomeOf {
            collectionApi.getCollection(session.username!!, subjectId, tokenStore.tokenFor(session))
        }
        val value = tokenStore.commitForSession(session) {
            // 失败也属于发起读取的会话；404 是“未收藏”的快照，同样不能盖掉期间的本地写。
            val failure = fetched.failureOrNull()
            if (failure != null && (failure as? Failure.Http)?.status != 404) throw FailureException(failure)
            holds.applyRead(start) { protected ->
                // 本地正写着或刚写过：服务端回的是写之前的样子，界面上留本地这一行
                if (subjectId in protected) userCollectionDao.getBySubjectId(subjectId, session.userId)?.let { it.toBo(subjectDao.get(subjectId)?.toBo()) }
                else when (fetched) {
                    is Outcome.Success -> save(fetched.value, session.userId)
                    is Outcome.Failed -> null
                }
            }
        }
        emit(Load.Ready(value))
    }.failuresAsLoad()

    private suspend fun save(dto: UserCollectionDto, accountId: Int): UserCollectionBo {
        dto.subject?.let { subjectDao.merge(it.toSnapshot(clock.nowMillis())) }
        val row = dto.toDomain().toEntity(accountId)
        userCollectionDao.upsert(row)
        return row.toBo(subjectDao.get(dto.subjectId)?.toBo())
    }

    /** 网页移除收藏，回读确认 404 后才删除本账号的本地记录；其余失败保留本地收藏。 */
    suspend fun removeCollection(subjectId: Int, expectedSession: AccountSession): Outcome<Unit> = outcomeOf {
        webSessions.perform(expectedSession) { formhash -> BgmWeb.subjectUncollect(subjectId, formhash) }
        val checked = outcomeOf {
            collectionApi.getCollection(expectedSession.username!!, subjectId, tokenStore.tokenFor(expectedSession))
        }
        tokenStore.requireCurrent(expectedSession)
        val failure = checked.failureOrNull()
            ?: throw FailureException(Failure.Rejected(RejectReason.SUBJECT_UNCOLLECT_UNAVAILABLE))
        if (failure != Failure.Http(404)) throw FailureException(failure)
        tokenStore.commitForSession(expectedSession) {
            holds.markWritten(subjectId)
            transactions.inTransaction {
                userCollectionDao.deleteBySubjectIds(listOf(subjectId), expectedSession.userId!!)
                episodeWatchStateDao.deleteBySubject(subjectId, expectedSession.userId)
            }
        }
    }

    suspend fun updateCollection(
        subjectId: Int,
        modify: CollectionModify,
        expectedSession: AccountSession? = null,
        localPublish: LocalPublish = LocalPublish.Immediate,
        onReconciled: () -> Unit = {},
    ): Outcome<Unit> = outcomeOf<Unit> {
        // UNKNOWN 只是读到不认识的值时的占位，不能写回服务端
        require(modify.type == null || modify.type in CollectionType.choices) { "无效收藏状态" }
        require(modify.rate == null || modify.rate in 0..10) { "评分必须为 0 到 10" }
        require((modify.epStatus ?: 0) >= 0 && (modify.volStatus ?: 0) >= 0) { "进度不能为负数" }
        val session = expectedSession ?: tokenStore.requireSession()
        val cached = userCollectionDao.getBySubjectId(subjectId, session.userId!!)
        val subjectType = subjectDao.get(subjectId)?.type ?: cached?.subjectType
        val countsInRecord = subjectType?.let(SubjectType::from)?.let(EpisodeTracking::tracksCounts) == true

        // 服务端 OpenAPI 规范明确限制：PATCH /v0/users/-/collections/{subject_id}
        // "由于直接修改剧集条目的完成度可能会引起意料之外效果，只能用于修改书籍类条目的完成度。"
        // 服务端实测 400 响应体原文：{"title":"Bad Request","details":{"path":"/v0/users/-/collections/975","method":"PATCH"},"request_id":"...","description":"can't set 'vol_status' or 'ep_status' on non-book subject"}
        // 因此非书籍条目（动画/音乐/游戏等）在单个条目收藏 PATCH 请求体中严禁携带 ep_status 与 vol_status。
        val dto = CollectionModifyDto(
            type = modify.type?.value,
            rate = modify.rate,
            epStatus = if (countsInRecord) modify.epStatus else null,
            volStatus = if (countsInRecord) modify.volStatus else null,
            comment = modify.comment,
            `private` = modify.`private`,
            tags = modify.tags,
        )
        // 剥离后没有要改的字段（如面板只改了动画进度，进度另走逐集接口）：不发空请求——
        // 空 PATCH 也会刷新服务端的收藏更新时间。
        if (dto == CollectionModifyDto()) return@outcomeOf
        val written = outcomeOf {
            try {
                collectionApi.patchCollection(subjectId, dto, tokenStore.requireTokenFor(session))
            } catch (e: CollectionNotCollectedException) {
                // PATCH 404 = 条目未收藏（spec v0.yaml：用户不存在或者条目未收藏）。用户这次选了收藏状态才回退 POST 创建；
                // 只改卷 / 话进度、评分等（type 为 null）时不替用户建收藏，按未收藏失败（toFailure → Http 404）。
                // POST 与 PATCH 共用 UserSubjectCollectionModifyPayload（ep_status 剥离规则同上，dto 已按 isBook 剥离），
                // POST 语义是「如果不存在则创建」。400 是参数错误，重试无用，不回退。
                if (modify.type == null) throw e
                collectionApi.postCollection(subjectId, dto, tokenStore.requireTokenFor(session))
            }
        }
        if (written is Outcome.Failed) {
            tokenStore.requireCurrent(session)
            if (written.failure.mayHaveReachedServer()) {
                val read = outcomeOf {
                    collectionApi.getCollection(session.username!!, subjectId, tokenStore.tokenFor(session))
                }
                tokenStore.requireCurrent(session)
                if (read is Outcome.Success) localPublish.publish {
                    tokenStore.commitForSession(session) {
                        holds.markWritten(subjectId)
                        save(read.value, session.userId)
                        onReconciled()
                    }
                }
            }
            throw FailureException(written.failure)
        }
        tokenStore.requireCurrent(session)
        if (cached?.detailsLoaded == true) localPublish.publish {
            tokenStore.commitForSession(session) {
                holds.markWritten(subjectId)
                val latest = userCollectionDao.getBySubjectId(subjectId, session.userId) ?: cached
                userCollectionDao.upsert(latest.copy(
                    type = modify.type?.value ?: latest.type, rate = modify.rate ?: latest.rate,
                    epStatus = modify.epStatus ?: latest.epStatus, volStatus = modify.volStatus ?: latest.volStatus,
                    comment = modify.comment ?: latest.comment, isPrivate = modify.`private` ?: latest.isPrivate,
                    tagsJson = modify.tags?.toJson() ?: latest.tagsJson,
                    updatedAt = clock.nowMillis(),
                ))
            }
        } else {
            val refreshed = collectionApi.getCollection(session.username!!, subjectId, tokenStore.tokenFor(session))
            localPublish.publish {
                tokenStore.commitForSession(session) {
                    holds.markWritten(subjectId)
                    save(refreshed, session.userId)
                }
            }
        }
    }

    /**
     * 首页 `/p1/home` 一拍里本账号的那部分：收藏合并与逐集灌表。调用方（[HomeRepository]）持着 [session] 的
     * [TokenStore.commitForSession]，并把这里与条目快照、首页进度放进同一个事务：半途取消或换号不留半套，
     * 订阅这几张表的流也只因这一拍各重算一次。
     *
     * - 收藏：`interest` 是**完整**收藏（含进度与私密性），直接写成完整收藏行——收藏表因此是首页书籍进度与「在看」状态的唯一来源。
     *   条目类型按 id 一次批量取回（条目快照须先于此写入）；没变的行不写。
     * - 逐集：只 upsert 服务端给出的那些集（不在 `eps[]` 里的集不碰）。
     * - 两样都跳过本地正写着、或 [start]（请求 `/p1/home` 之前）之后写过的条目（[LocalProgressHolds.applyRead]）。
     */
    internal suspend fun writeHomeCollections(
        session: AccountSession,
        interests: List<Pair<Int, SubjectInterestDto>>,
        watchStates: List<EpisodeWatchStateEntity>,
        start: ReadStart,
    ) {
        val accountId = session.userId?.takeIf { session.authenticated } ?: return
        val types = subjectDao.getByIdsChunked(interests.map { it.first }).associate { it.id to it.type }
        holds.applyRead(start) { protected ->
            val rows = interests.mapNotNull { (subjectId, interest) ->
                if (subjectId in protected) return@mapNotNull null
                val subjectType = types[subjectId] ?: return@mapNotNull null
                UserCollectionEntity(
                    subjectId = subjectId, subjectType = subjectType, type = interest.type,
                    rate = interest.rate, tagsJson = interest.tags.toJson(),
                    epStatus = interest.epStatus, volStatus = interest.volStatus,
                    comment = interest.comment, isPrivate = interest.`private`,
                    updatedAt = interest.updatedAt.epochSecondsToMillis(), accountId = accountId, detailsLoaded = true,
                )
            }
            userCollectionDao.upsertChanged(rows)
            episodeWatchStateDao.upsertChanged(watchStates.filterNot { it.subjectId in protected })
        }
    }

    /** 摘要 interest 不含进度/私密性，不能覆盖已加载的完整收藏。 */
    suspend fun mergeP1Interests(subjects: List<P1SlimSubjectDto>, session: AccountSession) {
        if (!session.authenticated) return
        tokenStore.commitForSession(session) {
            // 已有行按 id 一次批量读、整批一次写入，同 [writeHomeCollections]。读留在锁里：锁外读到的「没有完整收藏」
            // 可能在进锁前被详情页写成完整的，再拿摘要覆盖就丢了进度与私密性。
            val withInterest = subjects.filter { it.interest != null }
            val detailed = userCollectionDao.getBySubjectIdsChunked(withInterest.map { it.id }, session.userId!!)
                .filter { it.detailsLoaded }.mapTo(HashSet()) { it.subjectId }
            val rows = withInterest.mapNotNull { subject ->
                val interest = subject.interest ?: return@mapNotNull null
                if (subject.id in detailed) return@mapNotNull null
                UserCollectionEntity(
                    subjectId = subject.id, subjectType = subject.type, type = interest.type,
                    rate = interest.rate, tagsJson = interest.tags.toJson(),
                    comment = interest.comment, updatedAt = interest.updatedAt.epochSecondsToMillis(),
                    accountId = session.userId, detailsLoaded = false,
                )
            }
            userCollectionDao.upsertChanged(rows)
        }
    }
}

/** 记几组收藏的取回时刻：一个账号五种类型 × 六种状态（含「全部」）是 30 组，留够两个账号来回切（设计决定）。 */
private const val USER_COLLECTIONS_FETCH_MEMO = 64

/** 收藏行 → 领域对象，条目由**分块批量查询**的结果补齐，不逐条 get。 */
private suspend fun List<UserCollectionEntity>.toBoList(subjectDao: SubjectDao): List<UserCollectionBo> {
    if (isEmpty()) return emptyList()
    val byId = subjectDao.getByIdsChunked(map { it.subjectId }).associateBy { it.id }
    return map { it.toBo(byId[it.subjectId]?.toBo()) }
}
