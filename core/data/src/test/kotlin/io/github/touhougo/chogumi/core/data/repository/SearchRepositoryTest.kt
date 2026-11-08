package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SearchRepositoryTest {
    private val engines = RecordedMockEngines()
    @get:org.junit.Rule
    val resources = TestResources()


    private val json = Json { ignoreUnknownKeys = true }

    /** p1 搜索响应里的一条短行。 */
    private fun slimSubjectJson(id: Int) =
        """{"id":$id,"name":"S$id","nameCN":"条目$id","type":2,"info":"","metaTags":[],
        "rating":{"rank":0,"count":[],"score":7.5,"total":10},"locked":false,"nsfw":false,
        "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"}}"""

    /** p1 搜索响应：total 为条数。 */
    private fun p1SearchPage(ids: List<Int>, total: Int = ids.size) =
        """{"data":[${ids.joinToString(",") { slimSubjectJson(it) }}],"total":$total}"""

    /** 只用 v0 能筛的时间条件：带上它的搜索走 v0（p1 搜索的日期条件服务端坏了）。 */
    private val dated = SearchFilters(airtime = SearchAirtime(2003, 2003))

    private data class RepoFixture(
        val repo: SearchRepository,
        val engine: MockEngine,
        val recentSearches: FakeRecentSearchDao,
        val subjectDao: FakeSubjectDao,
    )

    private fun makeRepoWithEngine(
        engine: MockEngine,
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
        firstPageDao: FakeSearchFirstPageDao = FakeSearchFirstPageDao(),
        clock: AppClock = AppClock.SYSTEM,
    ): RepoFixture {
        val client = resources.apiClient(engine)
        val recentSearchDao = FakeRecentSearchDao()
        val subjectDao = FakeSubjectDao()
        return RepoFixture(
            testSearchRepository(
                resources,
                SubjectApi(client), NextApi(client), subjectDao, recentSearchDao, testNsfwFilter(resources),
                clock = clock, computationDispatcher = computationDispatcher, firstPageDao = firstPageDao,
            ),
            engine, recentSearchDao, subjectDao,
        )
    }

    private fun makeRepo(content: String): RepoFixture {
        val engine = engines.create {
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        return makeRepoWithEngine(engine)
    }

    /** 提取 MockEngine 最近一次请求体的 filter 对象字段值。 */
    private suspend fun MockEngine.lastFilter(): kotlinx.serialization.json.JsonObject? =
        json.parseToJsonElement(engines.requests(this).last().body.toByteArray().decodeToString())
            .jsonObject["filter"]?.jsonObject

    /** 第一页的假接口：每个请求回一条 id 为 [id] 的条目，并记下收到几个请求。 */
    private fun firstPageEngine(id: () -> Int = { 8 }) = engines.create {
        respond(p1SearchPage(listOf(id())), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
    }

    @Test
    fun `搜索页再打开同一组条件，新鲜期内第一页用存下的那一页，带回总数与游标，不发请求`() = runTest {
        // 一页 20 条（够一批，不补页）、共 300 条：第一页之后还有下一页
        val engine = engines.create {
            respond(p1SearchPage((1..20).toList(), total = 300), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val fixture = makeRepoWithEngine(engine)
        val criteria = SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.RANK)

        val fromNetwork = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(fixture.repo.search(criteria, reuseFirstPage = true)).value
        val reused = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(fixture.repo.search(criteria, reuseFirstPage = true)).value

        assertEquals(1, engines.requests(engine).count(), "新鲜期内再打开不该再发请求")
        assertEquals((1..20).toList(), reused.items.map { it.id })
        assertEquals(300, reused.total)
        assertEquals(fromNetwork.nextOffset, reused.nextOffset, "存下的游标接着往下翻")
        assertEquals(SearchRepository.P1_SEARCH_PAGE_SIZE, reused.nextOffset)
    }

    @Test
    fun `搜索页重试与再次提交不用存下的那一页，取回的新结果换掉存下的`() = runTest {
        val nextId = AtomicInteger(8)
        val engine = firstPageEngine { nextId.getAndIncrement() }
        val fixture = makeRepoWithEngine(engine)
        val criteria = SearchCriteria(query = "你好", subjectType = SubjectType.ANIME)

        fixture.repo.search(criteria, reuseFirstPage = true)
        val resubmitted = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(fixture.repo.search(criteria)).value
        val reopened = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(fixture.repo.search(criteria, reuseFirstPage = true)).value

        assertEquals(listOf(9), resubmitted.items.map { it.id })
        assertEquals(2, engines.requests(engine).count())
        assertEquals(listOf(9), reopened.items.map { it.id }, "再打开用的是最近取回的那一页")
    }

    @Test
    fun `搜索页存下的第一页过了新鲜期就重新取`() = runTest {
        val nextId = AtomicInteger(8)
        var now = 1_000_000L
        val engine = firstPageEngine { nextId.getAndIncrement() }
        val fixture = makeRepoWithEngine(engine, clock = { now })
        val criteria = SearchCriteria(query = "你好", subjectType = SubjectType.ANIME)

        fixture.repo.search(criteria, reuseFirstPage = true)
        now += CacheTtl.SEARCH_FIRST_PAGE
        val stale = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(fixture.repo.search(criteria, reuseFirstPage = true)).value

        assertEquals(2, engines.requests(engine).count())
        assertEquals(listOf(9), stale.items.map { it.id })
    }

    @Test
    fun `第一页新鲜期内先出上次的结果，不再发请求`() = runTest {
        val engine = firstPageEngine()
        val fixture = makeRepoWithEngine(engine)
        val criteria = SearchCriteria(query = "你好", subjectType = SubjectType.ANIME)

        val fromNetwork = fixture.repo.firstPage(criteria, refresh = false).toList().last()
        val cached = fixture.repo.firstPage(criteria, refresh = false).toList()

        assertEquals(listOf(8), assertIs<Load.Ready<List<SubjectBo>>>(fromNetwork).value.map { it.id })
        assertEquals(1, engines.requests(engine).count(), "新鲜期内第二次取第一页不该再发请求")
        val ready = assertIs<Load.Ready<List<SubjectBo>>>(cached.single())
        assertTrue(ready.fromCache)
        assertEquals(listOf(8), ready.value.map { it.id })
    }

    @Test
    fun `货架的第一页只取一屏，搜索页一次取满一页`() = runTest {
        val engine = firstPageEngine()
        val fixture = makeRepoWithEngine(engine)
        val criteria = SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.RANK)

        fixture.repo.firstPage(criteria, refresh = false).toList()
        fixture.repo.search(criteria)

        val (shelf, list) = engines.requests(engine).map { it.url.encodedPath to it.url.parameters["limit"] }
        assertEquals("/p1/search/subjects" to SearchRepository.SHELF_SIZE.toString(), shelf, "货架照旧只要第一屏")
        assertEquals("/p1/search/subjects" to SearchRepository.P1_SEARCH_PAGE_SIZE.toString(), list, "搜索页按 p1 的上限一次取一页")
    }

    @Test
    fun `下拉刷新强刷第一页`() = runTest {
        val nextId = AtomicInteger(8)
        val engine = firstPageEngine { nextId.getAndIncrement() }
        val fixture = makeRepoWithEngine(engine)
        val criteria = SearchCriteria(query = "你好", subjectType = SubjectType.ANIME)

        fixture.repo.firstPage(criteria, refresh = false).toList()
        val refreshed = fixture.repo.firstPage(criteria, refresh = true).toList().last()

        assertEquals(2, engines.requests(engine).count())
        assertEquals(listOf(9), assertIs<Load.Ready<List<SubjectBo>>>(refreshed).value.map { it.id })
    }

    @Test
    fun `只差关键词的两组条件，第一页各存各的`() = runTest {
        val nextId = AtomicInteger(8)
        val engine = firstPageEngine { nextId.getAndIncrement() }
        val fixture = makeRepoWithEngine(engine)

        fixture.repo.firstPage(SearchCriteria(query = "甲", subjectType = SubjectType.ANIME), refresh = false).toList()
        val other = fixture.repo.firstPage(SearchCriteria(query = "乙", subjectType = SubjectType.ANIME), refresh = false).toList().last()

        assertEquals(2, engines.requests(engine).count(), "关键词不同却命中了别的条件存下的第一页")
        assertEquals(listOf(9), assertIs<Load.Ready<List<SubjectBo>>>(other).value.map { it.id })
    }

    @Test
    fun `取页、落库与映射跑在注入的计算调度器上，不占调用方`() = runTest {
        val computation = CountingDispatcher()
        val engine = engines.create {
            respond(
                """{"data":[],"total":0,"limit":20,"offset":0}""",
                HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine, computation)
        assertIs<Outcome.Success<*>>(fixture.repo.search(SearchCriteria(query = "你好", subjectType = SubjectType.ANIME)))
        assertTrue(computation.dispatches > 0, "搜索没有切到计算调度器")
    }

    @Test
    fun `搜索成功返回结果并落条目表`() = runTest {
        val fixture = makeRepo(p1SearchPage(listOf(8)))
        val result = fixture.repo.search(SearchCriteria(query = "你好", subjectType = SubjectType.ANIME))
        assertTrue(result is Outcome.Success && result.value.items.size == 1)
        assertEquals(1, fixture.subjectDao.store.size)
        assertEquals(8, fixture.subjectDao.store[8]?.id)
    }

    @Test
    fun `空条件直接返回空结果不打网络`() = runTest {
        val fixture = makeRepo("""{"data":[],"total":0,"limit":25,"offset":0}""")
        val result = fixture.repo.search(SearchCriteria())
        assertTrue(result is Outcome.Success && result.value.items.isEmpty())
        assertTrue(engines.requests(fixture.engine).none())
    }

    /** 分类浏览响应：total 为页数。 */
    private fun p1Page(ids: List<Int>, totalPages: Int) = """{"data":[${ids.joinToString(",") { id ->
        """{"id":$id,"name":"S$id","nameCN":"条目$id","type":2,"info":"","metaTags":["TV"],
        "rating":{"rank":0,"count":[],"score":7.5,"total":10},"locked":false,"nsfw":false,
        "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"}}"""
    }}],"total":$totalPages}"""

    private fun routedRepo(
        p1: String = """{"data":[],"total":0}""",
        search: String = """{"data":[],"total":0}""",
        v0: String = """{"data":[],"total":0,"limit":20,"offset":0}""",
    ): RepoFixture =
        makeRepoWithEngine(
            engines.create { request ->
                respond(
                    content = when (request.url.encodedPath) {
                        "/p1/subjects" -> p1
                        "/p1/search/subjects" -> search
                        else -> v0
                    },
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            },
        )

    /**
     * 断言并发取页请求序列：
     * - 起始页（阶段一）必须最先发出（代码保证阶段一在阶段二之前）；
     * - 其余并发页（阶段二）按集合无序匹配，避免因并发请求到达/记录乱序导致偶发失败；
     * - 严格断言总请求数（防多发或少发）。
     */
    private fun <T> assertConcurrentRequests(
        actual: List<T>,
        expectedFirst: T,
        expectedRest: Set<T>,
        message: String? = null,
    ) {
        val totalExpected = 1 + expectedRest.size
        assertEquals(totalExpected, actual.size, message?.let { "$it (总请求数)" } ?: "总请求数不符")
        assertEquals(expectedFirst, actual.firstOrNull(), message?.let { "$it (起始请求)" } ?: "起始请求必须最先发出")
        assertEquals(expectedRest, actual.drop(1).toSet(), message?.let { "$it (并发请求集合)" } ?: "并发请求集合不符")
    }

    @Test
    fun `选了类型无关键词按热度走p1浏览_GET参数`() = runTest {
        val fixture = routedRepo(p1 = p1Page(listOf(1, 2), totalPages = 1))
        val result = fixture.repo.search(SearchCriteria(subjectType = SubjectType.BOOK, sort = SearchSort.TRENDS))

        val request = engines.requests(fixture.engine).single()
        assertEquals("GET", request.method.value)
        assertEquals("/p1/subjects", request.url.encodedPath)
        assertEquals("1", request.url.parameters["type"])
        assertEquals("trends", request.url.parameters["sort"])
        assertEquals("1", request.url.parameters["page"])
        assertNull(request.url.parameters["tags"])
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(listOf(1, 2), success.value.items.map { it.id })
        assertEquals(2, fixture.subjectDao.store.size, "浏览结果同样落条目表")
    }

    @Test
    fun `选了类型无关键词默认按标注数走p1搜索_POST参数与filter_一页100条`() = runTest {
        // 默认排序是 COLLECTS（标注数），走 p1 搜索：POST /p1/search/subjects，条件全在 filter
        val fixture = routedRepo(search = p1SearchPage(listOf(9)))
        val result = fixture.repo.search(SearchCriteria(subjectType = SubjectType.BOOK))

        val request = engines.requests(fixture.engine).single()
        assertEquals("POST", request.method.value)
        assertEquals("/p1/search/subjects", request.url.encodedPath)
        assertEquals("100", request.url.parameters["limit"])
        assertEquals("0", request.url.parameters["offset"])
        val body = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
        assertEquals("", body["keyword"]?.jsonPrimitive?.content)
        assertEquals("heat", body["sort"]?.jsonPrimitive?.content)
        assertEquals(listOf(1), body["filter"]?.jsonObject?.get("type")?.jsonArray?.map { it.jsonPrimitive.int })
        assertNull(body["filter"]?.jsonObject?.get("tags"))
        assertNull(body["filter"]?.jsonObject?.get("metaTags"))
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(listOf(9), success.value.items.map { it.id })
    }

    @Test
    fun `分类浏览多标签各自一个参数_年月直传`() = runTest {
        val fixture = routedRepo(p1 = p1Page(emptyList(), totalPages = 0))
        fixture.repo.search(
            SearchCriteria(
                subjectType = SubjectType.ANIME,
                sort = SearchSort.TRENDS,
                filters = SearchFilters(
                    tags = listOf(TagFilter.Public("TV"), TagFilter.Public("漫画改")),
                    airtime = SearchAirtime(2024, 2024, 10),
                ),
            ),
        )
        val params = engines.requests(fixture.engine).single().url.parameters
        assertEquals("trends", params["sort"])
        assertEquals(listOf("TV", "漫画改"), params.getAll("tags"))
        assertEquals("2024", params["year"])
        assertEquals("10", params["month"])
    }

    @Test
    fun `分类浏览页码换算_total为页数_末页无下一页`() = runTest {
        val fixture = routedRepo(p1 = p1Page((1..12).toList(), totalPages = 3))
        // 固定热度排序：排名与标注数走搜索接口，页码换算这一支（分类浏览）只在热度/日期/名称下可达
        val criteria = SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS)

        val first = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(
            fixture.repo.search(criteria, offset = 0),
        )
        assertEquals(SearchRepository.BROWSE_PAGE_SIZE, first.value.nextOffset)
        assertEquals(3 * SearchRepository.BROWSE_PAGE_SIZE, first.value.total)

        val second = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(
            fixture.repo.search(criteria, offset = SearchRepository.BROWSE_PAGE_SIZE),
        )
        assertEquals(2 * SearchRepository.BROWSE_PAGE_SIZE, second.value.nextOffset)

        val last = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(
            fixture.repo.search(criteria, offset = 2 * SearchRepository.BROWSE_PAGE_SIZE),
        )
        assertNull(last.value.nextOffset)
        assertEquals(listOf("1", "2", "3"), engines.requests(fixture.engine).map { it.url.parameters["page"] })
    }

    @Test
    fun `分类浏览空页即结束`() = runTest {
        val fixture = routedRepo(p1 = p1Page(emptyList(), totalPages = 0))
        val result = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(
            fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS)),
        )
        assertNull(result.value.nextOffset)
    }

    @Test
    fun `无类型只有年份时走搜索接口_年份换算日期区间`() = runTest {
        val fixture = routedRepo(p1 = p1Page(emptyList(), 0))
        fixture.repo.search(SearchCriteria(filters = SearchFilters(airtime = SearchAirtime(2010, 2010))))
        assertEquals("/v0/search/subjects", engines.requests(fixture.engine).single().url.encodedPath)
        assertEquals(
            listOf(">=2010-01-01", "<=2010-12-31"),
            fixture.engine.lastFilter()?.get("air_date")?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `单独排序不构成搜索_零网络`() = runTest {
        val fixture = routedRepo(p1 = p1Page(emptyList(), 0))
        val result = fixture.repo.search(SearchCriteria(sort = SearchSort.RANK))
        assertTrue(result is Outcome.Success && result.value.items.isEmpty())
        assertTrue(engines.requests(fixture.engine).none())
    }

    @Test
    fun `非法条件返回Error且零网络零落库`() = runTest {
        val fixture = makeRepo("""{"data":[],"total":0,"limit":20,"offset":0}""")
        // 越界月份
        val badMonth = fixture.repo.search(
            SearchCriteria(filters = SearchFilters(airtime = SearchAirtime(2020, 2020, 13))),
        )
        assertIs<Outcome.Failed>(badMonth)
        // UNKNOWN 类型
        val unknown = fixture.repo.search(SearchCriteria(subjectType = SubjectType.UNKNOWN))
        assertIs<Outcome.Failed>(unknown)
        // 空白标签
        val blankTag = fixture.repo.search(SearchCriteria(filters = SearchFilters(tags = listOf(TagFilter.User("  ")))))
        assertIs<Outcome.Failed>(blankTag)

        assertTrue(engines.requests(fixture.engine).none(), "非法条件不得发网络请求")
        assertTrue(fixture.subjectDao.store.isEmpty(), "非法条件不得落库")
    }

    @Test
    fun `空关键词有分类时默认按标注数走p1搜索_标签只发一次`() = runTest {
        val fixture = routedRepo(search = p1SearchPage(listOf(101)))
        val result = fixture.repo.search(
            SearchCriteria(subjectType = SubjectType.ANIME, filters = SearchFilters(tags = listOf(TagFilter.Public("科幻")))),
        )
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(listOf(101), success.value.items.map { it.id })
        val request = engines.requests(fixture.engine).single()
        assertEquals("/p1/search/subjects", request.url.encodedPath)
        assertEquals("heat", json.parseToJsonElement(request.body.toByteArray().decodeToString())
            .jsonObject["sort"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("科幻"),
            fixture.engine.lastFilter()?.get("metaTags")?.jsonArray?.map { it.jsonPrimitive.content },
            "单个 meta 标签原样发一次（p1 浏览「同一标签发两次」那个缺陷在搜索接口上没有）",
        )
        assertNull(fixture.engine.lastFilter()?.get("tags"))
    }

    @Test
    fun `用户标签写入tags字段而非metaTags`() = runTest {
        val engine = engines.create { request ->
            val body = request.body.toByteArray().decodeToString()
            val filter = json.parseToJsonElement(body).jsonObject["filter"]?.jsonObject
            val userTags = filter?.get("tags")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

            respond(
                content = p1SearchPage(if (userTags.contains("妹控")) listOf(101) else emptyList()),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)

        // 无类型的公共标签走搜索接口 metaTags，不命中「妹控」用户标签
        val publicResult = fixture.repo.search(
            SearchCriteria(filters = SearchFilters(tags = listOf(TagFilter.Public("妹控")))),
        )
        val publicSuccess = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(publicResult)
        assertTrue(publicSuccess.value.items.isEmpty())

        // 多个用户标签走搜索接口（多标签不可浏览：p1 不支持多个用户标签的且关系）
        val userResult = fixture.repo.search(
            SearchCriteria(
                subjectType = SubjectType.ANIME,
                sort = SearchSort.TRENDS,
                filters = SearchFilters(tags = listOf(TagFilter.User("妹控"), TagFilter.User("日常"))),
            ),
        )
        val userSuccess = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(userResult)
        assertEquals(1, userSuccess.value.items.size)
        assertEquals(101, userSuccess.value.items.first().id)

        assertEquals("/p1/search/subjects", engines.requests(fixture.engine).last().url.encodedPath)
        val filter = fixture.engine.lastFilter()!!
        assertEquals(listOf("妹控", "日常"), filter["tags"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertNull(filter["metaTags"])
    }

    @Test
    fun `单用户标签默认按标注数走p1搜索_用户标签进tags而非metaTags`() = runTest {
        val fixture = routedRepo(search = p1SearchPage(listOf(326)))
        val result = fixture.repo.search(
            SearchCriteria(subjectType = SubjectType.ANIME, filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子")))),
        )
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(listOf(326), success.value.items.map { it.id })
        val request = engines.requests(fixture.engine).single()
        assertEquals("/p1/search/subjects", request.url.encodedPath)
        val filter = fixture.engine.lastFilter()!!
        assertEquals(listOf("菅野洋子"), filter["tags"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertNull(filter["metaTags"], "用户标签不能落到 metaTags（那是另一列）")
    }

    @Test
    fun `单用户标签按日期排序仍走p1浏览携带tagsCat`() = runTest {
        val fixture = makeRepo(
            """{"data":[{"id":326,"type":2,"name":"攻壳","nameCN":"攻壳","info":"","metaTags":[],
            "rating":{"rank":1,"count":[],"score":9.0,"total":100},"locked":false,"nsfw":false,
            "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"}}],"total":1}""",
        )
        val result = fixture.repo.search(
            SearchCriteria(
                subjectType = SubjectType.ANIME,
                sort = SearchSort.DATE,
                filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子"))),
            ),
        )
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(listOf(326), success.value.items.map { it.id })
        val request = engines.requests(fixture.engine).single()
        assertEquals("/p1/subjects", request.url.encodedPath)
        assertEquals("subject", request.url.parameters["tagsCat"])
        assertEquals(listOf("菅野洋子"), request.url.parameters.getAll("tags"))
        assertEquals("date", request.url.parameters["sort"])
    }

    @Test
    fun `浏览接口中间空页被服务端剔空时仍按total继续翻页`() = runTest {
        // total 为 10 页（共 10 * 24 = 240 条），中间全空时最多连续取 6 页达到上限停止。
        // 无关键词、按日期排序：走 p1 分类浏览。
        val fixture = makeRepo("""{"data":[],"total":10}""")
        val result = fixture.repo.search(
            SearchCriteria(
                subjectType = SubjectType.ANIME,
                sort = SearchSort.DATE,
                filters = SearchFilters(tags = listOf(TagFilter.User("重口"))),
            ),
            offset = 0,
        )
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertTrue(success.value.items.isEmpty())
        assertEquals(240, success.value.total)
        assertEquals(144, success.value.nextOffset, "第 1~6 页为空，达到 6 页上限停止，按 nextCursor 推进到 144")
        assertEquals(6, engines.requests(fixture.engine).count())
        assertTrue(success.value.hasMore)
    }

    @Test
    fun `完整组合首屏与翻页body相同仅URL分页不同`() = runTest {
        val subjectItems = (1..12).joinToString(",") { """{"id":$it,"type":2,"name":"n$it","name_cn":"一$it","summary":"s","images":{"large":"","common":"","medium":"","small":"","grid":""},"series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,"meta_tags":[],"tags":[],"rating":{"rank":0,"total":0,"count":{},"score":0.0},"collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}""" }
        val fixture = makeRepo("""{"data":[$subjectItems],"total":100,"limit":20,"offset":0}""")
        val criteria = SearchCriteria(
            query = "机动",
            subjectType = SubjectType.ANIME,
            filters = SearchFilters(
                tags = listOf(TagFilter.Public("科幻"), TagFilter.User("原创")),
                airtime = SearchAirtime(2010, 2010),
            ),
        )
        fixture.repo.search(criteria, offset = 0)
        fixture.repo.search(criteria, offset = 20)

        assertEquals(2, engines.requests(fixture.engine).count())
        val bodies = engines.requests(fixture.engine).map { it.body.toByteArray().decodeToString() }
        assertEquals(bodies[0], bodies[1], "首屏与翻页 body 必须一致")
        val offsets = engines.requests(fixture.engine).map { it.url.parameters["offset"] }
        assertEquals(listOf("0", "20"), offsets)
        assertEquals(listOf("20", "20"), engines.requests(fixture.engine).map { it.url.parameters["limit"] })
    }

    @Test
    fun `历史保存`() = runTest {
        val fixture = makeRepo("""{"data":[],"total":0,"limit":25,"offset":0}""")
        fixture.repo.recordSearch("约定的梦幻岛")
        assertEquals(1, fixture.recentSearches.store.size)
    }

    @Test
    fun `带时间条件的v0搜索服务端跳条不误判末页并按请求跨度推进`() = runTest {
        val subjectItems = (1..12).joinToString(",") { """{"id":$it,"type":2,"name":"n$it","name_cn":"一$it","summary":"s","images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},"series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,"meta_tags":[],"tags":[],"rating":{"rank":0,"total":0,"count":{},"score":0.0},"collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}""" }
        val fixture = makeRepo(
            """{"data":[$subjectItems],"total":50,"limit":20,"offset":0}"""
        )
        val result = fixture.repo.search(
            SearchCriteria(query = "测试", subjectType = SubjectType.ANIME, filters = dated), offset = 0,
        )
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(12, success.value.items.size)
        assertEquals(50, success.value.total)
        assertEquals(20, success.value.nextOffset, "跳条后游标按请求跨度 20 推进")
        assertTrue(success.value.hasMore)
    }

    @Test
    fun `搜索到达total时nextOffset为null`() = runTest {
        // 非空的一页：空页本身也会结束翻页，用空页证明不了按 total 判末页
        val fixture = makeRepo(p1SearchPage((1..20).toList(), total = 20))
        val result = fixture.repo.search(
            SearchCriteria(query = "测试", subjectType = SubjectType.ANIME), offset = 0,
        )
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(20, success.value.items.size)
        assertNull(success.value.nextOffset)
        assertFalse(success.value.hasMore)
    }

    @Test
    fun `搜索与分类浏览登录态下带令牌、未登录不带`() = runTest {
        val engine = engines.create { request ->
            val content = if (request.url.encodedPath.contains("/search/")) {
                """{"data":[],"total":0,"limit":20,"offset":0}"""
            } else {
                """{"data":[],"total":0}"""
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val tokenStore = testTokenStore(scope = backgroundScope)
        val repo = testSearchRepository(
            resources,
            SubjectApi(client),
            NextApi(client),
            FakeSubjectDao(),
            FakeRecentSearchDao(),
            testNsfwFilter(resources),
            tokenStore = tokenStore,
        )

        /** 这次调用发出的每个请求的令牌头；一个请求都没发出时失败（否则「不带令牌」恒真）。 */
        suspend fun authsOf(call: suspend () -> Unit): Set<String?> {
            val before = engines.requests(engine).count()
            call()
            return engines.requests(engine).drop(before).map { it.headers[io.ktor.http.HttpHeaders.Authorization] }.toSet()
                .also { assertTrue(it.isNotEmpty(), "这次调用没有发出请求") }
        }

        // 1. 未登录
        assertEquals(setOf(null), authsOf { repo.search(SearchCriteria(query = "关键词", subjectType = SubjectType.ANIME)) })
        assertEquals(setOf(null), authsOf { repo.search(SearchCriteria(query = "", subjectType = SubjectType.ANIME)) })

        // 2. 登录
        val session = tokenStore.beginSession("search_token_123")
        tokenStore.completeSession(session, userId = 1, username = "searcher")

        val bearer = setOf<String?>("Bearer search_token_123")
        // p1 搜索 (SearchSource.SEARCH)
        assertEquals(bearer, authsOf { repo.search(SearchCriteria(query = "关键词", subjectType = SubjectType.ANIME)) })

        // 带时间条件的 v0 搜索 (SearchSource.DATED_SEARCH)
        assertEquals(bearer, authsOf { repo.search(SearchCriteria(query = "关键词", subjectType = SubjectType.ANIME, filters = dated)) })

        // 分类浏览 (SearchSource.BROWSE)：匿名时服务端对部分分类整个不给
        val browse = authsOf { repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS)) }
        assertEquals("/p1/subjects", engines.requests(engine).last().url.encodedPath)
        assertEquals(bearer, browse, "分类浏览登录时带令牌")
    }

    @Test
    fun `搜索期间发生换号返回会话已变且网络数据不落库`() = runTest {
        val subjectDao = FakeSubjectDao()
        val tokenStore = testTokenStore(scope = backgroundScope)
        val sessionA = tokenStore.beginSession("token_a")
        tokenStore.completeSession(sessionA, userId = 1, username = "usera")

        val engine = engines.create {
            // 请求期间换号
            val sessionB = tokenStore.beginSession("token_b")
            tokenStore.completeSession(sessionB, userId = 2, username = "userb")
            // 关键词搜索走 p1：给它能解析的一页，换号要由会话校验挡住，而不是由解析失败挡住
            respond(
                content = p1SearchPage(listOf(999)),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val repo = testSearchRepository(
            resources,
            SubjectApi(client),
            NextApi(client),
            subjectDao,
            FakeRecentSearchDao(),
            testNsfwFilter(resources),
            tokenStore = tokenStore,
        )

        val result = repo.search(SearchCriteria(query = "换号测试", subjectType = SubjectType.ANIME))
        assertEquals(Outcome.Failed(Failure.SessionChanged), result)
        assertNull(subjectDao.get(999), "换号后搜索结果不得落库")
    }

    @Test
    fun `凑满一批_a_前几页稀疏时并发取页到满12条才返回_nextOffset指向最后取的页之后`() = runTest {
        // 并发取页语义：起始页只 0 条（< 12），剩余 5 页（第 2~6 页）一次并发发出。
        // 各页可见数 0 + 1 + 2 + 10 + 0 + 0 = 13 条（>= MIN_VISIBLE_PER_LOAD=12）。
        // 本轮共取满 MAX_PAGES_PER_LOAD=6 页，nextOffset 指向第 6 页之后的 144。
        val recorder = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            recorder.record(request)
            val pageNo = request.url.parameters["page"]?.toInt() ?: 1
            val items = when (pageNo) {
                1 -> emptyList()
                2 -> listOf(101)
                3 -> listOf(102, 103)
                4 -> (104..113).toList()
                else -> emptyList()
            }
            respond(
                content = p1Page(items, totalPages = 10),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS), offset = 0)

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(13, success.value.items.size)
        assertEquals(listOf(101, 102, 103) + (104..113).toList(), success.value.items.map { it.id })
        assertEquals(144, success.value.nextOffset, "并发取满 6 页，nextOffset 指向第 6 页之后的 144")
        assertEquals(240, success.value.total)
        assertConcurrentRequests(
            actual = recorder.map { it.url.parameters["page"] },
            expectedFirst = "1",
            expectedRest = setOf("2", "3", "4", "5", "6"),
        )
    }

    @Test
    fun `凑满一批_b_到底时不足12条也返回_nextOffset为null`() = runTest {
        // 共 2 页（total = 2 * 24 = 48），每页 2 条，共 4 条（< 12 条）
        // 到达末页（第 2 页 nextOffset == null）后停止返回
        val recorder = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            recorder.record(request)
            val pageNo = request.url.parameters["page"]?.toInt() ?: 1
            val items = when (pageNo) {
                1 -> listOf(201, 202)
                2 -> listOf(203, 204)
                else -> emptyList()
            }
            respond(
                content = p1Page(items, totalPages = 2),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS), offset = 0)

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(4, success.value.items.size)
        assertEquals(listOf(201, 202, 203, 204), success.value.items.map { it.id })
        assertNull(success.value.nextOffset, "到底后 nextOffset 为 null")
        assertEquals(48, success.value.total)
        assertConcurrentRequests(
            actual = recorder.map { it.url.parameters["page"] },
            expectedFirst = "1",
            expectedRest = setOf("2"),
        )
    }

    @Test
    fun `凑满一批_c_达到6页上限时返回_nextOffset非空`() = runTest {
        // total 共 10 页，每页仅 1 条，连续取 6 页共 6 条（< 12 条）
        // 达到 MAX_PAGES_PER_LOAD=6 上限停止，nextOffset 为 6 * 24 = 144
        val recorder = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            recorder.record(request)
            val pageNo = request.url.parameters["page"]?.toInt() ?: 1
            respond(
                content = p1Page(listOf(300 + pageNo), totalPages = 10),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS), offset = 0)

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(6, success.value.items.size)
        assertEquals((1..6).map { 300 + it }, success.value.items.map { it.id })
        assertEquals(144, success.value.nextOffset, "达到 6 页上限停止，nextOffset 为 6 * 24 = 144（非空）")
        assertEquals(240, success.value.total)
        assertConcurrentRequests(
            actual = recorder.map { it.url.parameters["page"] },
            expectedFirst = "1",
            expectedRest = setOf("2", "3", "4", "5", "6"),
        )
    }

    @Test
    fun `凑满一批_d_并发中某页失败整体返回Error且其余请求被取消`() = runBlocking {
        // 起始页成功返回 2 条（< 12），触发并发取第 2~6 页。五页全部挡在各自闸门里在途，
        // 只放行第 3 页且令其返回 500：整次必须返回 Error、不产生半截 Success，
        // 其余四页的请求随作用域被取消 —— 未取消则它们会永远等在闸门上，cancelled 永远为空。
        val gates = (2..6).associateWith { CompletableDeferred<Unit>() }
        val entered = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val engine = engines.create { request ->
            val pageNo = request.url.parameters["page"]?.toInt() ?: 1
            if (pageNo == 1) {
                respond(
                    content = p1Page(listOf(401, 402), totalPages = 10),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            } else {
                entered.add(pageNo)
                try {
                    gates.getValue(pageNo).await()
                } finally {
                    // 只有被取消（或正常放行后）才会走到这里；被取消时记为 cancelled。
                    if (!gates.getValue(pageNo).isCompleted) cancelled.add(pageNo)
                }
                if (pageNo == 3) {
                    respond(content = "Internal Server Error", status = HttpStatusCode.InternalServerError)
                } else {
                    respond(
                        content = p1Page(emptyList(), totalPages = 10),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val fixture = makeRepoWithEngine(engine)

        val search = async {
            fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS), offset = 0)
        }
        awaitUntil("阶段二 5 页全部在途") { entered.size == 5 }
        gates.getValue(3).complete(Unit) // 放行失败页，其余 4 页保持挂在闸门上

        assertIs<Outcome.Failed>(search.await(), "中途失败整次失败，不得返回半截结果")
        awaitUntil("其余在途请求被取消") { cancelled.size == 4 }
        assertEquals(setOf(2, 4, 5, 6), cancelled.toSet(), "失败页之外的在途请求必须被取消")
    }

    @Test
    fun `凑满一批_e_满页24条只取一页_请求次数为1`() = runTest {
        // 单页 24 条全可见，首屏即满足 >= 12 条，只请求 1 次
        val engine = engines.create {
            respond(
                content = p1Page((1..24).toList(), totalPages = 5),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS), offset = 0)

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(24, success.value.items.size)
        assertEquals(24, success.value.nextOffset)
        assertEquals(1, engines.requests(fixture.engine).count(), "满页（24 条全可见）只取一页，请求次数 = 1")
        assertEquals("1", engines.requests(fixture.engine).single().url.parameters["page"])
    }

    @Test
    fun `凑满一批_f_searchPage路径同样生效`() = runTest {
        // p1 搜索接口：第 1 页 2 条（< 12），触发并发。第 1 页游标 100 推出跨度 100，
        // 故并发取 offset 100/200/300/400/500 共 5 页（total=1000 未越界），本轮合计 6 页 limit=100。
        // 各页返回同一批 id（603..613），去重后与首页合并为 13 条；末页 offset=500 之后游标为 600。
        val recorder = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            recorder.record(request)
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            val ids = if (offset == 0) listOf(601, 602) else (603..613).toList()
            respond(
                content = p1SearchPage(ids, total = 1000),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(SearchCriteria(query = "机动", subjectType = SubjectType.ANIME), offset = 0)

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(13, success.value.items.size)
        assertEquals(600, success.value.nextOffset, "取满 6 页 limit=100，nextOffset 应为 600")
        assertEquals(1000, success.value.total)
        assertConcurrentRequests(
            actual = recorder.map { it.url.parameters["offset"] },
            expectedFirst = "0",
            expectedRest = setOf("100", "200", "300", "400", "500"),
            message = "searchPage 路径同样并发取页",
        )
    }

    @Test
    fun `凑满一批_跨页重复条目按id去重保持原序`() = runTest {
        val engine = engines.create { request ->
            val pageNo = request.url.parameters["page"]?.toInt() ?: 1
            val items = when (pageNo) {
                1 -> listOf(1, 2)
                2 -> listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13) // id 2 重复
                else -> emptyList()
            }
            respond(
                content = p1Page(items, totalPages = 5),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS), offset = 0)

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(13, success.value.items.size, "去重后应为 13 条")
        assertEquals((1..13).toList(), success.value.items.map { it.id }, "保持首次出现顺序")
    }

    @Test
    fun `凑满一批_并发_阶段二请求同时在途而非排队`() = runBlocking {
        // 起始页只 1 条可见（< 12），触发并发：剩余 5 页（第 2~6 页）必须同时在途。
        // 用每页各自的闸门挡住，若实现是串行排队，第 5 页永远到不了 gates，entered 停在 1。
        val recorder = ConcurrentRequestRecorder()
        val gates = (2..6).associateWith { CompletableDeferred<Unit>() }
        val entered = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val engine = MockEngine { request ->
            recorder.record(request)
            val pageNo = request.url.parameters["page"]?.toInt() ?: 1
            if (pageNo == 1) {
                respond(
                    content = p1Page(listOf(1), totalPages = 10),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            } else {
                entered.add(pageNo)
                gates.getValue(pageNo).await()
                respond(
                    content = p1Page(emptyList(), totalPages = 10),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }
        val fixture = makeRepoWithEngine(engine)

        val collect = async {
            fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS), offset = 0)
        }
        awaitUntil("阶段二 5 页全部在途") { entered.size == 5 }
        assertEquals(setOf(2, 3, 4, 5, 6), entered.toSet(), "阶段二的 5 页必须同时在途，而不是排队")
        gates.values.forEach { it.complete(Unit) }
        assertIs<Outcome.Success<PagedResult<SubjectBo>>>(collect.await())
        assertEquals(6, recorder.count())
    }

    @Test
    fun `凑满一批_并发_各页乱序返回仍按页序合并并去重`() = runBlocking {
        // 各页闸门分别放行，制造乱序返回（第 6 页先回、第 2 页最后回）。
        // 合并结果必须仍按页序（1..N），且跨页重复 id 只保留首次出现的那份。
        val gates = (2..6).associateWith { CompletableDeferred<Unit>() }
        val entered = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val engine = engines.create { request ->
            val pageNo = request.url.parameters["page"]?.toInt() ?: 1
            val items = when (pageNo) {
                1 -> listOf(1, 2)
                2 -> listOf(2, 3) // id 2 与第 1 页重复
                3 -> listOf(4, 5)
                4 -> listOf(6, 7)
                5 -> listOf(8, 9)
                6 -> listOf(10, 11)
                else -> emptyList()
            }
            if (pageNo > 1) {
                entered.add(pageNo)
                gates.getValue(pageNo).await()
            }
            respond(
                content = p1Page(items, totalPages = 10),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)

        val collect = async {
            fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS), offset = 0)
        }
        awaitUntil("阶段二 5 页全部在途") { entered.size == 5 }
        // 乱序放行：6 → 4 → 2，其余任意
        gates.getValue(6).complete(Unit)
        gates.getValue(4).complete(Unit)
        gates.getValue(2).complete(Unit)
        gates.getValue(3).complete(Unit)
        gates.getValue(5).complete(Unit)

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(collect.await())
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), success.value.items.map { it.id }, "乱序返回也必须按页序合并、去重")
    }

    @Test
    fun `凑满一批_并发_总页数不足6页时不越界请求`() = runTest {
        // 起始页 0 条可见（< 12），total = 3 页（共 72 条）。只允许请求第 1~3 页，不得请求第 4 页。
        val recorder = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            recorder.record(request)
            val pageNo = request.url.parameters["page"]?.toInt() ?: 1
            val items = if (pageNo <= 3) listOf(700 + pageNo) else emptyList()
            respond(
                content = p1Page(items, totalPages = 3),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.TRENDS), offset = 0)

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(listOf(701, 702, 703), success.value.items.map { it.id })
        assertNull(success.value.nextOffset, "第 3 页已是末页")
        assertConcurrentRequests(
            actual = recorder.map { it.url.parameters["page"] },
            expectedFirst = "1",
            expectedRest = setOf("2", "3"),
            message = "不得越过 total 请求第 4 页",
        )
    }

    @Test
    fun `日期与名称排序走p1的请求形态`() = runTest {
        // 分类浏览：公共标签（多值各自一个参数）与年月直传
        val category = routedRepo(p1 = p1Page(emptyList(), totalPages = 0))
        category.repo.search(
            SearchCriteria(
                subjectType = SubjectType.ANIME,
                sort = SearchSort.DATE,
                filters = SearchFilters(
                    tags = listOf(TagFilter.Public("TV"), TagFilter.Public("漫画改")),
                    airtime = SearchAirtime(2024, 2024, 10),
                ),
            ),
        )
        val dateReq = engines.requests(category.engine).single()
        assertEquals("/p1/subjects", dateReq.url.encodedPath)
        assertEquals("GET", dateReq.method.value)
        assertEquals("date", dateReq.url.parameters["sort"])
        assertEquals(listOf("TV", "漫画改"), dateReq.url.parameters.getAll("tags"))
        assertEquals("2024", dateReq.url.parameters["year"])
        assertEquals("10", dateReq.url.parameters["month"])
        assertNull(dateReq.url.parameters["tagsCat"])

        // 标签浏览：名称排序，tagsCat=subject
        val tag = routedRepo(p1 = p1Page(emptyList(), totalPages = 0))
        tag.repo.search(
            SearchCriteria(
                subjectType = SubjectType.ANIME,
                sort = SearchSort.TITLE,
                filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子"))),
            ),
        )
        val titleReq = engines.requests(tag.engine).single()
        assertEquals("/p1/subjects", titleReq.url.encodedPath)
        assertEquals("title", titleReq.url.parameters["sort"])
        assertEquals("subject", titleReq.url.parameters["tagsCat"])
        assertEquals(listOf("菅野洋子"), titleReq.url.parameters.getAll("tags"))
    }

    @Test
    fun `v0搜索到offset1000硬上限时nextOffset为null且不再请求更大偏移`() = runTest {
        // 服务端未给出 total（v0 的四个分页字段均可省略，缺省为 0）时，nextCursor 只看「空页即结束」，
        // 会一路推进下去 —— 此时 1000 硬上限是唯一能拦住 offset>=1000 请求的东西。
        // 起始页 980 稀疏（1 条 < 12）会触发凑批，最坏会外推到 1080；硬上限必须让它停在 1000。
        val recorder = ConcurrentRequestRecorder()
        val sparseItem = """{"id":1,"type":2,"name":"S1","name_cn":"条目1","summary":"","platform":"TV",
            "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
            "series":false,"nsfw":false,"locked":false,"volumes":0,"eps":0,"total_episodes":0,
            "meta_tags":[],"tags":[],"rating":{"rank":0,"total":0,"count":{},"score":0.0},
            "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}"""
        val engine = MockEngine { request ->
            recorder.record(request)
            val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
            // 无 total 字段；980 给 1 条（稀疏），更远的页码服务端恒空
            val data = if (offset == 980) sparseItem else ""
            respond(
                content = """{"data":[$data],"limit":20,"offset":$offset}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(
            SearchCriteria(query = "测试", subjectType = SubjectType.ANIME, filters = dated),
            offset = 980,
        )

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(listOf(1), success.value.items.map { it.id })
        assertNull(success.value.nextOffset, "980 + 20 = 1000 触硬上限，即使无 total 也不再推进")
        assertFalse(success.value.hasMore, "到达 v0 的 offset 上限即视为到底")
        assertEquals(setOf("980"), recorder.map { it.url.parameters["offset"] }.toSet(), "只应发出起始页这一次请求")
        assertTrue(
            recorder.none { (it.url.parameters["offset"]?.toIntOrNull() ?: 0) >= 1000 },
            "不得发出 offset >= 1000 的请求",
        )
    }

    @Test
    fun `p1搜索到offset1000硬上限时nextOffset为null且不再请求更大偏移`() = runTest {
        // 900 这一页稀疏（1 条 < 12）、服务端 total 为 0 时 nextCursor 只看「空页即结束」，会推进到 1000 再往外凑批；
        // 两个搜索接口共用的硬上限必须让它停住
        val recorder = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            recorder.record(request)
            val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
            respond(
                content = p1SearchPage(if (offset == 900) listOf(1) else emptyList(), total = 0),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(SearchCriteria(query = "测试", subjectType = SubjectType.ANIME), offset = 900)

        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(listOf(1), success.value.items.map { it.id })
        assertNull(success.value.nextOffset, "900 + 100 = 1000 触硬上限")
        assertEquals(listOf("/p1/search/subjects"), recorder.map { it.url.encodedPath }, "只应发出起始页这一次请求")
    }

    @Test
    fun `v0搜索在980之前正常推进_不含硬上限时的游标`() = runTest {
        val recorder = ConcurrentRequestRecorder()
        // 满页 12 条以上：首屏即满足阈值，只取这一页，游标直接来自它（不受凑批影响）
        val items = (1..12).joinToString(",") { id ->
            """{"id":$id,"type":2,"name":"S$id","name_cn":"条目$id","summary":"","platform":"TV",
            "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
            "series":false,"nsfw":false,"locked":false,"volumes":0,"eps":0,"total_episodes":0,
            "meta_tags":[],"tags":[],"rating":{"rank":0,"total":0,"count":{},"score":0.0},
            "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}"""
        }
        val engine = MockEngine { request ->
            recorder.record(request)
            respond(
                content = """{"data":[$items],"total":1000,"limit":20,"offset":${
                    request.url.parameters["offset"] ?: "0"
                }}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val fixture = makeRepoWithEngine(engine)
        val result = fixture.repo.search(
            SearchCriteria(query = "测试", subjectType = SubjectType.ANIME, filters = dated),
            offset = 960,
        )
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertEquals(980, success.value.nextOffset, "960 + 20 = 980，未触 1000 上限，仍有下一页")
        assertTrue(success.value.hasMore)
    }

    @Test
    fun `搜索账号偏好为关时带nsfwfalse_为开时不带`() = runTest {
        val criteria = SearchCriteria(
            subjectType = SubjectType.ANIME,
            filters = SearchFilters(tags = listOf(TagFilter.User("百合"))),
        )

        val off = makeRepoWithEngine(engines.create {
            respond(
                content = """{"data":[],"total":0,"limit":20,"offset":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        })
        off.repo.search(criteria)
        assertEquals(false, off.engine.lastFilter()?.get("nsfw")?.jsonPrimitive?.content?.toBoolean())

        // 偏好为开：仓库从 NsfwFilter 读到 true，请求体里不得出现 nsfw 字段
        val onEngine = engines.create {
            respond(
                content = """{"data":[],"total":0,"limit":20,"offset":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(onEngine)
        val onRepo = testSearchRepository(
            resources,
            SubjectApi(client),
            NextApi(client),
            FakeSubjectDao(),
            FakeRecentSearchDao(),
            testNsfwFilter(resources, showNsfw = true),
        )
        onRepo.search(criteria)
        val body = engines.requests(onEngine).single().body.toByteArray().decodeToString()
        assertFalse(body.contains("\"nsfw\""), "偏好为开时不传 nsfw：$body")
    }
}