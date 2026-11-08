package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.subject.SubjectSection
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionEntity
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.AirPhase
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.WebPageApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** 统计 tab 用到的几项数据：透视页评分拆分、推荐、最近收藏时刻、系列。 */
class SubjectStatsDataTest {
    @get:org.junit.Rule
    val resources = TestResources()


    /** 一部作品指向另一部的一条关联：[relationId] 2 前传、3 续集、6 番外篇。 */
    private data class Edge(val to: Int, val relationId: Int, val type: Int = 2, val order: Int = 0)

    private val requested = ConcurrentLinkedQueue<String>()

    /** 每部作品的关联请求到了没有（非单行本那一路）：[client] 的 `holdUntil` 拿它让一个请求等另一个。 */
    private val relationsArrived = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()

    private fun arrival(id: Int) = relationsArrived.computeIfAbsent(id) { CompletableDeferred() }

    private fun client(
        graph: Map<Int, List<Edge>> = emptyMap(),
        failing: Set<Int> = emptySet(),
        holdUntil: Map<Int, Int> = emptyMap(),
        recs: String = """{"data":[],"total":0}""",
        collects: String = """{"data":[],"total":0}""",
    ): HttpClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            requested += path
            val id = path.removePrefix("/p1/subjects/").substringBefore('/').toInt()
            val (body, status) = when {
                path.endsWith("/relations") && id in failing -> "{}" to HttpStatusCode.InternalServerError
                path.endsWith("/relations") && request.url.parameters["offprint"] == "true" -> """{"data":[],"total":0}""" to HttpStatusCode.OK
                path.endsWith("/relations") && !arrive(id, holdUntil[id]) -> "{}" to HttpStatusCode.InternalServerError
                path.endsWith("/relations") -> {
                    val edges = graph[id].orEmpty()
                    """{"data":[${edges.joinToString(",") { relationItem(it) }}],"total":${edges.size}}""" to HttpStatusCode.OK
                }
                path.endsWith("/recs") -> recs to HttpStatusCode.OK
                path.endsWith("/collects") -> collects to HttpStatusCode.OK
                else -> error("没有预期的请求：$path")
            }
            respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        return resources.apiClient(engine)
    }

    /**
     * 记下 [id] 的关联请求到了；有 [waitFor] 时等那一部的关联请求也到了才答（按真实时间最多等 [WAIT_BUDGET_MS]：
     * 同时在途时几毫秒就等到），等不到返回 false，这次请求按服务端出错答：两个请求不同时在途，等的那一方就取不到。
     */
    private suspend fun arrive(id: Int, waitFor: Int?): Boolean {
        arrival(id).complete(Unit)
        return waitFor == null || withTimeoutOrNull(WAIT_BUDGET_MS) { arrival(waitFor).await() } != null
    }

    private fun relationItem(edge: Edge) =
        """{"subject":{"id":${edge.to},"name":"s${edge.to}","nameCN":"","type":${edge.type},"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":${edge.to}.5,"total":0},"locked":false,"nsfw":false},
            "relation":{"id":${edge.relationId},"en":"","cn":"","jp":"","desc":""},"order":${edge.order}}"""

    private fun slimSubject(id: Int, nsfw: Boolean = false) =
        """{"id":$id,"name":"s$id","nameCN":"","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":7.0,"total":0},"locked":false,"nsfw":$nsfw}"""

    private fun user(id: Int) =
        """{"id":$id,"username":"u$id","nickname":"u$id","avatar":{"small":"","medium":"","large":""},
            "group":10,"sign":"","joinedAt":0,"isFriend":false}"""

    private fun repo(
        client: HttpClient,
        subjectDao: FakeSubjectDao = FakeSubjectDao(),
        sections: FakeSubjectSectionDao = FakeSubjectSectionDao(),
        webPageApi: WebPageApi = WebPageApi(resources.apiClient { error("这里不请求网页") }),
    ) = testSubjectRepository(
        resources,
        SubjectApi(client), NextApi(client), subjectDao, FakeEpisodeDao(), sections, testNsfwFilter(resources),
        webPageApi = webPageApi,
    )

    /** 详情页的做法：先取这部作品自己的关联，再从它出发走系列。 */
    private suspend fun SubjectRepository.seriesOf(id: Int): SubjectSeries {
        val own = getRelations(id).last()
        assertTrue(own is Load.Ready, "这部作品自己的关联应取到：$own")
        return getSeries(id, SubjectType.ANIME, own.value)
    }

    @Test
    fun `系列：沿前传往前、沿续集往后，只走同类型，最早的在最前`() = runTest {
        val graph = mapOf(
            3 to listOf(Edge(2, relationId = 2), Edge(4, relationId = 3), Edge(40, relationId = 6), Edge(50, relationId = 3, type = 1)),
            2 to listOf(Edge(1, relationId = 2), Edge(3, relationId = 3)),
            1 to listOf(Edge(2, relationId = 3)),
            4 to listOf(Edge(3, relationId = 2), Edge(6, relationId = 3, order = 2), Edge(5, relationId = 3, order = 1)),
            5 to emptyList(),
            6 to emptyList(),
        )

        val series = repo(client(graph)).seriesOf(3)

        assertEquals(listOf(1, 2), series.before.map { it.id })
        assertEquals(listOf(4, 5, 6), series.after.map { it.id }, "同一步的几部按服务端排序值排")
        assertEquals(listOf(1.5, 2.5), series.before.map { it.score }, "评分随关联一起带回")
    }

    @Test
    fun `系列：前传与续集两个方向同时走，不等一个方向走完再走另一个`() = runTest {
        val graph = mapOf(
            3 to listOf(Edge(2, relationId = 2), Edge(4, relationId = 3)),
            2 to listOf(Edge(1, relationId = 2)),
            4 to listOf(Edge(5, relationId = 3)),
        )

        // 往前那一步（作品 2 的关联）要等往后那一步（作品 4 的关联）也发出才答
        val series = repo(client(graph, holdUntil = mapOf(2 to 4))).seriesOf(3)

        assertEquals(listOf(1, 2), series.before.map { it.id }, "往前那一步应与往后那一步同时在途")
        assertEquals(listOf(4, 5), series.after.map { it.id })
    }

    @Test
    fun `系列：同一部两个方向都走到时只留在前面`() = runTest {
        // 数据成环：往前走到 1，往后从 4 也走到 1
        val graph = mapOf(
            3 to listOf(Edge(2, relationId = 2), Edge(4, relationId = 3)),
            2 to listOf(Edge(1, relationId = 2)),
            4 to listOf(Edge(1, relationId = 3)),
            1 to emptyList(),
        )

        val series = repo(client(graph)).seriesOf(3)

        assertEquals(listOf(1, 2), series.before.map { it.id })
        assertEquals(listOf(4), series.after.map { it.id }, "一部只出现一次：系列横排按 id 作 key")
    }

    @Test
    fun `系列：每个方向最多走 8 步`() = runTest {
        val graph = (1..20).associateWith { listOf(Edge(it + 1, relationId = 3)) }

        val series = repo(client(graph)).seriesOf(1)

        assertEquals((2..9).toList(), series.after.map { it.id })
        assertEquals(emptyList(), series.before)
    }

    @Test
    fun `系列：更远的一步取不到，那个方向就停在那里`() = runTest {
        val graph = mapOf(3 to listOf(Edge(4, relationId = 3)), 4 to listOf(Edge(5, relationId = 3)))

        val series = repo(client(graph, failing = setOf(4))).seriesOf(3)

        assertEquals(listOf(4), series.after.map { it.id })
    }

    @Test
    fun `系列：新鲜缓存里的关联缺评分字段时按未命中重新拉取，相连的几部带回各自的分`() = runTest {
        val now = 10_000L
        val sections = FakeSubjectSectionDao()
        val related = SubjectRelationBo(id = 4, type = SubjectType.ANIME, name = "s4", nameCn = "", relationId = 3, score = 4.5)
        val withoutScore = JsonArray(
            Json.encodeToJsonElement(ListSerializer(SubjectRelationBo.serializer()), listOf(related))
                .jsonArray.map { JsonObject(it.jsonObject - "score") },
        )
        sections.seed(SubjectSectionEntity(3, SubjectSection.RELATIONS.name, 0, json = withoutScore.toString(), updatedAt = now))
        val client = client(mapOf(3 to listOf(Edge(4, relationId = 3)), 4 to emptyList()))
        val repo = testSubjectRepository(
            resources,
            SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), sections, testNsfwFilter(resources),
            clock = AppClock { now },
        )

        val series = repo.seriesOf(3)

        assertTrue("/p1/subjects/3/relations" in requested, "缺字段的缓存应按未命中重新拉取")
        assertEquals(listOf(4.5), series.after.map { it.score })
    }

    @Test
    fun `推荐：按服务端顺序，作品进条目表，缓存只存 id，NSFW 在读取时滤掉`() = runTest {
        val subjects = FakeSubjectDao()
        val sections = FakeSubjectSectionDao()
        val recs = """{"data":[
            {"subject":${slimSubject(30)},"sim":0,"count":0},
            {"subject":${slimSubject(10, nsfw = true)},"sim":0,"count":0},
            {"subject":${slimSubject(20)},"sim":0,"count":0}],"total":3}"""

        val result = repo(client(recs = recs), subjects, sections).getRecs(8).last()

        assertTrue(result is Load.Ready, "应成功：$result")
        assertEquals(listOf(30, 20), result.value.map { it.id })
        assertEquals(setOf(10, 20, 30), subjects.store.keys)
        assertEquals("[30,10,20]", sections.get(8, "RECS", 0)?.json)

        requested.clear()
        val cached = repo(client(), subjects, sections).getRecs(8).last()
        assertEquals(listOf(30, 20), (cached as Load.Ready).value.map { it.id })
        assertEquals(emptyList(), requested.toList(), "新鲜的缓存不再请求")
    }

    @Test
    fun `最近收藏时刻：p1 的秒换成毫秒，新的在前`() = runTest {
        val collects = """{"data":[
            {"user":${user(1)},"interest":{"id":1,"rate":0,"type":2,"comment":"","tags":[],"updatedAt":1791858249}},
            {"user":${user(2)},"interest":{"id":2,"rate":8,"type":3,"comment":"","tags":[],"updatedAt":1791626240}}],"total":42920}"""

        val result = repo(client(collects = collects)).getRecentCollectTimes(8).last()

        assertEquals(Load.Ready(listOf(1_791_858_249_000L, 1_791_626_240_000L)), result)
        assertTrue(requested.single().endsWith("/collects"))
    }

    @Test
    fun `评分拆分：从透视页取，按收藏状态与放送阶段认组`() = runTest {
        val page = """<script>var CHART_SETS = {"interest_type":{"data":[{"title":9,"2":7203,"5":11},{"title":1,"2":35}]},
            "airdate":{"data":[{"title":8,"3":9046}]}};</script>"""
        val pages = ConcurrentLinkedQueue<String>()
        val web = WebPageApi(
            resources.apiClient { request ->
                pages += request.url.toString()
                respond(page, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Text.Html.toString()))
            },
        )

        val result = repo(client(), webPageApi = web).getRatingSplit(317613).last()

        assertTrue(result is Load.Ready, "应成功：$result")
        assertEquals(listOf(35, 0, 0, 0, 0, 0, 0, 0, 7203, 0), result.value.byCollection[CollectionType.DONE])
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 0, 11, 0), result.value.byCollection[CollectionType.DROPPED])
        assertEquals(setOf(AirPhase.AFTER), result.value.byAirPhase.keys)
        assertEquals(listOf("https://bgm.tv/subject/317613/stats"), pages.toList())
    }
}
