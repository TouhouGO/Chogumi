package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressDao
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressEntity
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectField
import io.github.touhougo.chogumi.core.data.db.mapper.epochSecondsToMillis
import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toEpisodeBo
import io.github.touhougo.chogumi.core.data.db.mapper.toSnapshot
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.db.subject.getByIdsChunked
import io.github.touhougo.chogumi.core.data.db.subject.getBySubjectIdsChunked
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.data.util.cachedNetworkCall
import io.github.touhougo.chogumi.core.data.util.isCacheStale
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.FriendActivityBo
import io.github.touhougo.chogumi.core.model.HomeProgressItemBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.userAvatarUrl
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.ProgressItemDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private val episodeListSerializer = ListSerializer(CachedEpisode.serializer())

/**
 * 缓存列用的章节形状。
 *
 * `EpisodeBo` 不是 `@Serializable`，而这里只需回显字段；更重要的是**刻意不给它加
 * `@Serializable`**——那会让领域模型承担持久化细节，且服务端返回的 `eps[]`
 * 可能被截断（见 [HomeRepository] 类注释），不值得把它当成可长期存储的对象。
 */
@Serializable
private data class CachedEpisode(
    val id: Int,
    val type: Int,
    val name: String,
    val nameCn: String,
    val sort: Double,
    val airdate: String,
)

/**
 * 首页数据仓库（`/p1/home` 的进度区块 + 好友在看）。
 *
 * ## 为什么必须缓存优先
 *
 * `/p1/home` 实测 **2.5–3.4 秒、663 KB**（2026-09-29 实测），
 * 是本项目最慢最大的端点，绝不能阻塞首帧。因此沿用项目既有的
 * [cachedNetworkCall]（先回显缓存，过期再后台刷新，刷新失败交给页面提示）。
 *
 * ## 两条与其它仓库不同的约束（都来自实测）
 *
 * 1. **空 `progress` 不清缓存**。服务端各区块独立 `Promise.all(...).catch()`，
 *    单块失败只记日志并返回空数组，**HTTP 恒 200、无失败标志**（报告 §8）。
 *    因此「空」与「用户没有在看条目」不可区分；清空会把上一条真实缓存抹掉。
 *    这里通过 `writeCache` 只在**非空**时替换来保证。
 * 2. **未登录时完全省略 Authorization 头**。带空令牌会得到 401
 *    `AUTHORIZATION_INVALID`，而不是 200 空数据（报告 §8.1）。
 *
 * ## 逐集状态不在这里
 *
 * `progress[].eps` 会被服务端截断（>72 集时只给 73 元素窗口，报告 §2），
 * 所以它是**参考**而非逐集真源。逐集状态读
 * [CollectionObserver.observeEpisodeWatchStates]（v0 端点，返回全量 + 显式 type）。
 */
class HomeRepository(
    private val nextApi: NextApi,
    private val homeProgressDao: HomeProgressDao,
    private val subjectDao: SubjectDao,
    private val tokenStore: TokenStore,
    private val collectionStore: CollectionStore,
    private val collectionObserver: CollectionObserver,
    private val watchStateStore: WatchStateStore,
    /** 首页带回的收藏与逐集状态落库时，不盖本地正写着或请求发出后写过的条目。 */
    private val holds: LocalProgressHolds,
    private val episodeDao: EpisodeDao,
    /** 一拍的四张表一个事务（[fetchProgress]）。 */
    private val transactions: TransactionRunner,
    private val clock: AppClock,
    /**
     * 读缓存、取网络、落库、补全与映射都在这里跑：`/p1/home` 一次约 660 KB，缓存的逐集列表每部最多 73 集 JSON，
     * 不占收集方（`viewModelScope` = 主线程）。
     */
    private val computationDispatcher: CoroutineDispatcher,
) {

    /**
     * 首页进度区块：先缓存再刷新。缓存拍与网络拍都按 `updatedAt` 倒序（与接口排序一致）。
     *
     * 截断窗口不能作为可操作的进度结果：正片序号、积压数和已播上限都需要完整章节。
     * 已有完整章节与逐集状态时立即回显；没有时先补全，再发成功结果。
     * 自动的网络刷新只为本地对不上服务端的截断作品重新分页（[keepsLocalHistory]）；下拉刷新（[refresh]）把截断作品都重新分页：
     * 在网页上改了窗口外的集、看过的集数又没变时，本地判不出来，用户主动刷新就得拿到服务端的全部记录。
     * 两种都不会先用短窗口覆盖已显示的完整数据。
     */
    suspend fun getHomeProgress(refresh: Boolean = false): Flow<Load<List<HomeProgressItemBo>>> {
        // 会话与缓存时间戳在构造流之前取一次：cachedAt 回调不是 suspend，无法在内部读库。
        // 这一次加载的读缓存、请求、落库与补全都固定在这个会话：途中换号就以 SessionChanged 结束，A 的数据不进 B 的分区。
        val session = tokenStore.session().takeIf { it.authenticated }
        val cachedAt = session?.userId?.let { homeProgressDao.getMeta(it)?.updatedAt } ?: 0L
        return cachedNetworkCall(
            readCache = {
                session?.userId?.let { homeProgressDao.getByAccount(it).ifEmpty { null } }
            },
            mapCache = { rows ->
                val subjects = subjectDao.getByIdsChunked(rows.map { it.subjectId }).associateBy { it.id }
                rows.map { row -> row.toBo(subjects[row.subjectId]?.toBo()) }
            },
            fetchNetwork = { fetchProgress(session) },
            // 落库在 fetchProgress 里与条目快照、收藏、逐集状态同一个事务写完
            writeCache = {},
            refresh = refresh,
            ttlMillis = CacheTtl.HOME_PROGRESS,
            cachedAt = { cachedAt },
            clock = clock,
            workContext = computationDispatcher,
        ).transform { load ->
            if (load !is Load.Ready) {
                emit(load)
                return@transform
            }
            // 补全在计算调度器上做，发射留在收集方（同 cachedNetworkCall 的 workContext，不接 flowOn）
            val ready = withContext(computationDispatcher) { restoreCompleteProgress(load.value, session) }
            if (ready != null) emit(Load.Ready(ready, load.fromCache))
            if (!load.fromCache || ready == null) {
                val (backfill, completed) = withContext(computationDispatcher) {
                    val backfill = backfillTruncated(load.value, refresh, session)
                    backfill to backfill?.let { restoreCompleteProgress(it.items, session, it.confirmedEmpty) }
                }
                if (completed != null) {
                    emit(Load.Ready(completed))
                } else if (ready == null) {
                    // 补全没成：交出补全时遇到的失败原因（章节或逐集状态）；都没失败却仍不完整，原因不明
                    emit(Load.Failed(backfill?.failure ?: Failure.Unknown))
                }
            }
        }
    }

    /**
     * 本机已有的在看作品章节，不取网络：首页进度缓存里每部的章节窗口，并上本地全章节（与首页补全同一个合并），
     * 当前账号的；换号跟着换，未登录是空的。本机没有这部的首页进度缓存时它不在结果里。
     * 每日放送的「我在追」按它判哪天播（`followedAiringDateOf`）。窗口可能被截断（类注释），只用来找下一集的播出日，不算进度。
     * 读不出来是一拍 [Failure.Storage]。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCachedEpisodes(): Flow<Load<Map<Int, List<EpisodeBo>>>> =
        tokenStore.sessionFlow.flatMapLatest { session ->
            val accountId = session.userId?.takeIf { session.authenticated }
            if (accountId == null) {
                flowOf(emptyMap())
            } else {
                combine(homeProgressDao.observeByAccount(accountId), episodeDao.observeLastWrite()) { rows, _ ->
                    val full = episodeDao.getBySubjectIdsChunked(rows.map { it.subjectId }).groupBy({ it.subjectId }, { it.toBo() })
                    rows.associate { row -> row.subjectId to mergeEpisodeMetadata(full[row.subjectId].orEmpty(), row.windowEpisodes()) }
                }
            }
        }.storageAsLoad().flowOn(computationDispatcher)

    /**
     * 缓存拍与网络拍共用的完整数据入口。episodes 表只由 v0 全量分页完成后替换，
     * 不能用 p1 的窗口独立计算正片序号；全量逐集状态未到齐时同样不能把缺项当成未看。
     */
    private suspend fun restoreCompleteProgress(
        items: List<HomeProgressItemBo>,
        session: AccountSession?,
        confirmedEmpty: Set<Int> = emptySet(),
    ): List<HomeProgressItemBo>? {
        val restored = ArrayList<HomeProgressItemBo>(items.size)
        for (item in items) restored += if (item.subject.id in confirmedEmpty) item else restoreComplete(item, session) ?: return null
        return restored
    }

    /** 一部的完整进度：窗口本身完整就是它；否则本地全章节并上窗口的元数据，还没有章节或正片缺逐集状态时为 null。 */
    private suspend fun restoreComplete(item: HomeProgressItemBo, session: AccountSession?): HomeProgressItemBo? {
        if (EpisodeTracking.isEpisodeListComplete(item.subject.type, item.subject.eps ?: 0, item.eps)) return item
        val full = episodeDao.getBySubject(item.subject.id).map { it.toBo() }
        if (full.isEmpty()) return null
        val episodes = mergeEpisodeMetadata(full, item.eps)
        val states = session?.let { collectionObserver.cachedEpisodeWatchStates(item.subject.id, it) }.orEmpty()
        if (EpisodeTracking.mainEpisodes(episodes).any { it.id !in states }) return null
        return item.copy(eps = episodes)
    }

    /**
     * 用 [session]（未登录为 null）的凭据拉 `/p1/home` 并只映射进度区块；其余区块（时间线 / 小组 / 放送表）不映射。
     * 响应回来时会话已经换了：抛 [io.github.touhougo.chogumi.core.data.token.SessionChangedException]，什么也不落库。
     *
     * 一拍的四张表（条目快照、收藏、逐集状态、首页进度）在一个事务里写：半途取消或换号不留半套，
     * 订阅这几张表的流（首页、放送表、统计）也只因这一拍各重算一次。
     */
    private suspend fun fetchProgress(session: AccountSession?): List<HomeProgressItemBo> {
        val start = holds.beginRead()
        val response = nextApi.getHome(session?.let { tokenStore.tokenFor(it) })
        session?.let { tokenStore.requireCurrent(it) }
        val items = response.progress.map { it.toBo() }
        val now = clock.nowMillis()
        // 摘要落库：让首页条目在 subjects 表里有完整行（NSFW 真源、详情页复用），收藏合并依赖它。
        val snapshots = response.progress.map { it.subject.toProgressSnapshot(now) }
        val accountId = session?.userId
        if (session == null || accountId == null) {
            subjectDao.mergeAll(snapshots)
            return items
        }
        // 灌表：对 eps[] 里出现的每一集写逐集状态。有 collection 就取 status，没有就是 NONE。不在 eps[] 里的集不碰。
        val watchStateRows = response.progress.flatMap { progressItem ->
            progressItem.eps.map { ep ->
                EpisodeWatchStateEntity(
                    episodeId = ep.id,
                    subjectId = progressItem.subject.id,
                    accountId = accountId,
                    status = ep.collection?.status ?: EpisodeStatus.NONE.value,
                    updatedAt = (ep.collection?.updatedAt ?: 0L).epochSecondsToMillis(),
                )
            }
        }
        val progressRows = items.map { item ->
            HomeProgressEntity(
                subjectId = item.subject.id,
                accountId = accountId,
                subjectType = item.subject.type.value,
                epsJson = json.encodeToString(episodeListSerializer, item.eps.map { it.toCached() }),
                updatedAt = item.updatedAt,
            )
        }
        tokenStore.commitForSession(session) {
            transactions.inTransaction {
                subjectDao.mergeAll(snapshots)
                // 收藏（含进度与在看状态）进收藏表：首页书籍进度与「是否仍在看」只从收藏表读。
                collectionStore.writeHomeCollections(session, response.progress.map { it.subject.id to it.interest }, watchStateRows, start)
                // 首页进度只在非空时替换：空数组来自「区块失败」或「真的没有在看条目」，两者不可区分（报告 §8），
                // 保留旧缓存比清空更安全（类注释约束 1）
                if (progressRows.isNotEmpty()) homeProgressDao.replaceForAccount(accountId, progressRows, now)
            }
        }
        // 所有成功发射统一经 restoreCompleteProgress 校验。
        return items
    }

    /** 补全后的列表、本轮确认没有章节的条目，以及补全途中遇到的第一个失败（没有则为 null）。 */
    private class Backfill(val items: List<HomeProgressItemBo>, val confirmedEmpty: Set<Int>, val failure: Failure?)

    /**
     * 截断回退：`eps[]` 只有在正片+SP 数 <= 72 时才是完整的（判定见 [EpisodeTracking.isEpisodeListComplete]）。
     * 不完整、且本地记录不能直接用（[keepsLocalHistory]，下拉刷新 [refresh] 时一律不用）的条目各自一路取全章节与逐集状态
     * （[WatchStateStore.refreshEpisodeHistory]：用户逐集端点带着全部章节），**条目之间并行**；
     * 失败时保留原始接口数据，发射入口决定是否有完整缓存可保留；绝不把窗口发给界面。
     * 没有要补的条目时返回 null（不必再发一拍）。
     */
    private suspend fun backfillTruncated(items: List<HomeProgressItemBo>, refresh: Boolean, session: AccountSession?): Backfill? {
        val truncated = items.filterNot {
            EpisodeTracking.isEpisodeListComplete(it.subject.type, it.subject.eps ?: 0, it.eps)
        }
        if (truncated.isEmpty()) return null
        val stale = if (refresh) {
            truncated
        } else {
            val current = session?.let { pinned -> watchStateStore.currentWatchHistories(truncated.map { it.subject.id }, pinned) }.orEmpty()
            val now = clock.nowMillis()
            truncated.filterNot { keepsLocalHistory(it, current, now, session) }
        }
        if (stale.isEmpty()) return null
        val permits = Semaphore(SYNC_CONCURRENCY)
        val results = coroutineScope {
            stale.map { item ->
                async {
                    val id = item.subject.id
                    val history = watchStateStore.refreshEpisodeHistory(id, session, permits)
                    BackfillResult(id, history.valueOrNull(), history.failureOrNull())
                }
            }.awaitAll()
        }
        val fullEps = results.mapNotNull { r -> r.history?.let { r.subjectId to it.episodes } }.toMap()
        return Backfill(
            items = items.map { item -> fullEps[item.subject.id]?.let { item.copy(eps = it) } ?: item },
            confirmedEmpty = results.filter { it.history?.confirmedEmpty == true }.mapTo(HashSet()) { it.subjectId },
            failure = results.firstNotNullOfOrNull { it.failure },
        )
    }

    private class BackfillResult(val subjectId: Int, val history: EpisodeHistory?, val failure: Failure?)

    /**
     * 一部截断作品的本地全章节与逐集状态能否直接用、不必为它重新分页（长篇一部上千集，一次一两 MB）：
     * 本地拼得出完整进度（[restoreComplete]），看过的集数与服务端对得上（[WatchStateStore.currentWatchHistories]），
     * 章节在新鲜期内（[CacheTtl.EPISODES]），且正片不少于服务端给的总集数（新出的集落在窗口之外时靠这条发现）。
     * 窗口里的逐集状态每轮都由 `/p1/home` 灌表，本来就是新的。
     */
    private suspend fun keepsLocalHistory(item: HomeProgressItemBo, current: Set<Int>, now: Long, session: AccountSession?): Boolean {
        if (item.subject.id !in current) return false
        val chapters = episodeDao.getBySubject(item.subject.id)
        if (chapters.isEmpty() || isCacheStale(episodeDao.fetchedAt(item.subject.id) ?: 0L, CacheTtl.EPISODES, now)) return false
        if (EpisodeTracking.mainEpisodes(chapters.map { it.toBo() }).size < (item.subject.eps ?: 0)) return false
        return restoreComplete(item, session) != null
    }

    /**
     * 好友的在看动态（`GET /p1/me/friends/subject-collections`）。
     *
     * 这里不缓存：它是按 `subjectType`
     * 分页的列表，且首页只用第一页，做 SWR 反而凭空多一层状态
     * （与 `UserRepository` 对分页列表的取舍一致）。
     *
     * 返回值含他人昵称与头像地址，属个人信息。
     */
    suspend fun getFriendsSubjectCollections(
        subjectType: SubjectType,
        limit: Int = 10,
    ): Outcome<List<FriendActivityBo>> = withContext(computationDispatcher) { friendsSubjectCollections(subjectType, limit) }

    private suspend fun friendsSubjectCollections(subjectType: SubjectType, limit: Int): Outcome<List<FriendActivityBo>> = outcomeOf {
        val session = tokenStore.session().takeIf { it.authenticated }
            ?: return Outcome.Failed(Failure.NotLoggedIn)
        // 请求前后固定在同一个会话：跨过换号的结果（含他人昵称与头像）不落地
        tokenStore.withSessionToken(session) { _, token ->
            val page = nextApi.getFriendsSubjectCollections(subjectType.value, limit = limit, accessToken = token)
            val now = clock.nowMillis()
            // 整页一个事务（同 [fetchProgress] 的摘要落库）；mergeAll 按入参顺序返回合并后的行
            val subjects = subjectDao.mergeAll(page.data.map { it.subject.toSnapshot(now) })
            page.data.zip(subjects) { dto, subject ->
                FriendActivityBo(
                    userId = dto.user.id,
                    username = dto.user.username,
                    nickname = dto.user.nickname,
                    avatarUrl = dto.user.avatar.let { userAvatarUrl(it.large, it.medium, it.small) },
                    subject = subject.toBo(),
                    collectionType = CollectionType.from(dto.collectionType) ?: CollectionType.UNKNOWN,
                    updatedAt = dto.updatedAt.epochSecondsToMillis(),
                )
            }
        }
    }
}

/** 完整列表保留全部章节，同 id 的元数据采用此次首页响应，纳入缓存之后新播出的集。 */
private fun mergeEpisodeMetadata(full: List<EpisodeBo>, window: List<EpisodeBo>): List<EpisodeBo> =
    (full + window).associateBy { it.id }.values.sortedBy { it.sort }

/** ProgressSubject 承诺总集数与卷数，不能经过不承诺这些字段的 SlimSubject 映射后丢掉。 */
private fun io.github.touhougo.chogumi.core.network.model.p1.ProgressSubjectDto.toProgressSnapshot(updatedAt: Long) =
    toSlim().toSnapshot(updatedAt).let { snapshot ->
        snapshot.copy(
            value = snapshot.value.copy(eps = eps, volumes = volumes),
            fields = snapshot.fields + setOf(SubjectField.EPS, SubjectField.VOLUMES),
        )
    }

/** `/p1/home` 的 `ProgressSubject` → 摘要形状，用于 subjects 表落库。 */
private fun io.github.touhougo.chogumi.core.network.model.p1.ProgressSubjectDto.toSlim() = P1SlimSubjectDto(
    id = id, name = name, nameCN = nameCN, type = type, images = images,
    nsfw = nsfw, locked = locked, metaTags = metaTags, info = info, rating = rating,
    // 进度区块的 interest 是完整 SubjectInterest（含进度），摘要种子不需要它
    interest = null,
)

private fun ProgressItemDto.toBo(): HomeProgressItemBo = HomeProgressItemBo(
    subject = subject.toBo(),
    eps = eps.map { it.toEpisodeBo() },
    updatedAt = interest.updatedAt.epochSecondsToMillis(),
)

private fun io.github.touhougo.chogumi.core.network.model.p1.ProgressSubjectDto.toBo() = SubjectBo(
    id = id,
    type = SubjectType.from(type) ?: SubjectType.UNKNOWN,
    name = name,
    nameCn = nameCN,
    summary = info,
    nsfw = nsfw,
    date = airDate.ifBlank { null },
    imageLarge = images?.large,
    imageCommon = images?.common,
    ratingScore = rating.score,
    ratingTotal = rating.total,
    eps = eps,
    volumes = volumes,
    metaTags = metaTags,
    rawType = type,
)

private fun EpisodeBo.toCached() = CachedEpisode(
    id = id, type = type, name = name, nameCn = nameCn, sort = sort, airdate = airdate,
)

private fun HomeProgressEntity.toBo(subject: SubjectBo?) = HomeProgressItemBo(
    subject = subject ?: SubjectBo(
        id = subjectId, type = SubjectType.from(subjectType) ?: SubjectType.UNKNOWN, name = "", nameCn = "",
    ),
    eps = windowEpisodes(),
    updatedAt = updatedAt,
)

/** 缓存行里存的章节窗口；读不懂的存档当没有章节。 */
private fun HomeProgressEntity.windowEpisodes(): List<EpisodeBo> =
    runCatching { json.decodeFromString(episodeListSerializer, epsJson) }.getOrDefault(emptyList()).map { it.toBo(subjectId) }

private fun CachedEpisode.toBo(subjectId: Int) = EpisodeBo(
    id = id, subjectId = subjectId, type = type, name = name, nameCn = nameCn, sort = sort, airdate = airdate,
)
