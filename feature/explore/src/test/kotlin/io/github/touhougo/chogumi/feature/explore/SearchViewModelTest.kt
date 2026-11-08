package io.github.touhougo.chogumi.feature.explore

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfDao
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfEntity
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.ExploreLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.FakeExploreShelfDao
import io.github.touhougo.chogumi.core.data.repository.FaultableExploreShelfDao
import io.github.touhougo.chogumi.core.data.repository.FakeRecentSearchDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeTagCatalogDao
import io.github.touhougo.chogumi.core.data.repository.SearchRepository
import io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSearchRepository
import io.github.touhougo.chogumi.core.data.repository.testTagCatalogRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.model.PinnedShelf
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.TagSource
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.availableSorts
import io.github.touhougo.chogumi.core.model.effectiveSort
import io.github.touhougo.chogumi.core.model.isTagBrowse
import io.github.touhougo.chogumi.core.model.pickWord
import io.github.touhougo.chogumi.core.model.searchDimensionsFor
import io.github.touhougo.chogumi.core.model.shelfPresetOf
import io.github.touhougo.chogumi.core.model.tagSearchCriteria
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.model.p1.SearchSubjectDto
import io.github.touhougo.chogumi.core.network.model.v0.SearchFilterDto
import io.github.touhougo.chogumi.core.network.model.v0.SearchRequestDto
import io.github.touhougo.chogumi.core.res.R
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SearchViewModelTest {
    @Test
    fun `旧筛选面板提示重试不会请求新面板`() = runBlocking {
        val calls = AtomicInteger()
        val fixture = makeViewModel(seed = SearchCriteria(subjectType = SubjectType.ANIME), onIntercept = { request ->
            if (request.url.encodedPath.startsWith("/p1/channels/")) {
                calls.incrementAndGet()
                respond("boom", HttpStatusCode.InternalServerError)
            } else null
        })
        val vm = fixture.viewModel
        awaitUntil("分类搜索完成") { fixture.requestedOffsets.isNotEmpty() && !vm.uiState.value.results.isLoading }
        vm.onFiltersOpen()
        awaitUntil("首次失败") { vm.failures.current.value != null }
        val old = vm.failures.current.value!!
        vm.onFiltersDismiss()
        vm.onFiltersOpen()
        vm.onPanelQueryChange("新面板")
        awaitUntil("新面板失败") { calls.get() == 2 && vm.failures.current.value !== old }
        val jobs = withContext(main.dispatcher) {
            val before = vm.viewModelScope.coroutineContext.job.children.toSet()
            old.retry!!.invoke()
            vm.viewModelScope.coroutineContext.job.children.filter { it !in before }.toList()
        }
        jobs.joinAll()
        assertEquals(2, calls.get())
        assertEquals("新面板", vm.uiState.value.panelQuery)
    }

    @Test
    fun `首次目录失败提示并能重试而不清除输入`() = runBlocking {
        val calls = AtomicInteger()
        val catalogDao = FakeTagCatalogDao()
        val fixture = makeViewModel(
            seed = SearchCriteria(subjectType = SubjectType.ANIME),
            catalogDao = catalogDao,
            onIntercept = { request ->
                if (!request.url.encodedPath.startsWith("/p1/channels/")) null
                else if (calls.incrementAndGet() == 1) respond("boom", HttpStatusCode.InternalServerError)
                else respond("""{"data":[{"name":"动画标签","count":10}],"total":1}""", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            },
        )
        val vm = fixture.viewModel
        awaitUntil("分类搜索完成") { fixture.requestedOffsets.isNotEmpty() && !vm.uiState.value.results.isLoading }
        vm.onFiltersOpen()
        vm.onPanelQueryChange("动画")
        vm.onDraftWordPick("草稿", WordPick.ADD)
        val draft = vm.uiState.value.form.filterDraft
        awaitUntil("首次目录失败提示") { vm.failures.current.value != null }
        val notice = vm.failures.current.value!!
        assertEquals(1, calls.get())
        assertTrue(vm.uiState.value.form.filterDraft != null)
        assertTrue(notice.retry != null)
        notice.retry!!.invoke()
        awaitUntil("目录重试写入") { catalogDao.rowsOf(SubjectType.ANIME.value).isNotEmpty() }
        assertEquals(2, calls.get())
        assertEquals(listOf("动画标签"), catalogDao.search(SubjectType.ANIME.value, "动画", 10).map { it.name })
        assertEquals("动画", vm.uiState.value.panelQuery)
        assertEquals(draft, vm.uiState.value.form.filterDraft)
    }

    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val json = Json { ignoreUnknownKeys = true }

    /** 不带时间条件的搜索（p1）一页的条数；带时间条件的走 v0，一页 [datedPage] 条。 */
    private val page = SearchRepository.P1_SEARCH_PAGE_SIZE
    private val datedPage = SearchRepository.DATED_SEARCH_PAGE_SIZE

    /** 一个搜索请求带的条件（两个搜索接口统一按 v0 的字段名读，见 [asConditions]）。 */
    private suspend fun conditionsOf(request: HttpRequestData): SearchRequestDto {
        val raw = request.body.toByteArray().decodeToString()
        return if (request.url.encodedPath == "/p1/search/subjects") json.decodeFromString<SearchSubjectDto>(raw).asConditions()
        else json.decodeFromString(raw)
    }

    /** 记录请求体用：与 App 发出的请求一样省略空字段。 */
    private val bodyJson = Json { explicitNulls = false }

    /**
     * 两个搜索接口是同一份条件、只换字段名（`SearchRequestMapperTest` 逐字段核过）：p1 搜索的请求体按 v0 的字段名记下，
     * 这里的断言只看 ViewModel 提交了什么条件；走的是哪个接口另记在 [VmFixture.searchPaths]。
     */
    private fun SearchSubjectDto.asConditions() = SearchRequestDto(
        keyword = keyword,
        sort = sort ?: "match",
        filter = filter?.let {
            SearchFilterDto(type = it.type, tag = it.tags, airDate = it.date, rating = it.rating, nsfw = it.nsfw, metaTags = it.metaTags, rank = it.rank)
        },
    )

    private val main = TestMain()
    private val viewModels = mutableListOf<SearchViewModel>()


    @BeforeTest
    fun setUp() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope })

        viewModels.clear()

    }

    /**
     * 面板搜索框要用的标签目录仓库。用与搜索同一个 MockEngine，目录请求回一份空目录
     * （total=0）：这些用例测的是搜索与分页，目录内容另有建议合成的单测覆盖。
     */
    private fun tagCatalogFor(client: HttpClient, dao: FakeTagCatalogDao): TagCatalogRepository =
        testTagCatalogRepository(NextApi(client), dao, testNsfwFilter(resources))

    private data class VmFixture(
        val viewModel: SearchViewModel,
        val requestedOffsets: List<Int>,
        val capturedBodies: List<String>,
        val recentSearchDao: FakeRecentSearchDao,
        /** 每个搜索请求打到的路径（与 [capturedBodies] 一一对应）。 */
        val searchPaths: List<String>,
        /** 再打开一次搜索页：同一个仓库、同一个假服务端上的新 ViewModel。 */
        val reopen: (SearchCriteria?) -> SearchViewModel,
    )

    private fun makeViewModel(
        total: Int = 40,
        missing: Set<Int> = emptySet(),
        seed: SearchCriteria? = null,
        openFilters: Boolean = false,
        catalogDao: FakeTagCatalogDao? = null,
        /** 探索页布局（图钉写在这里）；不测图钉的用例给一份空表。 */
        layoutRepository: ExploreLayoutRepository = ExploreLayoutRepository(FakeExploreShelfDao()),
        /** 按解析后的完整 DTO 决定结果 ID 基址（用完整条件 A/B 区分，不靠标签名）。 */
        idBaseResolver: ((SearchRequestDto) -> Int)? = null,
        onIntercept: (suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?)? = null,
    ): VmFixture {
        val requestedOffsets = mutableListOf<Int>()
        val capturedBodies = mutableListOf<String>()
        val searchPaths = mutableListOf<String>()
        val engine = MockEngine { request ->
            // 事件、状态和假服务端记录共享 Main；挂起闸门仍允许其他请求交错执行。
            withContext(main.dispatcher) {
                // 标签目录请求（GET /p1/channels/{type}/tags）不属于搜索行为：直接回一份空目录，
                // 既不记进请求序列也不落入下面的搜索分发——否则「面板输入查目录」会污染
                // 所有按 requestedOffsets / capturedBodies 断言的用例。
                if (request.url.encodedPath.startsWith("/p1/channels/")) {
                    onIntercept?.invoke(this@MockEngine, request)?.let { return@withContext it }
                    return@withContext respond(
                        content = """{"data":[],"total":0}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
                val limit = request.url.parameters["limit"]?.toIntOrNull() ?: 20
                // 先记录再拦截：失败/挂起的请求也必须出现在请求序列里，否则无法断言
                // “翻页重试使用了相同的 offset 与 body”。
                // 分类浏览接口（GET /p1/subjects）按页取：页码换算成偏移记入请求序列，
                // 请求「体」记为 "P1?" + 查询串，便于与搜索接口的 JSON 体区分断言。
                val isBrowse = request.url.encodedPath == "/p1/subjects"
                val isP1Search = request.url.encodedPath == "/p1/search/subjects"
                val browseOffset = ((request.url.parameters["page"]?.toIntOrNull() ?: 1) - 1) * SearchRepository.BROWSE_PAGE_SIZE
                requestedOffsets += if (isBrowse) browseOffset else offset
                val raw = request.body.toByteArray().decodeToString()
                val bodyStr = when {
                    isBrowse -> "P1?" + request.url.encodedQuery
                    isP1Search -> bodyJson.encodeToString(SearchRequestDto.serializer(), json.decodeFromString<SearchSubjectDto>(raw).asConditions())
                    else -> raw
                }
                capturedBodies += bodyStr
                searchPaths += request.url.encodedPath

                val intercepted = onIntercept?.invoke(this@MockEngine, request)
                if (intercepted != null) return@withContext intercepted

                if (isBrowse) {
                    val type = request.url.parameters["type"]!!.toInt()
                    val base = if (type == 1) 1000 else 0
                    val size = SearchRepository.BROWSE_PAGE_SIZE
                    val ids = (browseOffset until minOf(browseOffset + size, total)).filter { it !in missing }
                    val pages = (total + size - 1) / size
                    val data = ids.joinToString(",") { id ->
                        """{"id":${base + id},"name":"S${base + id}","nameCN":"条目${base + id}","type":$type,"info":"",
                            "metaTags":[],"rating":{"rank":0,"count":[],"score":0.0,"total":0},"locked":false,"nsfw":false,
                            "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"}}"""
                    }
                    return@withContext respond(
                        content = """{"data":[$data],"total":$pages}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

                val dto = json.decodeFromString<SearchRequestDto>(bodyStr)
                val filter = dto.filter
                val effectiveType = filter?.type?.singleOrNull() ?: 2
                // 优先用完整 DTO 决定基址；否则按类型（书籍 1000 起）。
                val effectiveIdBase = idBaseResolver?.invoke(dto)
                    ?: if (effectiveType == 1) 1000 else 0

                val ids = (offset until minOf(offset + limit, total)).filter { it !in missing }
                if (isP1Search) {
                    val slim = ids.joinToString(",") { id ->
                        val actualId = effectiveIdBase + id
                        """{"id":$actualId,"name":"S$actualId","nameCN":"条目$actualId","type":$effectiveType,"info":"",
                            "metaTags":[],"rating":{"rank":0,"count":[],"score":0.0,"total":0},"locked":false,"nsfw":false,
                            "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"}}"""
                    }
                    return@withContext respond(
                        content = """{"data":[$slim],"total":$total}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                val data = ids.joinToString(",") { id ->
                    val actualId = effectiveIdBase + id
                    """{"id":$actualId,"type":$effectiveType,"name":"S$actualId","name_cn":"条目$actualId","summary":"s",
                        "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
                        "series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,
                        "meta_tags":[],"tags":[],
                        "rating":{"rank":0,"total":0,"count":{},"score":0.0},
                        "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}"""
                }
                val resJson = """{"data":[$data],"total":$total,"limit":$limit,"offset":$offset}"""
                respond(
                    content = resJson,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }

        val client = resources.apiClient(engine)
        val recentSearchDao = FakeRecentSearchDao()
        val subjectDao = FakeSubjectDao()
        val repo = testSearchRepository(resources, SubjectApi(client), NextApi(client), subjectDao, recentSearchDao, testNsfwFilter(resources))
        val open = { criteria: SearchCriteria? ->
            SearchViewModel(repo, tagCatalogFor(client, catalogDao ?: FakeTagCatalogDao()), layoutRepository, testAppTime(System.currentTimeMillis()), StillCollectionMarker(), StillCollectionEditing(), criteria, openFilters = openFilters).also { viewModels += it }
        }
        // 并发取页使各页返回顺序不定，暴露按偏移排序的实时视图（各批偏移递增不重叠，故排序 == 按批推进）。
        return VmFixture(open(seed), SortedLiveView(requestedOffsets), capturedBodies, recentSearchDao, searchPaths, open)
    }

    /**
     * [requestedOffsets] 的实时排序视图：底层仍由假服务端追加，
     * 读取时按偏移排序，使断言与并发页的完成顺序无关。
     */
    private class SortedLiveView(private val backing: List<Int>) : AbstractList<Int>() {
        override val size: Int get() = backing.sorted().size
        override fun get(index: Int): Int = backing.sorted()[index]
        override fun isEmpty(): Boolean = backing.isEmpty()
        override fun contains(element: Int): Boolean = backing.contains(element)
    }

    /** 只用公共标签构造已提交条件（无类型）。 */
    private fun publicTag(value: String) = SearchCriteria(filters = SearchFilters(tags = listOf(TagFilter.Public(value))))

    /** 构造 tags 条件 + 具体类型。 */
    private fun typedTag(value: String, type: SubjectType) = SearchCriteria(
        subjectType = type,
        filters = SearchFilters(tags = listOf(TagFilter.Public(value))),
    )

    @Test
    fun `每个搜索页各有一条提示通道 上一页没看到的提示不会出现在下一页`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel()
        fixture.viewModel.failures.post(Failure.Storage)
        val next = fixture.reopen(null)
        assertNull(next.failures.current.value, "提示属于发出它的那一页：仓库是单例，不能把旧页（或旧账号）的提示留给新页")
    }

    @Test
    fun `搜索首屏加载并支持分页加载更多`() = runBlocking(main.dispatcher) {
        val (viewModel, requestedOffsets) = makeViewModel(total = 2 * page)
        viewModel.onQueryChange("命运石之门")
        viewModel.onSearch()
        awaitUntil("首屏搜索完成") { requestedOffsets.isNotEmpty() && !viewModel.uiState.value.results.isLoading }

        val state1 = viewModel.uiState.value
        assertEquals(page, state1.results.items.size)
        assertEquals(page, state1.results.nextOffset)
        assertTrue(state1.results.hasMore)

        viewModel.loadMore()
        awaitUntil("第二页加载完成") { requestedOffsets.size >= 2 && !viewModel.uiState.value.results.isLoadingMore }

        val state2 = viewModel.uiState.value
        assertEquals(2 * page, state2.results.items.size)
        assertFalse(state2.results.hasMore)
        assertEquals(listOf(0, page), requestedOffsets)
    }

    @Test
    fun `搜索跳条不误判末页且按请求跨度推进`() = runBlocking(main.dispatcher) {
        val (viewModel, requestedOffsets) = makeViewModel(total = 2 * page, missing = setOf(10, 11))
        viewModel.onQueryChange("测试")
        viewModel.onSearch()
        awaitUntil("首屏搜索完成") { requestedOffsets.isNotEmpty() && !viewModel.uiState.value.results.isLoading }

        val state1 = viewModel.uiState.value
        assertEquals(page - 2, state1.results.items.size)
        assertTrue(state1.results.hasMore, "跳条导致返回条数不足时不能误判为末页")
        assertEquals(page, state1.results.nextOffset)

        viewModel.loadMore()
        awaitUntil("第二页加载完成") { requestedOffsets.size >= 2 && !viewModel.uiState.value.results.isLoadingMore }

        val state2 = viewModel.uiState.value
        assertEquals(2 * page - 2, state2.results.items.size)
        assertFalse(state2.results.hasMore)
        assertEquals(listOf(0, page), requestedOffsets)
    }

    @Test
    fun `种子公共标签触发搜索且不写历史`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))
        awaitUntil("标签搜索完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }

        assertEquals(publicTag("治愈"), fixture.viewModel.uiState.value.form.applied)
        assertEquals(20, fixture.viewModel.uiState.value.results.items.size)
        assertEquals(0, fixture.recentSearchDao.store.size, "种子标签不写历史")

        val body = fixture.capturedBodies.last()
        val root = json.parseToJsonElement(body).jsonObject
        assertEquals("heat", root["sort"]?.jsonPrimitive?.content)
        val filter = root["filter"]?.jsonObject
        assertEquals(listOf("治愈"), filter?.get("meta_tags")?.jsonArray?.map { it.jsonPrimitive.content })
        assertNull(filter?.get("tag"))
    }

    @Test
    fun `种子用户标签走tag字段`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(null, "妹控", TagSource.USER))
        awaitUntil("用户标签搜索完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }

        assertEquals(
            SearchCriteria(filters = SearchFilters(tags = listOf(TagFilter.User("妹控")))),
            fixture.viewModel.uiState.value.form.applied,
        )
        val body = fixture.capturedBodies.last()
        val root = json.parseToJsonElement(body).jsonObject
        val filter = root["filter"]?.jsonObject
        assertEquals(listOf("妹控"), filter?.get("tag")?.jsonArray?.map { it.jsonPrimitive.content })
        assertNull(filter?.get("meta_tags"))
    }

    @Test
    fun `移除最后一个标签回空态且旧结果不残留且无条件时isLoading为false`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))
        awaitUntil("标签搜索完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }
        assertEquals(20, fixture.viewModel.uiState.value.results.items.size)

        // 移除该标签 → 无条件 → 回空态
        fixture.viewModel.removeTag(TagFilter.Public("治愈"))
        val state = fixture.viewModel.uiState.value
        assertEquals(SearchCriteria(), state.form.applied)
        assertTrue(state.results.items.isEmpty())
        assertFalse(state.results.isLoading)
    }

    @Test
    fun `输keyword正常写历史且按收藏排`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        fixture.viewModel.onQueryChange("命运石之门")
        fixture.viewModel.onSearch()
        awaitUntil("搜索完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }

        assertEquals(1, fixture.recentSearchDao.store.size)
        assertTrue(fixture.recentSearchDao.store.containsKey("命运石之门"))

        val body = fixture.capturedBodies.last()
        val root = json.parseToJsonElement(body).jsonObject
        // 有关键词时排序固定为收藏（heat）；match 实测不可靠（2026-09-27 实测）
        assertEquals("heat", root["sort"]?.jsonPrimitive?.content)
        assertEquals("命运石之门", root["keyword"]?.jsonPrimitive?.content)
    }

    @Test
    fun `draftQuery不参与请求_翻页仍用旧applied`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 2 * page, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))
        awaitUntil("标签首屏完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }

        // 打了字但不按搜索键
        fixture.viewModel.onQueryChange("测试打字但不提交")
        assertEquals("测试打字但不提交", fixture.viewModel.uiState.value.form.draftQuery)
        assertEquals(publicTag("治愈"), fixture.viewModel.uiState.value.form.applied)

        // 翻页
        fixture.viewModel.loadMore()
        awaitUntil("第二页完成") { fixture.requestedOffsets.size >= 2 && !fixture.viewModel.uiState.value.results.isLoadingMore }

        // 第二页请求仍然是治愈标签，且 keyword 为空
        val secondBody = fixture.capturedBodies.last()
        val root = json.parseToJsonElement(secondBody).jsonObject
        assertEquals("", root["keyword"]?.jsonPrimitive?.content)
        val filter = root["filter"]?.jsonObject
        assertEquals(listOf("治愈"), filter?.get("meta_tags")?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun `切类型保留tag且翻页请求体与首屏一致`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 2 * page, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))
        awaitUntil("标签首屏完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }

        fixture.viewModel.onFilterTypeChange(SubjectType.BOOK)
        awaitUntil("切类型搜索完成") { !fixture.viewModel.uiState.value.results.isLoading }

        val state = fixture.viewModel.uiState.value
        assertEquals(typedTag("治愈", SubjectType.BOOK), state.form.applied)

        // 选了类型、无关键词、无用户标签、默认标注数排序 → 走 p1 搜索，标签进 meta 标签
        assertEquals("/p1/search/subjects", fixture.searchPaths.last())
        val switchDto = searchDto(fixture.capturedBodies.last())
        assertEquals("heat", switchDto.sort)
        assertEquals(listOf(1), switchDto.filter?.type)
        assertEquals(listOf("治愈"), switchDto.filter?.metaTags)
        assertEquals(0, fixture.requestedOffsets.last(), "首屏偏移为 0")

        // 翻页：同一查询，只换偏移
        val before = fixture.requestedOffsets.size
        fixture.viewModel.loadMore()
        awaitUntil("翻页完成") { fixture.requestedOffsets.size >= before + 1 && !fixture.viewModel.uiState.value.results.isLoadingMore }
        val loadMoreDto = searchDto(fixture.capturedBodies.last())
        assertEquals(switchDto.copy(), loadMoreDto, "翻页请求体必须与首屏一致")
        assertEquals(page, fixture.requestedOffsets.last(), "翻页偏移为一页的条数")
    }

    @Test
    fun `submit重置isLoadingMore`() = runBlocking(main.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val fixture = makeViewModel(
            total = 2 * page,
            onIntercept = { request ->
                val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
                if (offset == page) {
                    gate.await()
                }
                null
            },
            seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC),
        )
        awaitUntil("首屏完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }

        fixture.viewModel.loadMore()
        awaitUntil("翻页挂起") { fixture.viewModel.uiState.value.results.isLoadingMore }

        // 挂起时换条件（加年份），submit 必须立即重置 isLoadingMore
        fixture.viewModel.saveAirtime(SearchAirtime(2010, 2010))
        assertFalse(fixture.viewModel.uiState.value.results.isLoadingMore, "submit 必须重置 isLoadingMore")
        gate.complete(Unit)
        Unit
    }

    @Test
    fun `挂起中切条件后新首屏完成且翻页能力恢复`() = runBlocking(main.dispatcher) {
        val gateOldLoadMore = CompletableDeferred<Unit>()
        val oldLoadMoreArrived = CompletableDeferred<Unit>()
        val oldLoadMoreCancelled = CompletableDeferred<Unit>()
        val fixture = makeViewModel(
            total = 2 * page,
            seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC),
            // 新条件（带年份）的结果 ID 从 2000 起，用以区分新旧结果。
            idBaseResolver = { dto -> if (dto.filter?.airDate != null) 2000 else 0 },
            onIntercept = { request ->
                val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
                val filter = conditionsOf(request).filter
                if (offset == page && filter?.metaTags == listOf("治愈") && filter.airDate == null) {
                    oldLoadMoreArrived.complete(Unit)
                    try {
                        gateOldLoadMore.await()
                    } catch (e: CancellationException) {
                        oldLoadMoreCancelled.complete(Unit)
                        throw e
                    }
                }
                null
            }
        )

        // 1. 种子标签「治愈」首屏加载完成
        awaitUntil("治愈首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.size == page }

        // 2. 触发翻页并被 gate 挂起 —— 先证明请求真的进入了旧翻页闸门，再往下走
        fixture.viewModel.loadMore()
        awaitUntil("翻页挂起") { fixture.viewModel.uiState.value.results.isLoadingMore }
        withTimeout(WAIT_BUDGET_MS) { oldLoadMoreArrived.await() }

        // 3. 翻页挂起时追加年份条件
        fixture.viewModel.saveAirtime(SearchAirtime(2010, 2010))
        assertFalse(fixture.viewModel.uiState.value.results.isLoadingMore, "submit 必须重置 isLoadingMore")

        // 4. 等待新条件首屏加载完成（带时间条件走 v0，一页 datedPage 条）
        awaitUntil("新条件首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.size == datedPage }
        assertFalse(fixture.viewModel.uiState.value.results.isLoadingMore)
        assertEquals(
            publicTag("治愈").let { it.copy(filters = it.filters.copy(airtime = SearchAirtime(2010, 2010))) },
            fixture.viewModel.uiState.value.form.applied,
        )
        assertEquals((2000 until 2000 + datedPage).toList(), fixture.viewModel.uiState.value.results.items.map { it.id })

        // 5. 旧翻页必须在 submit 时就被 cancel —— 断言 CancellationException 真实抛出。
        withTimeout(WAIT_BUDGET_MS) { oldLoadMoreCancelled.await() }

        // 6. 再次点击翻页（验证翻页能力恢复，旧翻页锁未残留）
        fixture.viewModel.loadMore()
        awaitUntil("新条件第二页加载完成") { !fixture.viewModel.uiState.value.results.isLoadingMore && fixture.viewModel.uiState.value.results.items.size == 2 * datedPage }

        val state = fixture.viewModel.uiState.value
        assertEquals(2 * datedPage, state.results.items.size)
        assertEquals((2000 until 2000 + 2 * datedPage).toList(), state.results.items.map { it.id })
        assertFalse(state.results.isLoadingMore)
        assertEquals(2 * datedPage, state.results.nextOffset, "游标属新条件")
    }

    @Test
    fun `offset达到total时nextOffset为null且endReached为true`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))
        awaitUntil("首屏完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }

        val state = fixture.viewModel.uiState.value
        assertEquals(20, state.results.items.size)
        assertNull(state.results.nextOffset)
        assertFalse(state.results.hasMore)
    }

    @Test
    fun `翻页至total触顶时请求序列逐页推进到最后一页且从不发出offset1000`() = runBlocking(main.dispatcher) {
        // 真实推进整条翻页序列（0、一页、两页……直到 1000 之前的最后一页），而不是用合成响应一步跳到末页。
        val fixture = makeViewModel(total = 1000, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))

        awaitUntil("首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.isNotEmpty() }
        assertEquals(page, fixture.viewModel.uiState.value.results.nextOffset)
        assertTrue(fixture.viewModel.uiState.value.results.hasMore)

        // 固定推进剩余各页；游标回归时应断言失败，不能无限循环。
        for (offset in page until 1000 step page) {
            assertEquals(offset, fixture.viewModel.uiState.value.results.nextOffset)
            fixture.viewModel.loadMore()
            awaitUntil("offset=$offset 加载完成") {
                fixture.requestedOffsets.last() == offset && !fixture.viewModel.uiState.value.results.isLoadingMore
            }
            assertEquals(offset + page, fixture.viewModel.uiState.value.results.items.size)
        }

        // 请求序列恰为逐页的偏移，止于 1000 之前的最后一页，且从未发出 offset=1000
        val expectedOffsets = (0 until 1000 step page).toList()
        assertEquals(expectedOffsets, fixture.requestedOffsets, "翻页请求序列必须逐页推进且止于 1000 之前")
        assertFalse(fixture.requestedOffsets.contains(1000), "触顶后不得再向服务端发出 offset=1000 请求")

        val state = fixture.viewModel.uiState.value
        assertNull(state.results.nextOffset)
        assertFalse(state.results.hasMore)
        assertFalse(state.results.isLoadingMore)

        // 触顶后再触发 loadMore 应是空转，仍不发请求
        fixture.viewModel.loadMore()
        assertEquals(expectedOffsets, fixture.requestedOffsets, "endReached 后的 loadMore 不得发请求")
    }

    @Test
    fun `首页可见为空但 nextOffset 非空则不进入空态且可续取到末页`() = runBlocking(main.dispatcher) {
        // 共 7 页，前 6 页全在 missing 中（模拟服务端/本地过滤为空），首批取满 6 页 0 条但 nextOffset 指向第 7 页
        val fixture = makeViewModel(
            total = 7 * page,
            seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC),
            missing = (0 until 6 * page).toSet(),
        )

        awaitUntil("首屏加载完成") {
            fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading
        }

        val state1 = fixture.viewModel.uiState.value
        assertEquals(0, state1.results.items.size)
        assertEquals(6 * page, state1.results.nextOffset)
        assertTrue(state1.results.hasMore)
        assertFalse(state1.results.isConfirmedEmpty, "nextOffset 非空时不进入空态")

        // 续取第二批
        fixture.viewModel.loadMore()
        awaitUntil("第二批加载完成") {
            fixture.requestedOffsets.size >= 7 && !fixture.viewModel.uiState.value.results.isLoadingMore
        }

        val state2 = fixture.viewModel.uiState.value
        assertEquals(page, state2.results.items.size)
        assertNull(state2.results.nextOffset)
        assertFalse(state2.results.hasMore)
        assertFalse(state2.results.isConfirmedEmpty)
        assertEquals((0..6).map { it * page }, fixture.requestedOffsets)
    }

    @Test
    fun `连续多页可见为空时能持续续取直至拿到条目且全程不误入空态`() = runBlocking(main.dispatcher) {
        // 带时间条件走 v0（一页 20 条，13 页才放得进 1000 的上限）：前两批（12 页共 240 条）全被过滤，第 13 页才有条目
        val fixture = makeViewModel(
            total = 260,
            seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC)!!.let { it.copy(filters = it.filters.copy(airtime = SearchAirtime(2010, 2010))) },
            missing = (0 until 240).toSet(),
        )

        // 第 1 批（前 6 页全空）
        awaitUntil("第 1 批完成") {
            fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading
        }
        val s1 = fixture.viewModel.uiState.value
        assertEquals(0, s1.results.items.size)
        assertEquals(120, s1.results.nextOffset)
        assertTrue(s1.results.hasMore)
        assertFalse(s1.results.isConfirmedEmpty, "第 1 批为空未到底不得进空态")

        // 第 2 批（再取 6 页全空）
        fixture.viewModel.loadMore()
        awaitUntil("第 2 批完成") {
            fixture.requestedOffsets.size >= 12 && !fixture.viewModel.uiState.value.results.isLoadingMore
        }
        val s2 = fixture.viewModel.uiState.value
        assertEquals(0, s2.results.items.size)
        assertEquals(240, s2.results.nextOffset)
        assertTrue(s2.results.hasMore)
        assertFalse(s2.results.isConfirmedEmpty, "第 2 批为空未到底不得进空态")

        // 第 3 批（第 13 页拿到条目）
        fixture.viewModel.loadMore()
        awaitUntil("第 3 批完成") {
            fixture.requestedOffsets.size >= 13 && !fixture.viewModel.uiState.value.results.isLoadingMore
        }
        val s3 = fixture.viewModel.uiState.value
        assertEquals(20, s3.results.items.size)
        assertNull(s3.results.nextOffset)
        assertFalse(s3.results.hasMore)
        assertFalse(s3.results.isConfirmedEmpty)
        assertEquals(
            listOf(0, 20, 40, 60, 80, 100, 120, 140, 160, 180, 200, 220, 240),
            fixture.requestedOffsets,
        )
    }

    @Test
    fun `全部数据均被过滤为空到底时才判定为空态`() = runBlocking(main.dispatcher) {
        // 共 7 页、全在 missing 中：第 1 批取 6 页为空未到底，第 2 批取到末页到底才进入空态
        val fixture = makeViewModel(
            total = 7 * page,
            seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC),
            missing = (0 until 7 * page).toSet(),
        )

        awaitUntil("第 1 批完成") {
            fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading
        }
        assertTrue(fixture.viewModel.uiState.value.results.hasMore)
        assertFalse(fixture.viewModel.uiState.value.results.isConfirmedEmpty)

        fixture.viewModel.loadMore()
        awaitUntil("第 2 批到底完成") {
            fixture.requestedOffsets.size >= 7 && !fixture.viewModel.uiState.value.results.isLoadingMore
        }
        val state = fixture.viewModel.uiState.value
        assertEquals(0, state.results.items.size)
        assertFalse(state.results.hasMore, "已触底")
        assertTrue(state.results.isConfirmedEmpty, "到底且确实为空时才进入空态")
    }

    @Test
    fun `历史点击保留附加条件且不重复写历史`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))
        awaitUntil("标签搜索完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }
        val requestsAfterSetup = fixture.requestedOffsets.size

        fixture.viewModel.onHistoryClick("clannad")
        awaitUntil("历史项搜索完成") { fixture.requestedOffsets.size >= requestsAfterSetup + 1 && !fixture.viewModel.uiState.value.results.isLoading }

        val state = fixture.viewModel.uiState.value
        assertEquals("clannad", state.form.applied.query)
        assertEquals("clannad", state.form.draftQuery)
        // 历史点击保留标签
        assertEquals(listOf(TagFilter.Public("治愈")), state.form.applied.filters.tags)
        // 且不写历史
        assertEquals(0, fixture.recentSearchDao.store.size)
    }

    @Test
    fun `移除不存在的标签不发请求不闪空`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        fixture.viewModel.onQueryChange("clannad")
        fixture.viewModel.onSearch()
        awaitUntil("关键字搜索完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }
        assertEquals(1, fixture.requestedOffsets.size)
        assertEquals(20, fixture.viewModel.uiState.value.results.items.size)

        // 移除不存在的标签 → 条件不变 → 空转
        fixture.viewModel.removeTag(TagFilter.Public("不存在"))

        assertEquals(1, fixture.requestedOffsets.size)
        assertEquals(20, fixture.viewModel.uiState.value.results.items.size)
        assertFalse(fixture.viewModel.uiState.value.results.isLoading)
        assertEquals(SearchCriteria(query = "clannad"), fixture.viewModel.uiState.value.form.applied)
    }

    @Test
    fun `移除标签后清除该条件并提交新搜索`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))
        awaitUntil("标签搜索完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }
        assertEquals(1, fixture.requestedOffsets.size)

        fixture.viewModel.removeTag(TagFilter.Public("治愈"))
        val state = fixture.viewModel.uiState.value
        assertEquals(SearchCriteria(), state.form.applied)
        assertTrue(state.results.items.isEmpty())
        assertFalse(state.results.isLoading)
    }

    @Test
    fun `onRetry按applied条件重发且置isLoading为true`() = runBlocking(main.dispatcher) {
        var shouldFail = true
        val fixture = makeViewModel(
            total = 20,
            onIntercept = {
                if (shouldFail) {
                    respond(
                        content = "Internal Server Error",
                        status = HttpStatusCode.InternalServerError,
                    )
                } else null
            },
            seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC),
        )

        awaitUntil("错误显示") { fixture.viewModel.uiState.value.results.error != null }
        assertEquals(Failure.Http(500), fixture.viewModel.uiState.value.results.error, "500 应映射成服务端错误，不是读不懂的响应")
        assertEquals(publicTag("治愈"), fixture.viewModel.uiState.value.form.applied)

        shouldFail = false
        fixture.viewModel.onRetry()
        assertTrue(fixture.viewModel.uiState.value.results.isLoading)
        assertNull(fixture.viewModel.uiState.value.results.error)

        awaitUntil("重试成功") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.isNotEmpty() }
        assertEquals(20, fixture.viewModel.uiState.value.results.items.size)
    }

    @Test
    fun `连续onRetry两次竞态保护`() = runBlocking(main.dispatcher) {
        val gateRetry1 = CompletableDeferred<Unit>()
        val gateRetry2 = CompletableDeferred<Unit>()
        val retry1Arrived = CompletableDeferred<Unit>()
        val retry2Arrived = CompletableDeferred<Unit>()
        var retryCount = 0

        val retry1Cancelled = CompletableDeferred<Unit>()
        val fixture = makeViewModel(
            total = 20,
            onIntercept = { request ->
                val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
                if (offset == 0) {
                    retryCount++
                    if (retryCount == 2) {
                        retry1Arrived.complete(Unit)
                        try {
                            gateRetry1.await()
                        } catch (e: CancellationException) {
                            retry1Cancelled.complete(Unit)
                            throw e
                        }
                    } else if (retryCount == 3) {
                        retry2Arrived.complete(Unit)
                        gateRetry2.await()
                    }
                }
                null
            },
            seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC),
        )

        awaitUntil("初始搜索完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.isNotEmpty() }
        assertEquals(1, retryCount)

        // 第一次重试发出并挂起 (retryCount == 2)
        fixture.viewModel.onRetry()
        withTimeout(WAIT_BUDGET_MS) { retry1Arrived.await() }

        // 第二次重试发出并挂起 (retryCount == 3)
        fixture.viewModel.onRetry()
        withTimeout(WAIT_BUDGET_MS) { retry2Arrived.await() }

        // 释放第二次重试并等待其完成
        gateRetry2.complete(Unit)
        awaitUntil("第二次重试完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.isNotEmpty() }
        assertEquals(20, fixture.viewModel.uiState.value.results.items.size)

        // 释放第一次重试（旧重试到达但已被 cancel 且代次过期）
        gateRetry1.complete(Unit)
        awaitUntil("旧重试已被取消") { retry1Cancelled.isCompleted }
        assertTrue(retry1Cancelled.isCompleted, "新重试必须取消正在进行的旧重试")

        val state = fixture.viewModel.uiState.value
        assertEquals(20, state.results.items.size)
        assertFalse(state.results.isLoading)
        assertNull(state.results.error)
    }

    @Test
    fun `旧首屏被新条件取消且不污染新结果`() = runBlocking(main.dispatcher) {
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val reqAArrived = CompletableDeferred<Unit>()
        var reqACancelled = false

        val fixture = makeViewModel(
            total = 20,
            onIntercept = { request ->
                val info = reqInfo(request)
                // 门控「治愈」标签请求（区分准备阶段可能产生的其他球）
                if (info.offset == 0 && info.metaTags == listOf("治愈")) {
                    if (info.type == 2) {
                        reqAArrived.complete(Unit)
                        try {
                            gateA.await()
                        } catch (e: CancellationException) {
                            reqACancelled = true
                            throw e
                        }
                    } else if (info.type == 1) {
                        gateB.await()
                    }
                }
                null
            },
            seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC),
        )

        // 1. 书籍准备：种子「治愈」后切 ANIME 类型 → 被 gateA 挂住
        withContext(Dispatchers.Main) {
            fixture.viewModel.onFilterTypeChange(SubjectType.ANIME)
        }
        withTimeout(WAIT_BUDGET_MS) { reqAArrived.await() }

        // 2. 切到书籍 (type=[1]) -> gateB 挂住
        withContext(Dispatchers.Main) {
            fixture.viewModel.onFilterTypeChange(SubjectType.BOOK)
        }

        // 3. gateB 释放，等待书籍结果成功写回
        gateB.complete(Unit)
        awaitUntil("新请求完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.isNotEmpty() }
        assertEquals(typedTag("治愈", SubjectType.BOOK), fixture.viewModel.uiState.value.form.applied)
        assertTrue(fixture.viewModel.uiState.value.results.items.all { it.type == SubjectType.BOOK })

        // 4. gateA 释放（旧请求已被 cancel，即使恢复也不得写回）
        gateA.complete(Unit)
        awaitUntil("旧请求协程已取消") { reqACancelled }
        assertTrue(reqACancelled, "旧搜索请求必须在 submit 时被 cancel 取消")

        // 5. 校验结果仍是书籍的，未被动画旧请求污染
        val state = fixture.viewModel.uiState.value
        assertEquals(typedTag("治愈", SubjectType.BOOK), state.form.applied)
        assertTrue(state.results.items.isNotEmpty())
        assertTrue(state.results.items.all { it.type == SubjectType.BOOK })
        assertTrue(state.results.items.all { it.id >= 1000 })
    }

    @Test
    fun `旧翻页切组合条件必须取消且不污染新结果`() = runBlocking(main.dispatcher) {
        val gateOldLoadMore = CompletableDeferred<Unit>()
        val oldLoadMoreArrived = CompletableDeferred<Unit>()
        var oldLoadMoreCancelled = false

        val fixture = makeViewModel(
            total = 240,
            onIntercept = { request ->
                val info = reqInfo(request)
                // 动画「治愈」的第二页（搜索按偏移分页，第二页偏移为一页的大小）
                if (info.offset == SearchRepository.P1_SEARCH_PAGE_SIZE && info.metaTags == listOf("治愈") && info.type == 2) {
                    oldLoadMoreArrived.complete(Unit)
                    try {
                        gateOldLoadMore.await()
                    } catch (e: CancellationException) {
                        oldLoadMoreCancelled = true
                        throw e
                    }
                }
                null
            },
            seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC),
        )

        // 1. 动画 + 治愈标签首屏完成（默认标注数排序走 p1 搜索，一页 100 条，type == ANIME）
        val pageSize = SearchRepository.P1_SEARCH_PAGE_SIZE
        withContext(Dispatchers.Main) {
            fixture.viewModel.onFilterTypeChange(SubjectType.ANIME)
        }
        awaitUntil("首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.size == pageSize }
        assertTrue(fixture.viewModel.uiState.value.results.items.all { it.type == SubjectType.ANIME })

        // 2. 触发翻页并被 gateOldLoadMore 挂起
        fixture.viewModel.loadMore()
        withTimeout(WAIT_BUDGET_MS) { oldLoadMoreArrived.await() }

        // 3. 切条件为书籍 (type=[1])，新首屏完成
        withContext(Dispatchers.Main) {
            fixture.viewModel.onFilterTypeChange(SubjectType.BOOK)
        }
        awaitUntil("书籍首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.isNotEmpty() }
        assertFalse(fixture.viewModel.uiState.value.results.isLoadingMore)
        assertEquals(typedTag("治愈", SubjectType.BOOK), fixture.viewModel.uiState.value.form.applied)
        assertEquals(pageSize, fixture.viewModel.uiState.value.results.items.size)
        assertTrue(fixture.viewModel.uiState.value.results.items.all { it.type == SubjectType.BOOK })

        // 4. 释放旧动画翻页
        gateOldLoadMore.complete(Unit)
        awaitUntil("旧翻页处理完毕") { oldLoadMoreCancelled }
        assertTrue(oldLoadMoreCancelled, "旧翻页请求必须在 submit 时被 cancel 取消")

        // 5. 断言结果无旧动画条目、isLoadingMore == false、nextOffset 属新条件
        val state = fixture.viewModel.uiState.value
        assertEquals(pageSize, state.results.items.size)
        assertTrue(state.results.items.all { it.type == SubjectType.BOOK })
        assertTrue(state.results.items.all { it.id >= 1000 })
        assertFalse(state.results.isLoadingMore)
        assertEquals(pageSize, state.results.nextOffset)
    }

    // --- 回归 ---

    @Test
    fun `重复排序不重搜`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        fixture.viewModel.onFilterTypeChange(SubjectType.ANIME)
        fixture.viewModel.onSortChange(SearchSort.RANK)
        awaitUntil("首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.requestedOffsets.isNotEmpty() }
        val count = fixture.requestedOffsets.size

        fixture.viewModel.onSortChange(SearchSort.RANK)
        assertEquals(count, fixture.requestedOffsets.size, "重复排序不得重搜")
        assertEquals(20, fixture.viewModel.uiState.value.results.items.size)
    }

    @Test
    fun `切到别的排序再切回来，取过的结果原样回来，不重搜`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        fixture.viewModel.onFilterTypeChange(SubjectType.ANIME)
        fixture.viewModel.onSortChange(SearchSort.RANK)
        awaitUntil("按排名完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.requestedOffsets.isNotEmpty() }
        val ranked = fixture.viewModel.uiState.value.results
        val count = fixture.requestedOffsets.size

        fixture.viewModel.onSortChange(SearchSort.COLLECTS)
        awaitUntil("按收藏完成") { fixture.requestedOffsets.size == count + 1 && !fixture.viewModel.uiState.value.results.isLoading }

        fixture.viewModel.onSortChange(SearchSort.RANK)
        assertEquals(ranked, fixture.viewModel.uiState.value.results, "切回排名应当当场是上次的结果")
        assertEquals(count + 1, fixture.requestedOffsets.size, "切回取过的排序不得重搜")
    }

    @Test
    fun `单选维度再点同一取值即取消_回落到无维度`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        fixture.viewModel.onFilterTypeChange(SubjectType.ANIME)
        fixture.viewModel.saveDimension(SearchDimension.CATEGORY, animeTv)
        awaitUntil("首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.requestedOffsets.isNotEmpty() }
        val count = fixture.requestedOffsets.size

        // 同一取值再点一次 = 取消该维度（唯一更新点的单选语义），条件变了所以要重搜
        fixture.viewModel.saveDimension(SearchDimension.CATEGORY, animeTv)
        awaitUntil("取消后重搜完成") { fixture.requestedOffsets.size > count && !fixture.viewModel.uiState.value.results.isLoading }
        assertTrue(fixture.viewModel.uiState.value.form.applied.filters.dimensions.isEmpty())
        assertEquals(20, fixture.viewModel.uiState.value.results.items.size)
    }

    @Test
    fun `面板未改动直接保存_零请求零generation变化`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        fixture.viewModel.onFilterTypeChange(SubjectType.ANIME)
        fixture.viewModel.saveDimension(SearchDimension.CATEGORY, animeTv)
        awaitUntil("首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.requestedOffsets.isNotEmpty() }
        val count = fixture.requestedOffsets.size
        val gen = fixture.viewModel.uiState.value.generation

        fixture.viewModel.onFiltersOpen()
        fixture.viewModel.onFiltersSave()

        assertEquals(count, fixture.requestedOffsets.size, "未改动保存不得发请求")
        assertEquals(gen, fixture.viewModel.uiState.value.generation, "未改动保存不得增加 generation")
    }

    @Test
    fun `保存非法时间不改变已提交条件_面板关闭`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))
        awaitUntil("首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.requestedOffsets.isNotEmpty() }
        val count = fixture.requestedOffsets.size
        val before = fixture.viewModel.uiState.value.form

        // 越界月份
        fixture.viewModel.saveAirtime(SearchAirtime(2020, 13))

        // 已提交条件不变；界面已先收起面板，故草稿一并丢弃（面板关闭）
        assertEquals(before, fixture.viewModel.uiState.value.form, "非法范围不得改变已提交条件")
        assertEquals(count, fixture.requestedOffsets.size, "非法范围不得发请求")
        assertTrue(fixture.viewModel.uiState.value.validationErrors.isNotEmpty())
    }

    @Test
    fun `再打开同一组条件_第一页先用上次存下的_不发请求_续取接着存下的游标`() = runBlocking(main.dispatcher) {
        val seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC)
        val fixture = makeViewModel(total = 2 * page, seed = seed)
        awaitUntil("首屏完成") { fixture.viewModel.uiState.value.results.items.isNotEmpty() }
        assertEquals(1, fixture.requestedOffsets.size)
        val first = fixture.viewModel.uiState.value.results

        val again = fixture.reopen(seed)
        awaitUntil("再打开的首屏落定") { again.uiState.value.results.items.isNotEmpty() }
        assertEquals(first.items.map { it.id }, again.uiState.value.results.items.map { it.id })
        assertEquals(first.nextOffset, again.uiState.value.results.nextOffset)
        assertEquals(1, fixture.requestedOffsets.size, "新鲜期内再打开不该再发请求")

        again.loadMore()
        awaitUntil("续取完成") { fixture.requestedOffsets.size >= 2 && !again.uiState.value.results.isLoadingMore }
        assertEquals(listOf(0, page), fixture.requestedOffsets)
    }

    @Test
    fun `显式相同query重搜`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        fixture.viewModel.onQueryChange("eva")
        fixture.viewModel.onSearch()
        awaitUntil("首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.requestedOffsets.isNotEmpty() }
        val count = fixture.requestedOffsets.size

        // 显式再次提交相同条件，仍应重搜
        fixture.viewModel.onSearch()
        awaitUntil("重搜完成") { fixture.requestedOffsets.size >= count + 1 }
        assertEquals(count + 1, fixture.requestedOffsets.size)
    }

    @Test
    fun `清除输入保留筛选`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        fixture.viewModel.onQueryChange("eva")
        fixture.viewModel.onSearch()
        awaitUntil("首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.requestedOffsets.isNotEmpty() }

        fixture.viewModel.onFilterTypeChange(SubjectType.ANIME)
        fixture.viewModel.saveDimension(SearchDimension.CATEGORY, animeTv)
        awaitUntil("维度搜索完成") { !fixture.viewModel.uiState.value.results.isLoading }

        fixture.viewModel.onQueryClear()
        val state = fixture.viewModel.uiState.value
        assertEquals("", state.form.applied.query)
        assertEquals("", state.form.draftQuery)
        // 维度与类型保留
        assertEquals(SubjectType.ANIME, state.form.applied.subjectType)
        assertEquals(mapOf(SearchDimension.CATEGORY to listOf(animeTv)), state.form.applied.filters.dimensions)
    }

    @Test
    fun `空态retry零请求且不永远loading`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        // 初始空态
        fixture.viewModel.onRetry()
        assertEquals(0, fixture.requestedOffsets.size, "空态 retry 不得发请求")
        assertFalse(fixture.viewModel.uiState.value.results.isLoading, "空态 retry 不得永远 loading")
    }

    // ==================== 完整条件快照与竞态 ====================

    /** 动画·分类·TV：从词典取，不在测试里另写一份取值。 */
    /** 经面板建立条件：打开 → 改草稿 → 保存（与界面同一条路径，每次保存最多触发一次搜索）。 */
    private fun SearchViewModel.saveAirtime(airtime: SearchAirtime?) {
        onFiltersOpen()
        onDraftAirtimeChange(airtime)
        onFiltersSave()
    }

    private fun SearchViewModel.saveDimension(dimension: SearchDimension, option: SearchDimensionOption) {
        onFiltersOpen()
        onDraftDimensionToggle(dimension, option)
        onFiltersSave()
    }

    private fun SearchViewModel.removeTag(tag: TagFilter) {
        onFiltersOpen()
        onDraftTagRemove(tag)
        onFiltersSave()
    }

    private val animeTv = searchDimensionsFor(SubjectType.ANIME)
        .single { it.dimension == SearchDimension.CATEGORY }.options.single { it.label == "TV" }

    /**
     * 完整条件 A：eva / ANIME / 默认排序 / 分类TV / 种子公共标签治愈 / 2010 年。
     * 结果 ID 用 0..39。标签只能经路由种子进入（面板不提供标签编辑），故 A 只含一个种子标签。
     */
    private val criteriaA = SearchCriteria(
        query = "eva",
        subjectType = SubjectType.ANIME,
        filters = SearchFilters(
            dimensions = mapOf(SearchDimension.CATEGORY to listOf(animeTv)),
            tags = listOf(TagFilter.Public("治愈")),
            airtime = SearchAirtime(2010, 2010),
        ),
    )

    private fun makeABViewModel(
        total: Int = 40,
        onIntercept: (suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?)? = null,
    ) = makeViewModel(
        total = total,
        seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC),
        onIntercept = onIntercept,
    )


    /**
     * 逐事件建立完整条件 A（标签来自 [makeABViewModel] 的种子）。每个表单事件都会触发一次搜索，
     * 故 setup 会产生多次 offset=0 请求；
     * 结束时 applied 必须等于 [criteriaA]。需要精确请求序列的用例自行记录 setupCount。
     */
    private suspend fun VmFixture.setUpCriteriaA() {
        viewModel.onQueryChange("eva")
        viewModel.onSearch()
        viewModel.onFilterTypeChange(SubjectType.ANIME)
        viewModel.saveDimension(SearchDimension.CATEGORY, animeTv)
        viewModel.saveAirtime(SearchAirtime(2010, 2010))
        awaitUntil("条件A就绪") {
            !viewModel.uiState.value.results.isLoading && viewModel.uiState.value.form.applied == criteriaA
        }
    }

    @Test
    fun `草稿隔离_提交A后输入草稿B改条件翻页仍用A`() = runBlocking(main.dispatcher) {
        val fixture = makeABViewModel(total = 40)
        fixture.setUpCriteriaA()

        // 输入未提交的 B 词
        fixture.viewModel.onQueryChange("未提交B")
        // 改年份
        fixture.viewModel.saveAirtime(SearchAirtime(2011))
        awaitUntil("改年份完成") { !fixture.viewModel.uiState.value.results.isLoading }
        // 翻页
        val before = fixture.requestedOffsets.size
        fixture.viewModel.loadMore()
        awaitUntil("翻页完成") { !fixture.viewModel.uiState.value.results.isLoadingMore && fixture.requestedOffsets.size > before }

        // 发出的 keyword 始终是 A 的（第一页 eva；草稿从未进入请求）
        for (body in fixture.capturedBodies) {
            val dto = json.decodeFromString<SearchRequestDto>(body)
            assertTrue(dto.keyword == "eva", "请求 keyword 必须始终为 eva，实际 ${dto.keyword}")
            assertFalse(body.contains("未提交B"), "草稿词不得进入请求")
        }
        // 草稿原样保留
        assertEquals("未提交B", fixture.viewModel.uiState.value.form.draftQuery)
    }

    @Test
    fun `显式提交_用新词与原全部附加条件并保存新词历史一次`() = runBlocking(main.dispatcher) {
        val fixture = makeABViewModel(total = 40)
        fixture.setUpCriteriaA()
        awaitUntil("「eva」写进历史") { fixture.recentSearchDao.store.containsKey("eva") }
        val historyBefore = fixture.recentSearchDao.store.size

        fixture.viewModel.onQueryChange("新词")
        fixture.viewModel.onSearch()
        awaitUntil("新词搜索完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.form.applied.query == "新词" }

        val body = fixture.capturedBodies.last()
        val dto = json.decodeFromString<SearchRequestDto>(body)
        assertEquals("新词", dto.keyword)
        // 附加条件保留
        assertEquals(listOf(2), dto.filter?.type)
        // 维度翻译在前、种子公共标签在后
        assertEquals(listOf("TV", "治愈"), dto.filter?.metaTags)
        assertNull(dto.filter?.tag)
        assertEquals(listOf(">=2010-01-01", "<=2010-12-31"), dto.filter?.airDate)
        // 新词历史只多一条
        awaitUntil("新词写进历史") { fixture.recentSearchDao.store.containsKey("新词") }
        assertEquals(historyBefore + 1, fixture.recentSearchDao.store.size)
    }

    @Test
    fun `首屏重试_用失败时相同的完整body且不保存草稿`() = runBlocking(main.dispatcher) {
        var shouldFail = true
        val fixture = makeABViewModel(
            total = 40,
            onIntercept = {
                if (shouldFail) respond("Internal Server Error", HttpStatusCode.InternalServerError) else null
            },
        )
        fixture.setUpCriteriaA()
        awaitUntil("首屏失败") { fixture.viewModel.uiState.value.results.error != null && fixture.viewModel.uiState.value.form.applied == criteriaA }
        assertEquals(Failure.Http(500), fixture.viewModel.uiState.value.results.error, "500 应映射成服务端错误，不是读不懂的响应")
        awaitUntil("「eva」写进历史") { fixture.recentSearchDao.store.containsKey("eva") }
        val historyBefore = fixture.recentSearchDao.store.toMap()
        val failedBody = fixture.capturedBodies.last()

        // 改草稿但不动 applied
        fixture.viewModel.onQueryChange("draft不该进请求")
        shouldFail = false
        fixture.viewModel.onRetry()
        awaitUntil("重试成功") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.isNotEmpty() }

        val retryBody = fixture.capturedBodies.last()
        assertEquals(failedBody, retryBody, "重试 body 必须与失败时完全相同")
        assertFalse(retryBody.contains("draft不该进请求"))
        // 重试不写历史
        assertEquals(historyBefore, fixture.recentSearchDao.store.toMap())
    }

    @Test
    fun `翻页重试_列表保留相同offset与完整body无重复`() = runBlocking(main.dispatcher) {
        var failLoadMore = true
        val fixture = makeABViewModel(
            total = 40,
            onIntercept = { request ->
                val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
                if (offset == 20 && failLoadMore) {
                    respond("Internal Server Error", HttpStatusCode.InternalServerError)
                } else null
            },
        )
        fixture.setUpCriteriaA()
        awaitUntil("A 首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.size == 20 }
        val firstPageBody = fixture.capturedBodies.last()
        val firstPageIds = fixture.viewModel.uiState.value.results.items.map { it.id }

        fixture.viewModel.loadMore()
        awaitUntil("翻页失败") { fixture.viewModel.uiState.value.results.loadMoreError != null }
        assertEquals(Failure.Http(500), fixture.viewModel.uiState.value.results.loadMoreError, "500 应映射成服务端错误，不是读不懂的响应")
        assertEquals(20, fixture.viewModel.uiState.value.results.items.size, "翻页失败列表保留")
        val failedOffset = fixture.requestedOffsets.last()

        failLoadMore = false
        fixture.viewModel.loadMore()
        awaitUntil("翻页重试成功") { fixture.viewModel.uiState.value.results.items.size == 40 && !fixture.viewModel.uiState.value.results.isLoadingMore }

        // 相同 offset（20）与完整 body
        val retryBody = fixture.capturedBodies.last()
        val retryOffset = fixture.requestedOffsets.last()
        assertEquals(failedOffset, retryOffset)
        // 第二页 body 的 filter/type/sort 与首屏一致（仅 URL offset 不同）
        val dtoFirst = json.decodeFromString<SearchRequestDto>(firstPageBody)
        val dtoRetry = json.decodeFromString<SearchRequestDto>(retryBody)
        assertEquals(dtoFirst.filter, dtoRetry.filter)
        assertEquals(dtoFirst.sort, dtoRetry.sort)
        assertEquals(dtoFirst.keyword, dtoRetry.keyword)
        // 无重复条目
        assertEquals(40, fixture.viewModel.uiState.value.results.items.map { it.id }.distinct().size)
        assertTrue(fixture.viewModel.uiState.value.results.items.map { it.id }.containsAll(firstPageIds))
    }

    @Test
    fun `重选同一维度取值_单选语义下等同取消`() = runBlocking(main.dispatcher) {
        val fixture = makeABViewModel(total = 40)
        fixture.setUpCriteriaA()
        val count = fixture.requestedOffsets.size

        fixture.viewModel.saveDimension(SearchDimension.CATEGORY, animeTv)

        // CATEGORY 是单选维度：再点同一取值即取消该维度，条件变化触发一次重搜
        awaitUntil("取消后重搜完成") { fixture.requestedOffsets.size > count && !fixture.viewModel.uiState.value.results.isLoading }
        val dims = fixture.viewModel.uiState.value.form.applied.filters.dimensions
        assertTrue(dims[SearchDimension.CATEGORY].isNullOrEmpty(), "单选维度再点同一取值应被取消")
    }

    @Test
    fun `清到空态_删最后一个标签取消旧请求且状态归位`() = runBlocking(main.dispatcher) {
        val fixture = makeABViewModel(total = 40)
        // 只有种子标签、无类型、无显式排序
        awaitUntil("首屏完成") { !fixture.viewModel.uiState.value.results.isLoading && fixture.viewModel.uiState.value.results.items.isNotEmpty() }

        fixture.viewModel.removeTag(TagFilter.Public("治愈"))
        val state = fixture.viewModel.uiState.value
        assertEquals(SearchCriteria(), state.form.applied)
        assertTrue(state.results.items.isEmpty())
        assertFalse(state.results.isLoading)
        assertFalse(state.results.isLoadingMore)
    }

    @Test
    fun `种子只注入一次_移除后不复活且不重置其他条件`() = runBlocking(main.dispatcher) {
        val seeded = makeViewModel(total = 20, seed = tagSearchCriteria(null, "治愈", TagSource.PUBLIC))
        awaitUntil("种子首屏完成") { !seeded.viewModel.uiState.value.results.isLoading && seeded.viewModel.uiState.value.results.items.isNotEmpty() }
        assertEquals(
            SearchCriteria(filters = SearchFilters(tags = listOf(TagFilter.Public("治愈")))),
            seeded.viewModel.uiState.value.form.applied,
        )

        // 加个关键词并移除种子标签
        seeded.viewModel.onQueryChange("later")
        seeded.viewModel.onSearch()
        awaitUntil("加词完成") { !seeded.viewModel.uiState.value.results.isLoading }
        seeded.viewModel.removeTag(TagFilter.Public("治愈"))
        awaitUntil("移除完成") { !seeded.viewModel.uiState.value.results.isLoading }
        assertEquals("later", seeded.viewModel.uiState.value.form.applied.query)
        assertEquals(emptyList<TagFilter>(), seeded.viewModel.uiState.value.form.applied.filters.tags)

        // 再次读取状态，种子不复活
        assertEquals(emptyList<TagFilter>(), seeded.viewModel.uiState.value.form.applied.filters.tags)
    }

    @Test
    fun `非法种子_validationErrors非空且零HTTP`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(null, "-治愈", TagSource.PUBLIC))
        // 给调度一点时间
        awaitUntil("非法种子处理完毕") { fixture.viewModel.uiState.value.validationErrors.isNotEmpty() }
        assertTrue(fixture.viewModel.uiState.value.validationErrors.isNotEmpty())
        assertEquals(0, fixture.requestedOffsets.size, "非法种子不得发 HTTP")
        assertFalse(fixture.viewModel.uiState.value.results.isLoading)
    }

    @Test
    fun `触顶_服务端total超过1000时完整A条件每页20请求序列止于980`() = runBlocking(main.dispatcher) {
        // total 是服务端给的值：超过 1000 时也只翻到 1000 之前（offset 到 1000 服务端不再给结果）
        val fixture = makeABViewModel(total = 5000)
        fixture.setUpCriteriaA()
        assertEquals(20, fixture.viewModel.uiState.value.results.nextOffset)

        // setup 期间的请求全是 offset=0；分页只从 20 开始。
        val setupCount = fixture.requestedOffsets.size
        assertTrue(fixture.requestedOffsets.all { it == 0 }, "setup 期间只应有 offset=0 请求")
        val appliedBody = fixture.capturedBodies.last()

        for (offset in 20 until 1000 step 20) {
            assertEquals(offset, fixture.viewModel.uiState.value.results.nextOffset)
            fixture.viewModel.loadMore()
            awaitUntil("offset=$offset 完成") {
                fixture.requestedOffsets.last() == offset && !fixture.viewModel.uiState.value.results.isLoadingMore
            }
        }

        val pageOffsets = fixture.requestedOffsets.drop(setupCount)
        assertEquals((20 until 1000 step 20).toList(), pageOffsets, "分页序列必须止于 980")
        assertFalse(fixture.requestedOffsets.contains(1000), "触顶后不得发出 offset=1000")
        val pageBodies = fixture.capturedBodies.drop(setupCount)
        assertEquals(List(pageOffsets.size) { appliedBody }, pageBodies, "每页都沿用应用时的完整条件，仅 URL offset 不同")

        assertNull(fixture.viewModel.uiState.value.results.nextOffset, "offset 到 1000 即到底，不管 total 多大")
        assertFalse(fixture.viewModel.uiState.value.results.hasMore)

        // 末页后零增发
        val finalCount = fixture.requestedOffsets.size
        fixture.viewModel.loadMore()
        assertEquals(finalCount, fixture.requestedOffsets.size, "末页后不得再发请求")
    }

    /** 两个接口统一的请求摘要：类型、分类标签（去掉规避用的重复）、偏移（分类浏览由页码换算）。 */
    private data class ReqInfo(val type: Int?, val metaTags: List<String>, val offset: Int)

    private suspend fun reqInfo(request: HttpRequestData): ReqInfo =
        if (request.url.encodedPath == "/p1/subjects") {
            val p = request.url.parameters
            ReqInfo(
                type = p["type"]?.toInt(),
                metaTags = p.getAll("tags").orEmpty().distinct(),
                offset = ((p["page"]?.toIntOrNull() ?: 1) - 1) * SearchRepository.BROWSE_PAGE_SIZE,
            )
        } else {
            val filter = conditionsOf(request).filter
            ReqInfo(filter?.type?.singleOrNull(), filter?.metaTags.orEmpty(), request.url.parameters["offset"]?.toIntOrNull() ?: 0)
        }

    /** 分类浏览请求记录为 "P1?查询串"；解析回参数。 */
    private fun browseParams(body: String): Parameters {
        assertTrue(body.startsWith("P1?"), "应为分类浏览请求，实际：$body")
        return parseQueryString(body.removePrefix("P1?"))
    }

    /** 搜索请求记录为 JSON 体（p1 搜索已按 v0 字段名记下，见 [asConditions]）；解析回 DTO。 */
    private fun searchDto(body: String): SearchRequestDto {
        assertFalse(body.startsWith("P1?"), "应为关键词搜索请求（不是分类浏览），实际：$body")
        return json.decodeFromString(body)
    }

    @Test
    fun `详情页分类种子带类型_默认按标注数走关键词搜索_单标签只发一次`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(SubjectType.ANIME, "漫画改", TagSource.PUBLIC))
        awaitUntil("分类种子搜索完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }

        val applied = fixture.viewModel.uiState.value.form.applied
        assertEquals(SubjectType.ANIME, applied.subjectType)
        assertEquals("漫画改", applied.filters.dimensions[SearchDimension.SOURCE]?.single()?.metaTag)
        assertTrue(applied.filters.tags.isEmpty())
        // 默认排序是标注数，走关键词搜索：单个 meta 标签原样发一次
        val dto = searchDto(fixture.capturedBodies.single())
        assertEquals("heat", dto.sort)
        assertEquals(listOf(2), dto.filter?.type)
        assertEquals(listOf("漫画改"), dto.filter?.metaTags)
        assertNull(dto.filter?.tag)
    }

    @Test
    fun `详情页标签种子带类型_默认标注数走关键词搜索_可切到日期与名称走分类浏览`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = tagSearchCriteria(SubjectType.ANIME, "菅野洋子", TagSource.USER))
        awaitUntil("标签种子搜索完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }
        val applied = fixture.viewModel.uiState.value.form.applied
        assertEquals(SubjectType.ANIME, applied.subjectType)
        assertEquals(listOf(TagFilter.User("菅野洋子")), applied.filters.tags)
        assertTrue(applied.isTagBrowse())
        assertEquals(
            setOf(SearchSort.RANK, SearchSort.COLLECTS, SearchSort.DATE, SearchSort.TITLE),
            applied.availableSorts(),
        )
        assertEquals(SearchSort.COLLECTS, applied.effectiveSort())
        assertEquals(R.string.search_sort_tagged, applied.effectiveSort().labelRes(isTagBrowse = true))

        // 标注数 → 关键词搜索：用户标签进 tag
        val firstDto = searchDto(fixture.capturedBodies.first())
        assertEquals("heat", firstDto.sort)
        assertEquals(listOf(2), firstDto.filter?.type)
        assertEquals(listOf("菅野洋子"), firstDto.filter?.tag)
        assertNull(firstDto.filter?.metaTags)

        // 切到日期：p1 标签浏览
        fixture.viewModel.onSortChange(SearchSort.DATE)
        awaitUntil("按日期完成") { fixture.requestedOffsets.size == 2 && !fixture.viewModel.uiState.value.results.isLoading }
        val dateParams = browseParams(fixture.capturedBodies.last())
        assertEquals("date", dateParams["sort"])
        assertEquals("subject", dateParams["tagsCat"])
        assertEquals(listOf("菅野洋子"), dateParams.getAll("tags"))

        // 切到名称：p1 标签浏览
        fixture.viewModel.onSortChange(SearchSort.TITLE)
        awaitUntil("按名称完成") { fixture.requestedOffsets.size == 3 && !fixture.viewModel.uiState.value.results.isLoading }
        val titleParams = browseParams(fixture.capturedBodies.last())
        assertEquals("title", titleParams["sort"])
        assertEquals("subject", titleParams["tagsCat"])
        assertEquals(listOf("菅野洋子"), titleParams.getAll("tags"))
    }

    @Test
    fun `探索页分类浏览入口_默认排序走关键词搜索_条件落在filter`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = SearchCriteria(subjectType = SubjectType.GAME))
        awaitUntil("浏览完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }
        assertEquals(SearchCriteria(subjectType = SubjectType.GAME), fixture.viewModel.uiState.value.form.applied)
        // 默认排序是标注数（COLLECTS），走关键词搜索：无条件时 filter 只有 type
        val dto = searchDto(fixture.capturedBodies.single())
        assertEquals("heat", dto.sort)
        assertEquals(listOf(4), dto.filter?.type)
        assertNull(dto.filter?.tag)
        assertNull(dto.filter?.metaTags)
    }

    @Test
    fun `探索页分类浏览切热度仍走p1浏览`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = SearchCriteria(subjectType = SubjectType.GAME))
        awaitUntil("浏览完成") { fixture.requestedOffsets.isNotEmpty() && !fixture.viewModel.uiState.value.results.isLoading }

        fixture.viewModel.onSortChange(SearchSort.TRENDS)
        awaitUntil("按热度完成") { fixture.requestedOffsets.size == 2 && !fixture.viewModel.uiState.value.results.isLoading }
        val params = browseParams(fixture.capturedBodies.last())
        assertEquals("4", params["type"])
        assertEquals("trends", params["sort"])
        assertNull(params["tags"])
    }

    @Test
    fun `浏览换排序只发一次_输关键词后按收藏_清掉关键词恢复原排序`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20, seed = SearchCriteria(subjectType = SubjectType.ANIME))
        val vm = fixture.viewModel
        awaitUntil("浏览完成") { fixture.requestedOffsets.isNotEmpty() && !vm.uiState.value.results.isLoading }

        vm.onSortChange(SearchSort.TRENDS)
        awaitUntil("按热度完成") { fixture.requestedOffsets.size == 2 && !vm.uiState.value.results.isLoading }
        assertEquals("trends", browseParams(fixture.capturedBodies.last())["sort"])
        val trending = vm.uiState.value.results

        vm.onQueryChange("eva")
        vm.onSearch()
        awaitUntil("关键词搜索完成") { fixture.requestedOffsets.size == 3 && !vm.uiState.value.results.isLoading }
        val dto = json.decodeFromString<SearchRequestDto>(fixture.capturedBodies.last())
        assertEquals("heat", dto.sort)
        assertEquals("eva", dto.keyword)

        // 清掉关键词回到按热度的浏览：刚取过，当场原样回来，不再发请求
        vm.onQueryClear()
        assertEquals(SearchSort.TRENDS, vm.uiState.value.form.applied.effectiveSort())
        assertEquals(trending, vm.uiState.value.results)
        assertEquals(3, fixture.requestedOffsets.size)
    }

    @Test
    fun `面板改草稿不发请求_关闭丢弃_保存只发一次`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(total = 20)
        val vm = fixture.viewModel
        vm.onFilterTypeChange(SubjectType.ANIME)
        awaitUntil("类型搜索完成") { fixture.requestedOffsets.isNotEmpty() && !vm.uiState.value.results.isLoading }
        val baseline = fixture.requestedOffsets.size
        val applied = vm.uiState.value.form.applied

        vm.onFiltersOpen()
        vm.onDraftDimensionToggle(SearchDimension.CATEGORY, animeTv)
        vm.onDraftAirtimeChange(SearchAirtime(2010, 2010))
        // 发起一轮搜索时 Pager 在事件里当场把结果标成加载中（请求随后才发），所以不用等一段时间：
        // 此刻结果没在加载，就是没发起
        assertFalse(vm.uiState.value.results.isLoading, "改草稿不得发起搜索")
        assertEquals(baseline, fixture.requestedOffsets.size, "改草稿不得发请求")
        assertEquals(applied, vm.uiState.value.form.applied)

        vm.onFiltersDismiss()
        assertFalse(vm.uiState.value.results.isLoading, "关闭不得发起搜索")
        assertEquals(baseline, fixture.requestedOffsets.size, "关闭不得发请求")
        assertEquals(applied, vm.uiState.value.form.applied)
        assertNull(vm.uiState.value.form.filterDraft)

        vm.onFiltersOpen()
        vm.onDraftDimensionToggle(SearchDimension.CATEGORY, animeTv)
        vm.onDraftAirtimeChange(SearchAirtime(2010, 2010, 4))
        vm.onFiltersSave()
        awaitUntil("保存后搜索完成") { fixture.requestedOffsets.size > baseline && !vm.uiState.value.results.isLoading }
        assertEquals(baseline + 1, fixture.requestedOffsets.size, "保存只发一次请求")
        // 类型 + 维度 + 年月、默认标注数排序 → 关键词搜索：维度进 meta_tags、年月进 air_date，无标签
        val dto = searchDto(fixture.capturedBodies.last())
        assertEquals("heat", dto.sort)
        assertEquals(listOf(2), dto.filter?.type)
        assertEquals(listOf("TV"), dto.filter?.metaTags)
        assertEquals(listOf(">=2010-04-01", "<=2010-04-30"), dto.filter?.airDate)
        assertNull(vm.uiState.value.form.filterDraft)
    }

    @Test
    fun `带openFilters初始化时自动展开筛选面板`() = runBlocking(main.dispatcher) {
        val fixture = makeViewModel(openFilters = true)
        val state = fixture.viewModel.uiState.value
        assertTrue(state.form.filterDraft != null, "openFilters 为 true 时面板草稿必须非空（面板已展开）")
    }

    // —— 图钉：条件放上探索页成一条货架

    /** 一组不在默认布局里的动画条件：两个词的交集。 */
    private val twoWords = SearchCriteria(subjectType = SubjectType.ANIME).pickWord("奇幻", WordPick.ADD).pickWord("百合", WordPick.ADD)

    @Test
    fun `图钉_放上去后亮着_再点从探索页移除`() = runBlocking(main.dispatcher) {
        val layouts = ExploreLayoutRepository(FakeExploreShelfDao())
        val vm = makeViewModel(seed = twoWords, layoutRepository = layouts).viewModel
        assertNull(vm.uiState.value.pinned)

        vm.onPinToggle("奇幻 × 百合")
        awaitUntil("放上去后图钉亮了") { vm.uiState.value.pinned != null }
        assertEquals(PinnedShelf("custom:1", "奇幻 × 百合"), vm.uiState.value.pinned)
        assertEquals("custom:1", layouts.observe(SubjectType.ANIME).first().first().id, "新货架在近期热门下面第一条")

        vm.onPinToggle("奇幻 × 百合")
        awaitUntil("移除后图钉熄了") { vm.uiState.value.pinned == null }
        assertTrue(layouts.observe(SubjectType.ANIME).first().single { it.id == "custom:1" }.hidden, "移除是隐藏")
    }

    @Test
    fun `默认布局里已有的条件_图钉一开始就亮_改名记在那条货架上`() = runBlocking(main.dispatcher) {
        val fantasy = checkNotNull(shelfPresetOf(SubjectType.ANIME, "奇幻"))
        val vm = makeViewModel(seed = fantasy.criteria).viewModel

        awaitUntil("图钉对上默认布局里的预设") { vm.uiState.value.pinned != null }
        assertEquals(PinnedShelf("preset:奇幻", fantasy.name), vm.uiState.value.pinned)

        vm.onRenameStart()
        vm.onRenameSubmit("  魔法  ")
        awaitUntil("改名落到预设货架上，写完收起改名框") { vm.uiState.value.pinned?.name == "魔法" && !vm.uiState.value.renaming }
    }

    @Test
    fun `布局表读不出时搜索页提示存储失败 重试读到后图钉对上预设`() = runBlocking(main.dispatcher) {
        val dao = FaultableExploreShelfDao().apply { failReads.set(true) }
        val fantasy = checkNotNull(shelfPresetOf(SubjectType.ANIME, "奇幻"))
        val vm = makeViewModel(seed = fantasy.criteria, layoutRepository = ExploreLayoutRepository(dao)).viewModel
        awaitUntil("存储失败提示") { vm.failures.current.value?.text == NoticeText.Reason(Failure.Storage) }
        val retry = assertNotNull(vm.failures.current.value?.retry)
        assertNull(vm.uiState.value.pinned, "布局读不出时图钉当作没对上")

        dao.failReads.set(false)
        retry()
        awaitUntil("重试后图钉对上默认布局里的预设") { vm.uiState.value.pinned != null }
    }

    @Test
    fun `货架改名写入失败时改名框留着_名字不变`() = runBlocking(main.dispatcher) {
        val failing = object : ExploreShelfDao by FakeExploreShelfDao() {
            override suspend fun replaceType(subjectType: Int, rows: List<ExploreShelfEntity>) {
                throw IOException("disk full")
            }
        }
        val fantasy = checkNotNull(shelfPresetOf(SubjectType.ANIME, "奇幻"))
        val vm = makeViewModel(seed = fantasy.criteria, layoutRepository = ExploreLayoutRepository(failing)).viewModel
        awaitUntil("图钉对上默认布局里的预设") { vm.uiState.value.pinned != null }

        vm.onRenameStart()
        vm.onRenameSubmit("魔法")
        awaitUntil("写入失败发出提示") { vm.failures.current.value != null }
        assertTrue(vm.uiState.value.renaming, "写入失败不收起改名框")
        assertEquals(fantasy.name, vm.uiState.value.pinned?.name)
    }

    @Test
    fun `货架改名提交空名字直接收起_不写表`() = runBlocking(main.dispatcher) {
        val dao = FakeExploreShelfDao()
        val fantasy = checkNotNull(shelfPresetOf(SubjectType.ANIME, "奇幻"))
        val vm = makeViewModel(seed = fantasy.criteria, layoutRepository = ExploreLayoutRepository(dao)).viewModel
        awaitUntil("图钉对上默认布局里的预设") { vm.uiState.value.pinned != null }

        vm.onRenameStart()
        vm.onRenameSubmit("   ")
        assertFalse(vm.uiState.value.renaming, "空名字不保存，改名框直接收起")
        assertEquals(0, dao.writes)
        assertEquals(fantasy.name, vm.uiState.value.pinned?.name)
    }

    @Test
    fun `全部分类的条件不能放上探索页`() = runBlocking(main.dispatcher) {
        val dao = FakeExploreShelfDao()
        val criteria = SearchCriteria(subjectType = null, filters = SearchFilters(tags = listOf(TagFilter.User("百合"))))
        val vm = makeViewModel(seed = criteria, layoutRepository = ExploreLayoutRepository(dao)).viewModel

        vm.onPinToggle("百合")

        assertEquals(0, dao.writes)
        assertNull(vm.uiState.value.pinned)
    }
}
