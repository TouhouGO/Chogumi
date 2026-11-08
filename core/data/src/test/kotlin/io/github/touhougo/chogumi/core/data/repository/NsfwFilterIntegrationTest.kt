package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest

/**
 * NSFW 过滤在「列条目」入口上的行为契约。
 *
 * 判定真源是 core:model 的 `isSubjectVisible`，过滤真源是 [NsfwFilter]；
 * 本文件验证各入口**接入**了它，且过滤不干扰分页游标（游标按服务端原始页算）。
 */
class NsfwFilterIntegrationTest {
    @get:org.junit.Rule
    val resources = TestResources()


    /** 热门条目项（p1 `{"subject":...,"count":N}`）。 */

    /** 日历条目项（p1 `{"subject":...,"watchers":N}`）。 */
    private fun calendarItem(id: Int, nsfw: Boolean) = """{"subject":${p1Subject(id, nsfw)},"watchers":5}"""

    /** p1 摘要主体（不带外层包装）。 */
    private fun p1Subject(id: Int, nsfw: Boolean) =
        """{"id":$id,"name":"S$id","nameCN":"条目$id","type":2,"info":"","metaTags":[],
            "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
            "rating":{"rank":0,"count":[],"score":7.0,"total":1},
            "locked":false,"nsfw":$nsfw}"""

    /** 一条 v0 完整条目（nsfw 可控），用于排行榜。 */
    private fun v0Subject(id: Int, nsfw: Boolean) =
        """{"id":$id,"type":2,"name":"S$id","name_cn":"条目$id","summary":"s",
            "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},"eps":12,
            "series":false,"nsfw":$nsfw,"locked":false,"platform":"TV","volumes":0,"total_episodes":12,
            "meta_tags":[],"tags":[],
            "rating":{"rank":1,"total":1,"count":{},"score":7.0},
            "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}"""

    private fun client(handler: MockEngine) = resources.apiClient(handler)

    private fun engineOf(content: String) = MockEngine {
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }

    // ---- CalendarRepository ----

    @Test
    fun `日历网络路径_关开关剔除NSFW`() = runTest {
        val content = """{"1":[${calendarItem(1, nsfw = false)},${calendarItem(2, nsfw = true)}]}"""
        val repo = testCalendarRepository(
            resources,
            NextApi(client(engineOf(content))), FakeCalendarDao(), FakeSubjectDao(), testNsfwFilter(resources, showNsfw = false),
        )
        val result = repo.getCalendar().first { it !is Load.Pending }
        assertEquals(listOf(1), assertIs<Load.Ready<Map<Int, List<io.github.touhougo.chogumi.core.model.CalendarItemBo>>>>(result)
            .value.values.flatten().map { it.subject.id })
    }

    @Test
    fun `日历缓存路径_关开关剔除NSFW`() = runTest {
        // 直接预置缓存全量（含一条 NSFW），缓存新鲜 → 只出缓存拍，不打网络。
        val dao = FakeCalendarDao()
        dao.upsertAll(
            listOf(
                io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity(weekday = 1, subjectId = 1, name = "普通", updatedAt = System.currentTimeMillis()),
                io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity(weekday = 1, subjectId = 2, name = "限制级", updatedAt = System.currentTimeMillis(), nsfw = true),
            ),
        )
        val networkCalls = AtomicInteger()
        val engine = MockEngine { networkCalls.incrementAndGet(); respond("{}", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString())) }
        val repo = testCalendarRepository(
            resources,
            NextApi(client(engine)), dao, FakeSubjectDao(), testNsfwFilter(resources, showNsfw = false),
        )
        val results = mutableListOf<Load<Map<Int, List<io.github.touhougo.chogumi.core.model.CalendarItemBo>>>>()
        repo.getCalendar().collect { results.add(it) }
        val cached = assertIs<Load.Ready<Map<Int, List<io.github.touhougo.chogumi.core.model.CalendarItemBo>>>>(results.single())
        assertTrue(cached.fromCache)
        assertEquals(listOf(1), cached.value.values.flatten().map { it.subject.id }, "缓存路径同样不得漏放 NSFW")
        assertEquals(0, networkCalls.get())
    }

    @Test
    fun `日历缓存路径_开开关保留NSFW且缓存仍存全量`() = runTest {
        val dao = FakeCalendarDao()
        dao.upsertAll(
            listOf(
                io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity(weekday = 1, subjectId = 1, name = "普通", updatedAt = System.currentTimeMillis()),
                io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity(weekday = 1, subjectId = 2, name = "限制级", updatedAt = System.currentTimeMillis(), nsfw = true),
            ),
        )
        val repo = testCalendarRepository(
            resources,
            NextApi(client(engineOf("{}"))), dao, FakeSubjectDao(), testNsfwFilter(resources, showNsfw = true),
        )
        val result = repo.getCalendar().first { it !is Load.Pending }
        assertEquals(
            listOf(1, 2),
            assertIs<Load.Ready<Map<Int, List<io.github.touhougo.chogumi.core.model.CalendarItemBo>>>>(result)
                .value.values.flatten().map { it.subject.id },
            "开关打开时 NSFW 条目必须保留",
        )
    }

    // ---- SubjectRepository.getRelations ----

    private fun relationItem(id: Int, relationId: Int, nsfw: Boolean) =
        """{"subject":{"id":$id,"name":"s$id","nameCN":"","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":$nsfw},
            "relation":{"id":$relationId,"en":"","cn":"","jp":"","desc":""},"order":0}"""

    /** 关联两路（offprint true/false）共用一份响应；本测试不关心 offprint 区分。 */
    private fun relationsClient(offprintBody: String, normalBody: String): HttpClient {
        val engine = MockEngine { request ->
            val body = if (request.url.parameters["offprint"] == "true") offprintBody else normalBody
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        return resources.apiClient(engine)
    }

    private fun relationsRepo(client: HttpClient, showNsfw: Boolean) = testSubjectRepository(
        resources,
        SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources, showNsfw),
    )

    @Test
    fun `关联条目_关开关剔除NSFW_开开关保留`() = runTest {
        val body = """{"data":[${relationItem(1, 3, nsfw = false)},${relationItem(2, 2, nsfw = true)}],"total":2}"""
        val empty = """{"data":[],"total":0}"""

        val off = relationsRepo(relationsClient(empty, body), showNsfw = false).getRelations(8).last()
        assertEquals(listOf(1), assertIs<Load.Ready<List<SubjectRelationBo>>>(off).value.map { it.id })

        val on = relationsRepo(relationsClient(empty, body), showNsfw = true).getRelations(8).last()
        assertEquals(listOf(1, 2), assertIs<Load.Ready<List<SubjectRelationBo>>>(on).value.map { it.id })
    }

    @Test
    fun `关联条目_缓存存全量_命中缓存后切换开关依然正确`() = runTest {
        val body = """{"data":[${relationItem(1, 3, nsfw = false)},${relationItem(2, 2, nsfw = true)}],"total":2}"""
        val empty = """{"data":[],"total":0}"""
        val filter = MutableTestNsfwFilter(resources)
        val sections = FakeSubjectSectionDao()
        val client = relationsClient(empty, body)
        val repo = testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), sections, filter.nsfwFilter)

        filter.set(false)
        val off = repo.getRelations(8).last()
        assertEquals(listOf(1), assertIs<Load.Ready<List<SubjectRelationBo>>>(off).value.map { it.id })

        filter.set(true)
        val on = repo.getRelations(8).last()
        val onSuccess = assertIs<Load.Ready<List<SubjectRelationBo>>>(on)
        assertTrue(onSuccess.fromCache, "第二次必须命中本地缓存")
        assertEquals(listOf(1, 2), onSuccess.value.map { it.id })
    }

    // ---- MonoRepository 出演作品 ----

    private fun castItem(id: Int, nsfw: Boolean) =
        """{"subject":{"id":$id,"name":"S$id","nameCN":"作品$id","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":$nsfw},
            "casts":[],"type":2}"""

    /** 人物参与作品项（`PersonWorkDto`：subject + positions）。 */
    private fun workItem(id: Int, nsfw: Boolean) =
        """{"subject":{"id":$id,"name":"S$id","nameCN":"作品$id","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":$nsfw},
            "positions":[{"type":{"id":1,"en":"CV","cn":"声优","jp":"声優"},"summary":"","appearEps":""}]}"""

    /** 人物演过的角色（`PersonCharacterDto`：character + relations）。 */
    private fun characterItem(id: Int, nsfw: Boolean) =
        """{"character":{"id":$id,"name":"C$id","nameCN":"角色$id","role":1,"info":"","comment":0,"lock":false,"nsfw":$nsfw},
            "relations":[{"subject":{"id":9,"name":"S9","nameCN":"作品","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false},"type":1}]}"""

    /**
     * 出演作品端点的最小仓库：`pathSuffix` 命中时回该页数据，其余端点回空。
     * 两个用例分别走 `/casts`（角色出演）与 `/works`（人物作品），语义一致只换路径。
     */
    private fun monoRepo(showNsfw: Boolean, items: String, pathSuffix: String = "/casts"): MonoRepository =
        monoRepo(testNsfwFilter(resources, showNsfw), items, pathSuffix)

    private fun monoRepo(nsfwFilter: NsfwFilter, items: String, pathSuffix: String = "/casts"): MonoRepository {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = if (path.endsWith(pathSuffix)) """{"data":[$items],"total":2}""" else """{"data":[],"total":0}"""
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        // 出演作品端点无需令牌，TokenStore 仅为构造所需。
        val store = testTokenStore(resources)
        return testMonoRepository(resources, io.github.touhougo.chogumi.core.network.api.MonoApi(client), store, nsfwFilter)
    }

    @Test
    fun `角色出演作品_关开关剔除NSFW_开开关保留`() = runTest {
        val casts = "${castItem(1, nsfw = false)},${castItem(2, nsfw = true)}"

        val off = monoRepo(showNsfw = false, items = casts).getAllWorks(MonoType.CHARACTER, id = 1)
        assertEquals(listOf(1), off.map { it.id })

        val on = monoRepo(showNsfw = true, items = casts).getAllWorks(MonoType.CHARACTER, id = 1)
        assertEquals(listOf(1, 2), on.map { it.id })
    }

    @Test
    fun `人物参与作品_关开关剔除NSFW_开开关保留`() = runTest {
        val works = "${workItem(1, nsfw = false)},${workItem(2, nsfw = true)}"

        val off = monoRepo(showNsfw = false, items = works, pathSuffix = "/works").getAllWorks(MonoType.PERSON, id = 2)
        assertEquals(listOf(1), off.map { it.id })

        val on = monoRepo(showNsfw = true, items = works, pathSuffix = "/works").getAllWorks(MonoType.PERSON, id = 2)
        assertEquals(listOf(1, 2), on.map { it.id })
    }

    @Test
    fun `全部作品的内存缓存在读出时过滤_切换开关不必重取`() = runTest {
        val filter = MutableTestNsfwFilter(resources)
        val casts = "${castItem(1, nsfw = false)},${castItem(2, nsfw = true)}"
        val repo = monoRepo(filter.nsfwFilter, items = casts)

        filter.set(false)
        assertEquals(listOf(1), repo.getAllWorks(MonoType.CHARACTER, id = 1).map { it.id })

        filter.set(true)
        assertEquals(listOf(1, 2), repo.getAllWorks(MonoType.CHARACTER, id = 1).map { it.id })
    }

    @Test
    fun `人物演过的角色_关开关剔除NSFW_开开关保留`() = runTest {
        val characters = "${characterItem(1, nsfw = false)},${characterItem(2, nsfw = true)}"

        val off = monoRepo(showNsfw = false, items = characters).getPersonCharacters(id = 2)
        assertEquals(listOf(1), off.items.map { it.id })

        val on = monoRepo(showNsfw = true, items = characters).getPersonCharacters(id = 2)
        assertEquals(listOf(1, 2), on.items.map { it.id })
        assertEquals("作品", on.items.first().subjectName, "作品名取第一段关系的条目")
    }

    // ---- SearchRepository（browse 与 searchPage 两路）----

    /** v0 搜索/浏览条目 JSON（nsfw 可控）。 */
    private fun searchSubject(id: Int, nsfw: Boolean) =
        """{"id":$id,"type":2,"name":"S$id","name_cn":"条目$id","summary":"s",
            "images":{"large":"l","common":"c","medium":"m","small":"s","grid":"g"},
            "series":false,"nsfw":$nsfw,"locked":false,"platform":"TV","volumes":0,"eps":0,"total_episodes":0,
            "meta_tags":[],"tags":[],
            "rating":{"rank":0,"total":0,"count":{},"score":0.0},
            "collection":{"wish":0,"collect":0,"doing":0,"on_hold":0,"dropped":0}}"""

    private fun searchRepo(body: String, showNsfw: Boolean) = testSearchRepository(
        resources,
        SubjectApi(client(engineOf(body))), NextApi(client(engineOf(body))),
        FakeSubjectDao(), FakeRecentSearchDao(), testNsfwFilter(resources, showNsfw),
    )

    @Test
    fun `两个搜索接口_关开关剔除NSFW_开开关保留`() = runTest {
        val p1Body = """{"data":[${p1Subject(1, nsfw = false)},${p1Subject(2, nsfw = true)}],"total":2}"""
        val criteria = io.github.touhougo.chogumi.core.model.SearchCriteria(
            query = "测试", subjectType = SubjectType.ANIME,
        )

        val off = searchRepo(p1Body, showNsfw = false).search(criteria)
        assertEquals(listOf(1), assertIs<Outcome.Success<PagedResult<SubjectBo>>>(off).value.items.map { it.id })

        val on = searchRepo(p1Body, showNsfw = true).search(criteria)
        assertEquals(listOf(1, 2), assertIs<Outcome.Success<PagedResult<SubjectBo>>>(on).value.items.map { it.id })

        // 带时间条件的走 v0，同一个过滤
        val v0Body = """{"data":[${searchSubject(1, nsfw = false)},${searchSubject(2, nsfw = true)}],"total":2,"limit":20,"offset":0}"""
        val dated = criteria.copy(filters = io.github.touhougo.chogumi.core.model.SearchFilters(airtime = io.github.touhougo.chogumi.core.model.SearchAirtime(2003, 2003)))
        val datedOff = searchRepo(v0Body, showNsfw = false).search(dated)
        assertEquals(listOf(1), assertIs<Outcome.Success<PagedResult<SubjectBo>>>(datedOff).value.items.map { it.id })
    }

    @Test
    fun `分类浏览_关开关剔除NSFW`() = runTest {
        // 热度排序仍走 p1 browse（total 为页数）
        val body = """{"data":[${p1Subject(1, nsfw = false)},${p1Subject(2, nsfw = true)}],"total":1}"""
        val criteria = io.github.touhougo.chogumi.core.model.SearchCriteria(
            subjectType = SubjectType.ANIME,
            sort = io.github.touhougo.chogumi.core.model.SearchSort.TRENDS,
        )
        val result = searchRepo(body, showNsfw = false).search(criteria)
        assertEquals(listOf(1), assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result).value.items.map { it.id })
    }

    @Test
    fun `分类浏览_默认标注数走p1搜索_关开关剔除NSFW`() = runTest {
        // 默认排序（标注数）走 p1 搜索：同一分类条件下 NSFW 同样被剔除
        val body = """{"data":[${p1Subject(1, nsfw = false)},${p1Subject(2, nsfw = true)}],"total":2}"""
        val criteria = io.github.touhougo.chogumi.core.model.SearchCriteria(subjectType = SubjectType.ANIME)
        val result = searchRepo(body, showNsfw = false).search(criteria)
        assertEquals(listOf(1), assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result).value.items.map { it.id })
    }

    @Test
    fun `搜索整页皆NSFW被剔除后游标仍前进`() = runTest {
        // 服务端 total=1000、连续多页全 NSFW：过滤后为空，最多取 6 页达到上限停止，游标仍按请求跨度（100）推进至 600。
        val data = (1..20).joinToString(",") { p1Subject(it, nsfw = true) }
        val body = """{"data":[$data],"total":1000}"""
        val criteria = io.github.touhougo.chogumi.core.model.SearchCriteria(query = "测试", subjectType = SubjectType.ANIME)
        val result = searchRepo(body, showNsfw = false).search(criteria, offset = 0)
        val success = assertIs<Outcome.Success<PagedResult<SubjectBo>>>(result)
        assertTrue(success.value.items.isEmpty())
        assertEquals(1000, success.value.total, "total 保持服务端值")
        assertEquals(600, success.value.nextOffset, "首批 6 页皆空达到上限停止，游标按请求跨度推进至 600")
        assertTrue(success.value.hasMore)
    }
}
