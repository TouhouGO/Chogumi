package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexMembership
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 目录写操作与勾选状态：请求的方法、路径与请求体照 p1 契约；勾选状态按目标的类别逐个目录查、取全每一页。
 * JSON 形状照 p1 接口文档裁剪，目录与条目换成虚构值。
 */
class IndexRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private val requests = ConcurrentRequestRecorder()

    private class Setup(val repository: IndexRepository, val tokenStore: TokenStore)

    /** 按「方法 路径?查询」给响应，没给的回空页。 */
    private suspend fun TestScope.setup(
        loggedIn: Boolean = true,
        responses: Map<String, String> = emptyMap(),
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Setup {
        val engine = MockEngine { request ->
            requests.record(request)
            val key = "${request.method.value} ${request.url.encodedPath}" +
                request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
            respond(
                responses[key] ?: responses["${request.method.value} ${request.url.encodedPath}"] ?: """{"data":[],"total":0}""",
                status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val tokenStore = testTokenStore(scope = backgroundScope)
        if (loggedIn) tokenStore.completeSession(tokenStore.beginSession("token"), userId = 7, username = "fixture_user")
        return Setup(IndexRepository(IndexApi(client), UserApi(client), tokenStore), tokenStore)
    }

    private fun HttpRequestData.json(): JsonObject =
        Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

    @Test
    fun `新建目录_POST名称介绍与私密_回新目录的id`() = runTest {
        val setup = setup(responses = mapOf("POST /p1/indexes" to """{"id":91}"""))

        val result = setup.repository.createIndex(" 私密 test ", "介绍", isPrivate = true)

        assertEquals(Outcome.Success(91), result)
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("""{"title":"私密 test","desc":"介绍","private":true}""", request.json().toString())
    }

    @Test
    fun `改目录_PATCH三项一起写_删目录_DELETE`() = runTest {
        val setup = setup(responses = mapOf("PATCH /p1/indexes/91" to "{}", "DELETE /p1/indexes/91" to "{}"))

        assertEquals(Outcome.Success(Unit), setup.repository.updateIndex(91, "公开 test", "", isPrivate = false))
        assertEquals(Outcome.Success(Unit), setup.repository.deleteIndex(91))

        val sent = requests.toList()
        assertEquals(listOf(HttpMethod.Patch, HttpMethod.Delete), sent.map { it.method })
        assertEquals("""{"title":"公开 test","desc":"","private":false}""", sent[0].json().toString())
        assertEquals("/p1/indexes/91", sent[1].url.encodedPath)
    }

    @Test
    fun `收录_PUT类别与id_不写评语时不带comment_回收录行的id`() = runTest {
        val setup = setup(responses = mapOf("PUT /p1/indexes/91/related" to """{"id":5001}"""))

        val result = setup.repository.addEntry(91, IndexTarget(IndexCategory.GROUP_TOPIC, 472971))

        assertEquals(Outcome.Success(5001), result)
        assertEquals(HttpMethod.Put, requests.single().method)
        assertEquals("""{"cat":5,"sid":472971}""", requests.single().json().toString())
    }

    @Test
    fun `移出按收录行的id_改评语时排序与评语一起写`() = runTest {
        val setup = setup(responses = mapOf("DELETE /p1/indexes/91/related/5001" to "{}", "PATCH /p1/indexes/91/related/5001" to "{}"))

        assertEquals(Outcome.Success(Unit), setup.repository.removeEntry(91, 5001))
        assertEquals(Outcome.Success(Unit), setup.repository.updateEntry(91, 5001, order = 3, comment = "好看"))

        val sent = requests.toList()
        assertEquals("/p1/indexes/91/related/5001", sent[0].url.encodedPath)
        assertEquals(HttpMethod.Delete, sent[0].method)
        assertEquals("""{"order":3,"comment":"好看"}""", sent[1].json().toString())
    }

    @Test
    fun `未登录_写操作不发请求_是NotLoggedIn`() = runTest {
        val setup = setup(loggedIn = false)

        assertEquals(Outcome.Failed(Failure.NotLoggedIn), setup.repository.createIndex("t", "", isPrivate = false))
        assertEquals(Outcome.Failed(Failure.NotLoggedIn), setup.repository.addEntry(1, IndexTarget(IndexCategory.SUBJECT, 2)))
        assertEquals(Outcome.Failed(Failure.NotLoggedIn), setup.repository.membershipsOf(IndexTarget(IndexCategory.SUBJECT, 2)))
        assertTrue(requests.none())
    }

    @Test
    fun `服务端5xx_写操作以Http失败返回_不抛`() = runTest {
        val setup = setup(status = HttpStatusCode.InternalServerError)

        val result = setup.repository.addEntry(91, IndexTarget(IndexCategory.SUBJECT, 265))

        assertEquals(Outcome.Failed(Failure.Http(500)), result)
    }

    @Test
    fun `勾选状态_我的每个目录按目标类别取全_找到同一个id才算在里面`() = runTest {
        val setup = setup(
            responses = mapOf(
                "GET /p1/users/fixture_user/indexes" to """{"data":[${slimIndex(1, "私密 test", private = true)},${slimIndex(2, "公开 test")}],"total":2}""",
                // 目录 1：第一页 100 条里没有，第二页才有（id 265，收录行 9001）
                "GET /p1/indexes/1/related?cat=0&limit=100&offset=0" to relatedPage(sids = (1000 until 1100).toList(), total = 101),
                "GET /p1/indexes/1/related?cat=0&limit=100&offset=100" to relatedPage(sids = listOf(265), total = 101, firstRowId = 9001),
                // 目录 2：收录了别的作品
                "GET /p1/indexes/2/related?cat=0&limit=100&offset=0" to relatedPage(sids = listOf(266), total = 1),
            ),
        )

        val result = setup.repository.membershipsOf(IndexTarget(IndexCategory.SUBJECT, 265))

        val memberships = assertIs<Outcome.Success<List<IndexMembership>>>(result).value
        assertEquals(listOf(1, 2), memberships.map { it.index.id })
        assertEquals(listOf(9001, null), memberships.map { it.relatedId })
        assertEquals(listOf(true, false), memberships.map { it.index.isPrivate }, "私密标记跟着列表项带上")
        // 各目录的查询并行发出：按集合比，不看到达顺序
        val related = requests.filter { it.url.encodedPath.endsWith("/related") }
        assertEquals(
            setOf("/p1/indexes/1/related@0", "/p1/indexes/1/related@100", "/p1/indexes/2/related@0"),
            related.map { "${it.url.encodedPath}@${it.url.parameters["offset"]}" }.toSet(),
            "目录 1 取了第二页",
        )
        assertTrue(related.all { it.url.parameters["cat"] == "0" }, "只按目标的类别取")
    }

    @Test
    fun `写成功才让writes加一_失败与未登录不加`() = runTest {
        val ok = setup(responses = mapOf("POST /p1/indexes" to """{"id":91}""", "PUT /p1/indexes/91/related" to """{"id":5001}"""))
        val before = ok.repository.writes.value

        ok.repository.createIndex("t", "", isPrivate = false)
        ok.repository.addEntry(91, IndexTarget(IndexCategory.SUBJECT, 265))
        ok.repository.removeEntry(91, 5001)
        assertEquals(before + 3, ok.repository.writes.value, "三次写成功各加一")

        val failing = setup(status = HttpStatusCode.InternalServerError)
        failing.repository.deleteIndex(91)
        assertEquals(0, failing.repository.writes.value, "写失败不加")

        val guest = setup(loggedIn = false)
        guest.repository.createIndex("t", "", isPrivate = false)
        assertEquals(0, guest.repository.writes.value, "未登录不加")
    }

    @Test
    fun `收藏与取消收藏目录也是目录写_writes各加一`() = runTest {
        val setup = setup(responses = mapOf("PUT /p1/collections/indexes/91" to "{}", "DELETE /p1/collections/indexes/91" to "{}"))
        val before = setup.repository.writes.value

        assertEquals(Outcome.Success(Unit), setup.repository.setCollected(91, collected = true))
        assertEquals(before + 1, setup.repository.writes.value, "收藏后「收藏的」列表要重取")
        assertEquals(Outcome.Success(Unit), setup.repository.setCollected(91, collected = false))
        assertEquals(before + 2, setup.repository.writes.value, "取消收藏后同样重取")
        assertEquals(listOf(HttpMethod.Put, HttpMethod.Delete), requests.toList().map { it.method })
    }

    @Test
    fun `目录头部按取数时的账号判定是不是自己的_私密标记带上`() = runTest {
        val setup = setup(
            responses = mapOf(
                "GET /p1/indexes/91" to indexJson(id = 91, uid = 7, private = true),
                "GET /p1/indexes/92" to indexJson(id = 92, uid = 8, private = false),
            ),
        )

        val mine = setup.repository.getIndex(91)
        val others = setup.repository.getIndex(92)

        assertTrue(mine.isOwn, "创建者是登录账号 7")
        assertTrue(mine.isPrivate)
        assertEquals(false, others.isOwn, "别人的目录")
        assertEquals(false, others.isPrivate)
    }

    private fun indexJson(id: Int, uid: Int, private: Boolean) =
        """{"id":$id,"uid":$uid,"type":0,"title":"t","desc":"","private":$private,"total":0,"replies":0,"collects":0,""" +
            """"stats":{"subject":{}},"award":0,"createdAt":1,"updatedAt":1}"""

    private fun slimIndex(id: Int, title: String, private: Boolean = false) =
        """{"id":$id,"uid":7,"type":0,"title":"$title","private":$private,"total":0,"stats":{"subject":{}},"createdAt":1,"updatedAt":1}"""

    private fun relatedPage(sids: List<Int>, total: Int, firstRowId: Int = 1) = sids.mapIndexed { i, sid ->
        """{"id":${firstRowId + i},"cat":0,"rid":0,"type":2,"sid":$sid,"order":$i,"comment":"","award":"","createdAt":1,
            "subject":{"id":$sid,"name":"s$sid","nameCN":"","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false}}"""
    }.joinToString(",", prefix = """{"data":[""", postfix = """],"total":$total}""")
}
