package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.model.v0.SearchFilterDto
import io.github.touhougo.chogumi.core.network.model.v0.SearchRequestDto
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class SubjectApiTest {

    private val subjectJson = """
        {
          "id": 8,
          "type": 2,
          "name": "こんにちは アニメ",
          "name_cn": "你好 Anime",
          "summary": "一个测试条目",
          "series": false,
          "nsfw": false,
          "locked": false,
          "date": "1998-01-01",
          "platform": "TV",
          "images": {"large": "https://lain.bgm.tv/pic/cover/l/98.jpg", "common": "", "medium": "", "small": "", "grid": ""},
          "volumes": 0,
          "eps": 26,
          "total_episodes": 26,
          "rating": {"rank": 0, "total": 10, "count": {"1": 0, "10": 1}, "score": 7.8},
          "collection": {"wish": 5, "collect": 3, "doing": 2, "on_hold": 1, "dropped": 0},
          "meta_tags": ["TV"],
          "tags": [{"name": "原创", "count": 12, "total_count": 100}]
        }
    """.trimIndent()

    private val episodesJson = """
        {
          "data": [
            {"id": 8, "subject_id": 8, "type": 0, "name": "第一话", "name_cn": "第1话",
             "sort": 1, "ep": 1, "airdate": "1998-01-01", "comment": 0,
             "duration": "25m", "desc": "", "disc": 0, "duration_seconds": 1500}
          ],
          "total": 1, "limit": 100, "offset": 0
        }
    """.trimIndent()

    private fun makeApi(content: String): Pair<SubjectApi, MockEngine> {
        val engine = MockEngine {
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        return SubjectApi(client) to engine
    }

    @Test
    fun `getSubject 解析全量字段`() = runTest {
        val (api, engine) = makeApi(subjectJson)
        val subject = api.getSubject(8)
        assertEquals(8, subject.id)
        assertEquals("你好 Anime", subject.nameCn)
        assertEquals(7.8, subject.rating.score)
        assertEquals(5, subject.collection.wish)
        assertEquals(12, subject.tags.first().count)
        assertEquals("/v0/subjects/8", engine.requestHistory.single().url.encodedPath)
    }

    @Test
    fun `getEpisodes 使用 v0 episodes 端点并携带 subject_id 参数`() = runTest {
        val (api, engine) = makeApi(episodesJson)
        val page = api.getEpisodes(subjectId = 8)
        assertEquals(1, page.data.size)
        assertEquals("第1话", page.data.first().nameCn)
        val request = engine.requestHistory.single()
        assertEquals("/v0/episodes", request.url.encodedPath)
        assertEquals("8", request.url.parameters["subject_id"])
    }

    @Test
    fun `searchSubjects 请求路径与解析`() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {"data":[{"id":8,"type":2,"name":"こんにちは","name_cn":"你好","summary":"s",
                      "images":{"large":"l.jpg","common":"c.jpg","medium":"m.jpg","small":"s.jpg","grid":"g.jpg"},"eps":26,
                      "series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"total_episodes":26,
                      "meta_tags":[],"tags":[],
                      "rating":{"rank":1,"total":5,"count":{},"score":8.0},
                      "collection":{"wish":1,"collect":2,"doing":3,"on_hold":0,"dropped":0}}],
                     "total":1,"limit":20,"offset":0}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        val page = SubjectApi(client).searchSubjects(
            SearchRequestDto(keyword = "你好", filter = SearchFilterDto(type = listOf(2)))
        )
        assertEquals(1, page.data.size)
        assertEquals("你好", page.data.first().nameCn)
        val request = engine.requestHistory.single()
        assertEquals("/v0/search/subjects", request.url.encodedPath)
        assertEquals("POST", request.method.value)
    }

    @Test
    fun `browseSubjects 携带排序参数并解析分页`() = runTest {
        val engine = MockEngine {
            respond(
                content = """
                    {"data":[{"id":8,"type":2,"name":"n","name_cn":"你好","summary":"s",
                      "images":{"large":"l.jpg","common":"c.jpg","medium":"m.jpg","small":"s.jpg","grid":"g.jpg"},"eps":26,
                      "series":false,"nsfw":false,"locked":false,"platform":"TV","volumes":0,"total_episodes":26,
                      "meta_tags":[],"tags":[],
                      "rating":{"rank":1,"total":5,"count":{},"score":8.0},
                      "collection":{"wish":1,"collect":2,"doing":3,"on_hold":0,"dropped":0}}],
                     "total":1,"limit":20,"offset":0}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        val page = SubjectApi(client).browseSubjects(type = 2, sort = "rank", limit = 20)
        assertEquals(1, page.data.size)
        val request = engine.requestHistory.single()
        assertEquals("/v0/subjects", request.url.encodedPath)
        assertEquals("2", request.url.parameters["type"])
        assertEquals("rank", request.url.parameters["sort"])
    }

    @Test
    fun `searchSubjects 请求体携带 meta_tags 与 tag 字段`() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                content = """{"data":[],"total":0,"limit":20,"offset":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        SubjectApi(client).searchSubjects(
            SearchRequestDto(
                keyword = "测试",
                sort = "heat",
                filter = SearchFilterDto(
                    type = listOf(2),
                    metaTags = listOf("科幻"),
                    tag = listOf("妹控"),
                ),
            )
        )
        val body = capturedBody ?: ""
        val jsonParser = Json { ignoreUnknownKeys = true }
        val root = jsonParser.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        assertEquals("测试", (root["keyword"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("heat", (root["sort"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
        val filter = root["filter"] as? kotlinx.serialization.json.JsonObject
        val metaTags = (filter?.get("meta_tags") as? kotlinx.serialization.json.JsonArray)?.map {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        }
        val userTags = (filter?.get("tag") as? kotlinx.serialization.json.JsonArray)?.map {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        }
        assertEquals(listOf("科幻"), metaTags)
        assertEquals(listOf("妹控"), userTags)
    }

    @Test
    fun `searchSubjects 空 keyword 也发送请求`() = runTest {
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respond(
                content = """{"data":[],"total":0,"limit":20,"offset":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        SubjectApi(client).searchSubjects(
            SearchRequestDto(
                keyword = "",
                sort = "heat",
                filter = SearchFilterDto(metaTags = listOf("科幻")),
            )
        )
        val root = Json.parseToJsonElement(capturedBody ?: "") as kotlinx.serialization.json.JsonObject
        assertEquals("", (root["keyword"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("heat", (root["sort"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `公开读接口携带与不携带 Authorization 头测试`() = runTest {
        val emptyListJson = """{"data":[],"total":0,"limit":20,"offset":0}"""
        val rawListJson = """[]"""
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            val content = when {
                request.url.encodedPath.endsWith("/persons") -> rawListJson
                request.url.encodedPath.endsWith("/episodes") || request.url.encodedPath.endsWith("/search/subjects") || request.url.encodedPath == "/v0/subjects" -> emptyListJson
                else -> subjectJson
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        val api = SubjectApi(client)

        // 1. getSubject
        api.getSubject(8, accessToken = "token_s")
        assertEquals("Bearer token_s", capturedAuth)
        api.getSubject(8, accessToken = null)
        assertNull(capturedAuth)

        // 2. getEpisodes
        api.getEpisodes(8, accessToken = "token_ep")
        assertEquals("Bearer token_ep", capturedAuth)
        api.getEpisodes(8, accessToken = null)
        assertNull(capturedAuth)

        // 3. searchSubjects
        api.searchSubjects(SearchRequestDto(keyword = "test"), accessToken = "token_search")
        assertEquals("Bearer token_search", capturedAuth)
        api.searchSubjects(SearchRequestDto(keyword = "test"), accessToken = null)
        assertNull(capturedAuth)

        // 4. getPersons
        api.getPersons(8, accessToken = "token_person")
        assertEquals("Bearer token_person", capturedAuth)
        api.getPersons(8, accessToken = null)
        assertNull(capturedAuth)

        // 5. browseSubjects
        api.browseSubjects(type = 2, accessToken = "token_browse")
        assertEquals("Bearer token_browse", capturedAuth)
        api.browseSubjects(type = 2, accessToken = null)
        assertNull(capturedAuth)
    }
}
