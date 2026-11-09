package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.db.mapper.toSnapshot
import io.github.touhougo.chogumi.core.data.db.search.RecentSearchDao
import io.github.touhougo.chogumi.core.data.db.search.RecentSearchEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectDao
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.data.util.ObservedPreferences
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchSource
import io.github.touhougo.chogumi.core.model.SearchValidation
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.isActive
import io.github.touhougo.chogumi.core.model.normalizeSearchCriteria
import io.github.touhougo.chogumi.core.model.searchSource
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import io.github.touhougo.chogumi.core.data.db.mapper.toIntList
import io.github.touhougo.chogumi.core.data.db.mapper.toJson
import io.github.touhougo.chogumi.core.data.db.search.SearchFirstPageDao
import io.github.touhougo.chogumi.core.data.db.search.SearchFirstPageEntity
import io.github.touhougo.chogumi.core.data.db.subject.getByIdsChunked
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.data.util.cachedNetworkCall
import io.github.touhougo.chogumi.core.data.util.isCacheStale
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.model.effectiveSort
import io.github.touhougo.chogumi.core.model.presetKey
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 搜索仓库：一组条件的第一页存一份（探索页货架见 [firstPage]，搜索页见 [search]），翻过的页不存；
 * 条目落 subjects 表供详情页 SWR 命中。
 */
class SearchRepository(
    private val subjectApi: SubjectApi,
    private val nextApi: NextApi,
    private val subjectDao: SubjectDao,
    private val recentSearchDao: RecentSearchDao,
    private val firstPageDao: SearchFirstPageDao,
    private val nsfwFilter: NsfwFilter,
    private val tokenStore: TokenStore,
    private val clock: AppClock,
    /** 取页、落库、过滤与转成领域模型都在这里跑：一页 v0 搜索 130–170 KB、每条带资料项 JSON，不占调用方（主线程）。 */
    private val computationDispatcher: CoroutineDispatcher,
    historyPreferences: DataStore<Preferences>,
) {
    private val history = ObservedPreferences(historyPreferences)
    private val historyMutex = Mutex()
    private val clearingHistory = MutableStateFlow(false)

    /**
     * 只有持久归属属于当前会话且没有清理在途时，才查询最近搜索；缺失归属的旧行也不展示。
     * 读不出本机存储时是 [Load.Failed]，由搜索页提示。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recentSearches: Flow<Load<List<String>>> = tokenStore.sessionFlow.flatMapLatest { session ->
        combine(history.data, clearingHistory) { preferences, clearing ->
            session.takeIf { !clearing && preferences[PreferenceKeys.RECENT_SEARCH_OWNER] == historyOwnerOf(it) }
        }.distinctUntilChanged().flatMapLatest { allowed ->
            if (allowed == null) flowOf(emptyList()) else recentSearchDao.latest(RECENT_SEARCH_LIMIT)
                .map { history -> if (tokenStore.isCurrent(session)) history else emptyList() }
        }.map<List<String>, Load<List<String>>> { Load.Ready(it) }
            .failuresAsLoad()
    }.distinctUntilChanged()

    /**
     * 观察完整会话而不是登录布尔位，换了账号就清掉上一个账号的历史。清理失败不提示：读取先认持久归属，
     * 清不掉的旧行新账号也读不到，用户没有要做的事；这一轮结束，下一次会话变化或记录搜索时再清。
     */
    fun maintainRecentSearches(scope: CoroutineScope) {
        scope.launch {
            outcomeOf { tokenStore.sessionFlow.collect { session -> clearForeignHistory(session) } }
        }
    }

    private suspend fun clearForeignHistory(session: AccountSession): Outcome<Unit> = storageOutcomeOf {
        historyMutex.withLock {
            tokenStore.commitForSession(session) { prepareHistory(session) }
        }
    }

    /** 在历史锁与会话提交中运行；先删行，成功后才把整张表授权给新会话，跨文件任一步失败都保持拒绝读取。 */
    private suspend fun prepareHistory(session: AccountSession) {
        val owner = historyOwnerOf(session)
        if (history.data.first()[PreferenceKeys.RECENT_SEARCH_OWNER] == owner) return
        recentSearchDao.deleteAll()
        history.edit { it[PreferenceKeys.RECENT_SEARCH_OWNER] = owner }
    }

    private fun historyOwnerOf(session: AccountSession): String = "${session.generation}:${session.viewerId}"

    /**
     * 唯一搜索入口（搜索页），一次一个结果。条件在仓库侧再次校验：非法直接失败（零网络、零落库；
     * ViewModel 已先校验过，走到这里是调用方的错，记为 [Failure.Unknown]），
     * 空态（[SearchCriteria.isActive] 为假）返回空结果且不发请求。
     * 接口由 [searchSource] 决定；各用接口自己的每页条数，偏移 ↔ 页码只在这里换算。
     * 取回的第一页（[offset] 为 0）存一份；[reuseFirstPage] 时新鲜期 [CacheTtl.SEARCH_FIRST_PAGE] 内直接用存下的那一页
     * （条目、总数、游标），不发请求：再打开排行榜、同一个搜索当场就有。重试、再次提交走网络（[reuseFirstPage] 为假）。
     * [session] 供外层按会话分区缓存的调用方传入捕获会话，确保请求与缓存键属于同一账号。
     */
    suspend fun search(
        criteria: SearchCriteria,
        offset: Int = 0,
        session: AccountSession? = null,
        reuseFirstPage: Boolean = false,
    ): Outcome<PagedResult<SubjectBo>> {
        val normalized = when (val result = normalizeSearchCriteria(criteria)) {
            is SearchValidation.Invalid -> return Outcome.Failed(Failure.Unknown)
            is SearchValidation.Valid -> result.value
        }
        if (!normalized.isActive()) return Outcome.Success(PagedResult(emptyList(), total = 0, nextOffset = null))
        return withContext(computationDispatcher) {
            outcomeOf {
                if (offset == 0) searchFirstPage(normalized, session ?: tokenStore.session(), reuseFirstPage)
                else load(normalized, offset, session, searchLimit = P1_SEARCH_PAGE_SIZE)
            }
        }
    }

    /** 搜索页的第一页：[reuse] 时新鲜期内用存下的那一页，否则取回并存下。与货架的 [firstPage] 同一张表，键按每页条数分开。 */
    private suspend fun searchFirstPage(normalized: SearchCriteria, session: AccountSession, reuse: Boolean): PagedResult<SubjectBo> {
        val key = firstPageKey(normalized, session, nsfwFilter.showNsfw(), P1_SEARCH_PAGE_SIZE)
        if (reuse) {
            cachedFirstPage(key)
                ?.takeUnless { isCacheStale(it.updatedAt, CacheTtl.SEARCH_FIRST_PAGE, clock.nowMillis()) }
                ?.let { return it.page }
        }
        return load(normalized, offset = 0, session, searchLimit = P1_SEARCH_PAGE_SIZE).also { storeFirstPage(key, it) }
    }

    /**
     * 一组条件的第一页，先出上次的结果再按需刷新：探索页的货架冷启动不必等一轮请求才有内容。
     * 新鲜期 [CacheTtl.SEARCH_FIRST_PAGE] 内直接用上次的，不发请求；[refresh]（下拉刷新）强刷。
     * 存的是可见条目的 id 与总数、游标（按 [firstPageKey] 分开存：条件、NSFW 开关、账号、每页条数），条目本身在 subjects 表；
     * 有条目已被本地缓存清理掉时当作没有缓存。取数与 [search] 是同一条路（[load]）。
     */
    fun firstPage(criteria: SearchCriteria, refresh: Boolean): Flow<Load<List<SubjectBo>>> = flow {
        val normalized = when (val result = normalizeSearchCriteria(criteria)) {
            is SearchValidation.Invalid -> return@flow emit(Load.Failed(Failure.Unknown))
            is SearchValidation.Valid -> result.value
        }
        if (!normalized.isActive()) return@flow emit(Load.Ready(emptyList()))
        // 请求与缓存键属于同一个会话：途中换号，结果不会记到另一个账号名下
        val session = tokenStore.session()
        val key = firstPageKey(normalized, session, nsfwFilter.showNsfw(), SHELF_SIZE)
        emitAll(
            cachedNetworkCall(
                readCache = { cachedFirstPage(key) },
                mapCache = { it.page },
                fetchNetwork = { load(normalized, offset = 0, session, searchLimit = SHELF_SIZE) },
                writeCache = { page -> storeFirstPage(key, page) },
                refresh = refresh,
                ttlMillis = CacheTtl.SEARCH_FIRST_PAGE,
                cachedAt = { it.updatedAt },
                clock = clock,
                workContext = computationDispatcher,
            ).map { page -> page.map { it.items } },
        )
    }

    /** 存下的第一页；没有、读不出 id、或有条目已不在 subjects 表时为 null（当作没有缓存）。 */
    private suspend fun cachedFirstPage(key: String): CachedFirstPage? {
        val page = firstPageDao.get(key) ?: return null
        val ids = page.subjectIdsJson.toIntList().ifEmpty { return null }
        val subjects = subjectDao.getByIdsChunked(ids).associateBy { it.id }
        val ordered = ids.map { id -> subjects[id] ?: return null }
        val items = nsfwFilter.apply(ordered) { it.nsfw }.map { it.toBo() }
        return CachedFirstPage(PagedResult(items, total = page.total, nextOffset = page.nextOffset), page.updatedAt)
    }

    private suspend fun storeFirstPage(key: String, page: PagedResult<SubjectBo>) {
        firstPageDao.upsert(
            SearchFirstPageEntity(key, page.items.map { it.id }.toJson(), page.total, page.nextOffset, clock.nowMillis()),
        )
    }

    private class CachedFirstPage(val page: PagedResult<SubjectBo>, val updatedAt: Long)

    /**
     * 第一页缓存的键：条件（含关键词与生效排序）、NSFW 开关、账号、每页条数（货架一屏、搜索页一整页，各存各的）；
     * JSON 数组编码，用户输入的分隔符不会撞键。
     */
    private fun firstPageKey(criteria: SearchCriteria, session: AccountSession, showNsfw: Boolean, limit: Int): String = buildJsonArray {
        add(criteria.presetKey())
        add(criteria.query)
        add(criteria.effectiveSort().name)
        add(showNsfw)
        add(session.userId ?: 0)
        add(limit)
    }.toString()

    /**
     * 取一次（起始页 + 稀疏时补的页）、落库、过滤；[search] 与 [firstPage] 共用。失败抛出。
     * [searchLimit] 是 p1 搜索一页取几条：货架只要第一屏（[SHELF_SIZE]），搜索页按接口上限取；另两个接口的页大小固定。
     */
    private suspend fun load(normalized: SearchCriteria, offset: Int, session: AccountSession?, searchLimit: Int): PagedResult<SubjectBo> {
        val source = normalized.searchSource()

        // 起始页：偏移与页码都可预先算出，必须先单独取一页 —— 常规标签一页即满，
        // 只发 1 个请求就能返回（见 test e），绝不为此多发并发请求。
        val first = fetchPage(source, normalized, offset, session, searchLimit)

        val firstCursor = first.nextOffset
        if (first.items.size >= MIN_VISIBLE_PER_LOAD || firstCursor == null) return first

        // 稀疏标签：剩余最多 MAX_PAGES_PER_LOAD-1 页一次并发发出，全部返回后按页序合并。
        // 起始页的跨度取自服务端实际给的游标（搜索接口可能不按请求的 limit 回），
        // 同一查询内每页跨度一致，故可据此预先算出后续各页偏移 —— 并发安全的前提。
        val span = firstCursor - offset
        val offsets = ArrayList<Int>(MAX_PAGES_PER_LOAD - 1)
        // 以起始页服务端 total 为界，越过它就不再请求（见 test e：总页数不足 6 页不越界）；下一页偏移同全仓的 nextCursor。
        var next: Int? = firstCursor
        while (offsets.size < MAX_PAGES_PER_LOAD - 1 && next != null) {
            offsets.add(next)
            next = nextCursor(offset = next, requestedLimit = span, total = first.total, isEmpty = false)
        }

        val rest = coroutineScope {
            // 任一页失败，整次失败、不推进游标（与串行版一致），其余请求随作用域一同取消。
            offsets.map { pageOffset -> async { fetchPage(source, normalized, pageOffset, session, searchLimit) } }
                .awaitAll()
        }

        return mergeInPageOrder(listOf(first) + rest)
    }

    /** 取一页并落库过滤，返回该页可见条目与游标；游标按服务端原始页算（过滤前）。 */
    private suspend fun fetchPage(
        source: SearchSource,
        criteria: SearchCriteria,
        offset: Int,
        session: AccountSession?,
        searchLimit: Int,
    ): PagedResult<SubjectBo> = when (source) {
        SearchSource.BROWSE -> browse(criteria, offset, session)
        SearchSource.SEARCH -> searchPage(criteria, offset, session, searchLimit)
        SearchSource.DATED_SEARCH -> datedSearchPage(criteria, offset, session)
    }

    /** 按页序合并（页间保持服务端顺序）、按 id 去重（首次出现优先）；游标取最后一页。 */
    private fun mergeInPageOrder(pages: List<PagedResult<SubjectBo>>): PagedResult<SubjectBo> {
        val accumulated = LinkedHashMap<Int, SubjectBo>()
        for (page in pages) {
            for (item in page.items) {
                accumulated.putIfAbsent(item.id, item)
            }
        }
        val last = pages.last()
        return PagedResult(accumulated.values.toList(), total = last.total, nextOffset = last.nextOffset)
    }

    /**
     * 分类与标签浏览（热度、日期、名称三种排序）：按页取，服务端 total 为页数；偏移必须是 [BROWSE_PAGE_SIZE] 的整数倍
     * （由本类的 nextOffset 保证）。
     *
     * **登录时带令牌**：匿名时服务端对部分分类整个不给（2026-10-18 实测：游戏分类「Galgame」三种排序匿名都是 0 条，
     * 同名的用户标签与 RPG 等分类匿名照常有）。账号允许 NSFW 时带令牌的浏览按账号设置分页，NSFW 条目照常在、页是满的
     * （2026-10-18 在设备上看 R18 游戏按日期）。本地 NSFW 过滤去掉的条目仍会让页变稀，由 [load] 一次补取后续几页兜住。
     */
    private suspend fun browse(criteria: SearchCriteria, offset: Int, session: AccountSession?): PagedResult<SubjectBo> {
        val query = criteria.toBrowseQuery()
        val pageNo = offset / BROWSE_PAGE_SIZE + 1
        val page = tokenStore.withSessionToken(session) { _, token ->
            nextApi.browseSubjects(
                type = query.type,
                sort = query.sort,
                tags = query.tags,
                year = query.year,
                month = query.month,
                page = pageNo,
                tagsCat = query.tagsCat,
                accessToken = token,
            )
        }
        val now = clock.nowMillis()
        val subjects = subjectDao.mergeAll(page.data.map { it.toSnapshot(now) })
        // 游标按服务端原始页算（过滤前），过滤后整页为空也要能继续翻页。
        val nextOffset = nextCursor(
            offset = offset,
            requestedLimit = BROWSE_PAGE_SIZE,
            total = page.total * BROWSE_PAGE_SIZE,
            isEmpty = page.data.isEmpty(),
        )
        val visible = nsfwFilter.apply(subjects) { it.nsfw }
        return PagedResult(visible.map { it.toBo() }, total = page.total * BROWSE_PAGE_SIZE, nextOffset = nextOffset)
    }

    /** p1 搜索：偏移分页，total 为条数，一页 [limit] 条（上限 [P1_SEARCH_PAGE_SIZE]），回短行。 */
    private suspend fun searchPage(criteria: SearchCriteria, offset: Int, session: AccountSession?, limit: Int): PagedResult<SubjectBo> {
        val page = tokenStore.withSessionToken(session) { _, token ->
            nextApi.searchSubjects(
                criteria.toP1SearchRequestDto(nsfwEnabled = nsfwFilter.showNsfw()),
                limit = limit,
                offset = offset,
                accessToken = token,
            )
        }
        val now = clock.nowMillis()
        val subjects = subjectDao.mergeAll(page.data.map { it.toSnapshot(now) })
        return searchResult(subjects, page.total, nextCursor(offset, requestedLimit = limit, total = page.total, isEmpty = page.data.isEmpty()))
    }

    /** 带时间条件的搜索（v0）：偏移分页，total 为条数，一页最多 [DATED_SEARCH_PAGE_SIZE] 条。 */
    private suspend fun datedSearchPage(criteria: SearchCriteria, offset: Int, session: AccountSession?): PagedResult<SubjectBo> {
        val page = tokenStore.withSessionToken(session) { _, token ->
            subjectApi.searchSubjects(
                criteria.toSearchRequestDto(nsfwEnabled = nsfwFilter.showNsfw()),
                limit = DATED_SEARCH_PAGE_SIZE,
                offset = offset,
                accessToken = token,
            )
        }
        val now = clock.nowMillis()
        val subjects = subjectDao.mergeAll(page.data.map { it.toSnapshot(now, detail = false) })
        val cursor = nextCursor(
            offset = offset,
            requestedLimit = DATED_SEARCH_PAGE_SIZE,
            total = page.total,
            isEmpty = page.data.isEmpty(),
            responseLimit = page.limit,
        )
        return searchResult(subjects, page.total, cursor)
    }

    /**
     * 一页搜索结果（两个搜索接口共用）：游标按服务端原始页算（过滤前，过滤后整页为空也要能继续翻页），
     * 叠加 offset < [SEARCH_MAX_OFFSET] 的硬上限，到了即视为到底；total 为条数（服务端同样封顶 1000）。
     */
    private suspend fun searchResult(subjects: List<SubjectEntity>, total: Int, cursor: Int?): PagedResult<SubjectBo> {
        val visible = nsfwFilter.apply(subjects) { it.nsfw }
        return PagedResult(visible.map { it.toBo() }, total = total, nextOffset = cursor?.takeIf { it < SEARCH_MAX_OFFSET })
    }

    suspend fun recordSearch(query: String): Outcome<Unit> = storageOutcomeOf {
        if (query.isNotBlank()) {
            val session = tokenStore.session()
            historyMutex.withLock {
                tokenStore.commitForSession(session) {
                    prepareHistory(session)
                    recentSearchDao.record(RecentSearchEntity(query = query.trim(), searchedAt = clock.nowMillis()), keep = RECENT_SEARCH_LIMIT)
                }
            }
        }
    }

    suspend fun clearRecentSearches(): Outcome<Unit> = storageOutcomeOf {
        val session = tokenStore.session()
        historyMutex.withLock {
            tokenStore.commitForSession(session) {
                clearingHistory.value = true
                try {
                    // 先撤销持久读取许可；即使删除失败或进程退出，旧行也不能恢复可见。
                    history.edit { it.remove(PreferenceKeys.RECENT_SEARCH_OWNER) }
                    recentSearchDao.deleteAll()
                    history.edit { it[PreferenceKeys.RECENT_SEARCH_OWNER] = historyOwnerOf(session) }
                } finally {
                    clearingHistory.value = false
                }
            }
        }
    }

    companion object {
        /** 「最近搜过」显示几个词。 */
        const val RECENT_SEARCH_LIMIT = 10

        /** 分类浏览接口每页固定 24 条（实测，不可调）。 */
        const val BROWSE_PAGE_SIZE = 24

        /** v0 搜索接口单页上限 20 条（实测：请求 24、50、100 都只返回 20，响应 limit=20）。 */
        const val DATED_SEARCH_PAGE_SIZE = 20

        /** p1 搜索接口单页上限 100 条（接口说明与 2026-10-16 实测一致）：搜索页一次取这么多。 */
        const val P1_SEARCH_PAGE_SIZE = 100

        /** 探索页货架只取第一屏这么多条（p1 搜索按此请求；浏览接口固定 24 条一页）。 */
        const val SHELF_SIZE = 20

        /**
         * 两个搜索接口的 offset 硬上限：服务端 offset=980 有数据、offset>=1000 恒空，
         * 且 total 也被封顶在 1000（2026-09-28 实测；p1 搜索 2026-10-16 实测相同）。
         * 达到即视为到底，返回 nextOffset=null 不再请求。
         *
         * 只作用于 [searchResult]：p1 分类浏览能翻到第 399 页（≈9576 条），
         * 故绝不能把上限加进共用的 [nextCursor]。
         */
        const val SEARCH_MAX_OFFSET = 1000

        /**
         * 单次加载最小期望可见条目数。
         * 依据：两至三列网格下一屏约可展示 6~10 条，凑满 12 条可填满一屏多，消除稀疏标签下一两条零散冒出的体验；
         * 未过滤时单页（20~24 条）即可满足，不会产生多余网络请求。
         * my design decision (not in M3)
         */
        const val MIN_VISIBLE_PER_LOAD = 12

        /**
         * 单次加载连续取页的最大页数上限。
         * 依据：针对本地过滤后稀疏的页（每页 24 条经过滤后可能只剩 1~2 条）最多连续取 6 页（共 144 条原始数据），
         * 避免极端稀疏或全空数据段造成无休止网络请求与超时；达到上限后及时返回已有条目并保留有效 nextOffset。
         * my design decision (not in M3)
         */
        const val MAX_PAGES_PER_LOAD = 6
    }
}
