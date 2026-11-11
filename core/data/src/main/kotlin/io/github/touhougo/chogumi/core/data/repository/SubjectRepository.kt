package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.SQLITE_IN_CHUNK
import io.github.touhougo.chogumi.core.data.db.mapper.epochSecondsToMillis
import io.github.touhougo.chogumi.core.data.db.mapper.ratingSplitOf
import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toDomain
import io.github.touhougo.chogumi.core.data.db.mapper.toEntity
import io.github.touhougo.chogumi.core.data.db.mapper.toSnapshot
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeDao
import io.github.touhougo.chogumi.core.data.db.subject.LINEUP_SECTIONS
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSection
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionEntity
import io.github.touhougo.chogumi.core.data.db.subject.getBySubjectIdsChunked
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.data.util.LruMap
import io.github.touhougo.chogumi.core.data.util.cachedNetworkCall
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.RelatedPersonBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectLineup
import io.github.touhougo.chogumi.core.model.SubjectRatingSplit
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectRelationKind
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.relationKindOf
import io.github.touhougo.chogumi.core.model.subjectLineupOf
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.WebPageApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 条目与章节数据仓库（先出缓存、再用网络结果水合） */
class SubjectRepository(
    private val subjectApi: SubjectApi,
    private val nextApi: NextApi,
    private val webPageApi: WebPageApi,
    private val subjectDao: SubjectDao,
    private val episodeDao: EpisodeDao,
    private val subjectSectionDao: SubjectSectionDao,
    private val nsfwFilter: NsfwFilter,
    private val tokenStore: TokenStore,
    private val clock: AppClock,
    /**
     * 详情页的读缓存、取网络、落库与映射（资料项、角色、制作人员、关联条目的 JSON）都在这里跑：
     * 打开详情页时这些正好撞上转场动画，不占收集方（主线程）。仍然先出缓存，点进去照样秒开。
     */
    private val computationDispatcher: CoroutineDispatcher,
) : SubjectLineupSource {

    suspend fun getSubject(id: Int, refresh: Boolean = false): Flow<Load<SubjectBo>> = cachedNetworkCall(
        readCache = { subjectDao.get(id) },
        mapCache = { it.toBo() },
        fetchNetwork = {
            val snapshot = tokenStore.withSessionToken { _, token ->
                subjectApi.getSubject(id, accessToken = token).toSnapshot(updatedAt = clock.nowMillis())
            }
            subjectDao.merge(snapshot).toBo()
        },
        writeCache = { /* 网络拍已经在统一合并事务中落库。 */ },
        refresh = refresh,
        ttlMillis = CacheTtl.SUBJECT_DETAIL,
        cachedAt = { it.updatedAt },
        clock = clock,
        workContext = computationDispatcher,
    )

    suspend fun getEpisodes(subjectId: Int, refresh: Boolean = false): Flow<Load<List<EpisodeBo>>> = cachedNetworkCall(
        readCache = { cachedEpisodeRows(subjectId)?.let { Fetched(it, episodeDao.fetchedAt(subjectId) ?: 0L) } },
        mapCache = { it.content.map { entity -> entity.toBo() } },
        // 显式按 sort 排序：不依赖 API 默认序（缓存拍 DAO 有 order by，网络拍也得有）
        fetchNetwork = {
            tokenStore.withSessionToken { _, token ->
                fetchAllPages(V0_EPISODE_PAGE_LIMIT) { offset, limit ->
                    subjectApi.getEpisodes(subjectId = subjectId, limit = limit, offset = offset, accessToken = token).toSlice()
                }.map { it.toDomain() }.sortedBy { it.sort }
            }
        },
        writeCache = { episodes ->
            val now = clock.nowMillis()
            episodeDao.replaceAll(subjectId, episodes.map { it.toEntity(updatedAt = now) }, fetchedAt = now)
        },
        refresh = refresh,
        ttlMillis = CacheTtl.EPISODES,
        cachedAt = { it.fetchedAt },
        clock = clock,
        workContext = computationDispatcher,
    )

    /** 本地有没有这部的章节（不论新旧，与 [getEpisodes] 的缓存拍同一个判定）。 */
    suspend fun hasCachedEpisodes(subjectId: Int): Boolean = cachedEpisodeRows(subjectId) != null

    private suspend fun cachedEpisodeRows(subjectId: Int) = episodeDao.getBySubject(subjectId).ifEmpty { null }

    /**
     * 本地已存的章节，只读、不取网络：[subjectIds] 里本地有章节的那几部，各自按 sort 排好；章节表一有写入就重算一拍。
     * 给只拿本地数据算进度的页面用（每日放送「我在追」）：本地没有章节的部不在结果里，由调用方按收藏记录兜底。
     */
    fun observeCachedEpisodes(subjectIds: Set<Int>): Flow<Load<Map<Int, List<EpisodeBo>>>> =
        episodeDao.observeLastWrite().map {
            if (subjectIds.isEmpty()) emptyMap()
            else episodeDao.getBySubjectIdsChunked(subjectIds.toList()).groupBy({ it.subjectId }, { it.toBo() })
        }.storageAsLoad().flowOn(computationDispatcher)

    /**
     * 关联条目（p1）：按页取全，每页上限 100；缓存规则见 [sectionCall]。
     *
     * 单行本（关系 ID 1003）与非单行本在服务端是**互斥的两路**（见 [NextApi.getSubjectRelations]），
     * 所以这里并行发两路再合并，缺一不可。合并后按条目 id 去重：
     * 服务端查询未过滤 `rlt_vice_versa`，而唯一键是 `(rlt_subject_id, rlt_related_subject_id, rlt_vice_versa)`
     * 即同一对面条目允许存在正向/反向两行——故"1003 与其他关系不可能落到同一条目上"**无法证实**，
     * 去重是必要的防御（保留先出现的一路：非单行本优先）。
     *
     * NSFW 过滤的唯一边界在下方 `.map`：缓存存全量，切换开关后命中缓存依然正确。
     */
    fun getRelations(subjectId: Int, refresh: Boolean = false): Flow<Load<List<SubjectRelationBo>>> =
        sectionCall(subjectId, SubjectSection.RELATIONS, ListSerializer(SubjectRelationBo.serializer()), refresh) { token ->
            coroutineScope {
                // 两路并行；任一路失败都会使 coroutineScope 抛出，由 cachedNetworkCall 统一转 Failed，
                // 不得静默吞掉单行本那一路的失败（否则会静默缺一整组）
                val paths = listOf(false, true).map { offprint ->
                    async {
                        fetchAllPages(P1_PAGE_LIMIT) { offset, limit -> nextApi.getSubjectRelations(subjectId, offprint, limit, offset, token).toSlice() }
                    }
                }
                paths.awaitAll().flatten()
            }.map { it.toBo() }.distinctBy { it.id }
        }.map { load -> load.map { relations -> nsfwFilter.apply(relations) { it.nsfw } } }

    /**
     * 条目角色：p1 一次请求就带齐中文名、定位与声优，绝大多数条目一页取完；缓存规则见 [sectionCall]。
     * 按 id 去重：分页并行时若服务端数据在两页之间变动，同一角色可能出现两次，而列表按 id 作 key。
     */
    fun getCharacters(subjectId: Int, refresh: Boolean = false): Flow<Load<List<RelatedCharacterBo>>> =
        getCharacters(subjectId, refresh, null, null)

    private fun getCharacters(subjectId: Int, refresh: Boolean, session: AccountSession?, permits: Semaphore?): Flow<Load<List<RelatedCharacterBo>>> =
        sectionCall(subjectId, SubjectSection.CHARACTERS, ListSerializer(RelatedCharacterBo.serializer()), refresh, session) { token ->
            fetchAllPages(P1_PAGE_LIMIT, permits = permits) { offset, limit ->
                session?.let { tokenStore.requireCurrent(it) }
                nextApi.getSubjectCharacters(subjectId, limit, offset, token).toSlice()
            }
                .map { it.toBo() }
                .distinctBy { it.id }
        }

    /** 条目制作人员/人物；缓存规则见 [sectionCall]。 */
    fun getPersons(subjectId: Int, refresh: Boolean = false): Flow<Load<List<RelatedPersonBo>>> =
        getPersons(subjectId, refresh, null, null)

    private fun getPersons(subjectId: Int, refresh: Boolean, session: AccountSession?, permits: Semaphore?): Flow<Load<List<RelatedPersonBo>>> =
        sectionCall(subjectId, SubjectSection.PERSONS, ListSerializer(RelatedPersonBo.serializer()), refresh, session) { token ->
            val fetch: suspend () -> List<RelatedPersonBo> = {
                session?.let { tokenStore.requireCurrent(it) }
                subjectApi.getPersons(subjectId, accessToken = token).map { it.toDomain() }
            }
            if (permits == null) fetch() else permits.withPermit { fetch() }
        }

    /**
     * 作品在网页「透视」页上的评分拆分（按收藏状态、按放送阶段）；缓存规则见 [sectionCall]。
     * 网页不认 OAuth 令牌，取到的总是游客看到的那一份。
     */
    fun getRatingSplit(subjectId: Int, refresh: Boolean = false): Flow<Load<SubjectRatingSplit>> =
        sectionCall(subjectId, SubjectSection.RATING_SPLIT, SubjectRatingSplit.serializer(), refresh) {
            ratingSplitOf(webPageApi.getSubjectStatsCharts(BgmWeb.subjectStats(subjectId)))
        }

    /**
     * 推荐作品（p1，服务端给的顺序，最多 [NextApi.getSubjectRecs] 那么多部）：作品合并进条目表，缓存里只存 id，
     * 读出时按 id 从条目表取回；NSFW 过滤在读取边界。缓存规则见 [sectionCall]。
     */
    fun getRecs(subjectId: Int, refresh: Boolean = false): Flow<Load<List<SubjectBo>>> =
        sectionCall(
            subjectId, SubjectSection.RECS, ListSerializer(Int.serializer()), refresh,
            toValue = { ids ->
                val byId = subjectDao.getByIds(ids).associateBy { it.id }
                ids.mapNotNull { byId[it]?.toBo() }
            },
        ) { token ->
            val recs = nextApi.getSubjectRecs(subjectId, token).data
            val now = clock.nowMillis()
            subjectDao.mergeAll(recs.map { it.subject.toSnapshot(now) })
            recs.map { it.subject.id }
        }.map { load -> load.map { subjects -> nsfwFilter.apply(subjects) { it.nsfw } } }

    /** 最近 [RECENT_COLLECTS_LIMIT] 位收藏者的收藏时刻（毫秒，新的在前）；缓存规则见 [sectionCall]。 */
    fun getRecentCollectTimes(subjectId: Int, refresh: Boolean = false): Flow<Load<List<Long>>> =
        sectionCall(subjectId, SubjectSection.RECENT_COLLECTS, ListSerializer(Long.serializer()), refresh) { token ->
            nextApi.getSubjectCollects(subjectId, limit = RECENT_COLLECTS_LIMIT, accessToken = token).data
                .map { it.interest.updatedAt.epochSecondsToMillis() }
        }

    /**
     * 作品 [subjectId] 所在的系列：从它自己的关联 [relations]（详情页已经取到的那份）出发，沿「前传」一步步往前、
     * 沿「续集」一步步往后，只走与它同为 [type] 的作品，每个方向最多 [SERIES_MAX_STEPS] 步。往后每一步读的是
     * 那几部作品自己的关联（[getRelations]，各自缓存，先到的一拍就用）；某一步取不到，那个方向停在已找到的地方。
     *
     * 每一步要等上一步的关联才知道下一步是谁，一个方向只能一步一步走；两个方向互不依赖，同时走，
     * 没有缓存时的耗时是较长那个方向的步数次往返，不是两个方向相加。数据成环、同一部两个方向都走到时，只留在前面。
     */
    suspend fun getSeries(subjectId: Int, type: SubjectType, relations: List<SubjectRelationBo>): SubjectSeries = coroutineScope {
        val before = async { seriesSteps(relations, SubjectRelationKind.PREQUEL, type, subjectId).asReversed().flatten() }
        val after = async { seriesSteps(relations, SubjectRelationKind.SEQUEL, type, subjectId).flatten() }
        val earlier = before.await()
        val earlierIds = earlier.mapTo(mutableSetOf()) { it.id }
        SubjectSeries(before = earlier, after = after.await().filter { it.id !in earlierIds })
    }

    /** 从 [subjectId] 沿 [kind] 走出的各步，近的在前；一步里有几部（TV 续作与剧场版续作）按服务端排序值、再按 id 排。 */
    private suspend fun seriesSteps(
        start: List<SubjectRelationBo>,
        kind: SubjectRelationKind,
        type: SubjectType,
        subjectId: Int,
    ): List<List<SubjectRelationBo>> {
        val seen = mutableSetOf(subjectId)
        fun stepFrom(relations: List<SubjectRelationBo>) = relations
            .filter { relationKindOf(it.relationId, it.type) == kind && it.type == type && it.id !in seen }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.order }, { it.id }))

        val steps = mutableListOf<List<SubjectRelationBo>>()
        var step = stepFrom(start)
        while (step.isNotEmpty()) {
            steps += step
            seen += step.map { it.id }
            if (steps.size == SERIES_MAX_STEPS) break
            val next = coroutineScope { step.map { async { getRelations(it.id).settled().valueOrNull() } }.awaitAll() }
            if (next.any { it == null }) break
            step = stepFrom(next.flatMap { it.orEmpty() })
        }
        return steps
    }

    /**
     * 条目详情子模块（关联、角色、制作人员、统计用的几项）的唯一缓存通道：与条目本体同一套 SWR 与新鲜期
     * （[CacheTtl.SUBJECT_DETAIL]），按「条目 × 模块 × 查看者」存一行。缓存里存的是 [fetch] 的结果 [C]，
     * 交给页面的是它经 [toValue] 得到的值（只存 id 的模块在这里按 id 取回作品）。
     *
     * 查看者在这一轮开始时取定一次，读缓存、发请求、写缓存都用它：游客与各账号的结果互不复用
     * （服务端对匿名请求会剔除 NSFW 条目）。缓存解析失败（模型字段变了）在 readCache 里抛出，
     * 由 [cachedNetworkCall] 按未命中处理，直接重新拉取。
     */
    private fun <C, V> sectionCall(
        subjectId: Int,
        section: SubjectSection,
        serializer: KSerializer<C>,
        refresh: Boolean,
        toValue: suspend (C) -> V,
        expectedSession: AccountSession? = null,
        fetch: suspend (token: String?) -> C,
    ): Flow<Load<V>> = flow {
        val session = expectedSession ?: tokenStore.session()
        tokenStore.requireCurrent(session)
        val viewerId = session.viewerId
        val stored = cachedNetworkCall(
            readCache = {
                subjectSectionDao.get(subjectId, section.name, viewerId)?.let { row ->
                    Fetched(sectionJson.decodeFromString(serializer, row.json), subjectSectionDao.fetchedAt(subjectId, section, viewerId) ?: 0L)
                }
            },
            mapCache = { it.content },
            fetchNetwork = { tokenStore.withSessionToken(session) { _, token -> fetch(token) } },
            writeCache = { content ->
                val now = clock.nowMillis()
                subjectSectionDao.write(
                    SubjectSectionEntity(
                        subjectId = subjectId,
                        section = section.name,
                        viewerId = viewerId,
                        json = sectionJson.encodeToString(serializer, content),
                        updatedAt = now,
                    ),
                    fetchedAt = now,
                )
            },
            refresh = refresh,
            ttlMillis = CacheTtl.SUBJECT_DETAIL,
            cachedAt = { it.fetchedAt },
            clock = clock,
            workContext = computationDispatcher,
        )
        emitAll(stored.map { load -> load.map { withContext(computationDispatcher) { toValue(it) } } })
    }

    /** 缓存与页面拿到的是同一个值的模块。 */
    private fun <T> sectionCall(
        subjectId: Int,
        section: SubjectSection,
        serializer: KSerializer<T>,
        refresh: Boolean,
        expectedSession: AccountSession? = null,
        fetch: suspend (token: String?) -> T,
    ): Flow<Load<T>> = sectionCall(subjectId, section, serializer, refresh, toValue = { it }, expectedSession = expectedSession, fetch = fetch)

    /** 缓存里读出的内容与它上次取回的时刻（SWR 的新鲜度只看这个，不看正文行的写入时刻）。 */
    private class Fetched<C>(val content: C, val fetchedAt: Long)

    /**
     * 解析过的阵容（`查看者 × 条目 → 两个模块的落库时刻与阵容`）：只保留最近 [LINEUP_MEMO_SIZE] 部摘要。
     * 只有落库时刻变了或已淘汰才重新读正文；缓存预算不限制本轮统计结果，读写与解析共用同一把锁。
     */
    private val lineupMemo = LruMap<Pair<Int, Int>, Pair<List<Long>, SubjectLineup?>>(LINEUP_MEMO_SIZE)
    private val lineupMutex = Mutex()

    override suspend fun cachedLineups(subjects: Map<Int, SubjectType>): Map<Int, SubjectLineup> {
        val viewerId = tokenStore.session().viewerId
        val stamps = lineupStamps(subjects.keys.toList(), viewerId)
        return buildMap {
            for ((subjectId, type) in subjects) {
                val stamp = LINEUP_SECTIONS.mapNotNull { stamps[subjectId to it] }
                if (stamp.size < LINEUP_SECTIONS.size) continue
                val key = viewerId to subjectId
                val memo = lineupMutex.withLock {
                    lineupMemo[key]?.takeIf { it.first == stamp }
                        ?: (stamp to readLineup(subjectId, type, viewerId)).also { lineupMemo[key] = it }
                }
                memo.second?.let { put(subjectId, it) }
            }
        }
    }

    override suspend fun missingLineups(subjectIds: List<Int>): List<Int> {
        val stamps = lineupStamps(subjectIds, tokenStore.session().viewerId)
        return subjectIds.filter { id -> LINEUP_SECTIONS.any { (id to it) !in stamps } }
    }

    override suspend fun fetchLineup(subjectId: Int, session: AccountSession, permits: Semaphore) {
        getCharacters(subjectId, false, session, permits).settled()
        tokenStore.requireCurrent(session)
        getPersons(subjectId, false, session, permits).settled()
    }

    override fun observeLineupChanges(): Flow<Unit> = subjectSectionDao.observeLastWrite().map { }

    private suspend fun lineupStamps(subjectIds: List<Int>, viewerId: Int): Map<Pair<Int, SubjectSection>, Long> =
        subjectIds.distinct().chunked(SQLITE_IN_CHUNK).flatMap { chunk ->
            subjectSectionDao.stamps(chunk, LINEUP_SECTIONS.map { it.name }, viewerId)
        }.associate { (it.subjectId to SubjectSection.valueOf(it.section)) to it.updatedAt }

    /** 正文解析失败（模型字段变了）按没有阵容算；条目详情页下次打开会重新拉取覆盖。 */
    private suspend fun readLineup(subjectId: Int, type: SubjectType, viewerId: Int): SubjectLineup? {
        val characters = subjectSectionDao.get(subjectId, SubjectSection.CHARACTERS.name, viewerId) ?: return null
        val persons = subjectSectionDao.get(subjectId, SubjectSection.PERSONS.name, viewerId) ?: return null
        return try {
            subjectLineupOf(
                type = type,
                characters = sectionJson.decodeFromString(ListSerializer(RelatedCharacterBo.serializer()), characters.json),
                persons = sectionJson.decodeFromString(ListSerializer(RelatedPersonBo.serializer()), persons.json),
            )
        } catch (_: SerializationException) {
            null
        }
    }

    private companion object {
        /** 阵容只留聚合摘要的固定预算：512 部供统计重算复用，超出仍从本机缓存读，不随收藏总量无界增长。 */
        const val LINEUP_MEMO_SIZE = 512

        /** p1 条目子列表（关联、角色）每页条数：服务端上限。 */
        const val P1_PAGE_LIMIT = 100

        /** v0 章节每页条数。 */
        const val V0_EPISODE_PAGE_LIMIT = 100

        /** 算收藏速度用的最近收藏者人数：p1 收藏者列表一页的上限。 */
        const val RECENT_COLLECTS_LIMIT = 100

        /** 系列每个方向最多走几步：再长的系列只画离这部作品最近的这些部。 */
        const val SERIES_MAX_STEPS = 8

        val sectionJson = Json { ignoreUnknownKeys = true }
    }
}
