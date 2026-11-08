package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class MonoApiTest {

    private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())

    @Test
    fun `getCharacter 请求路径与可选鉴权`() = runTest {
        var recordedAuth: String? = null
        val engine = MockEngine { request ->
            recordedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = """
                    {
                      "id": 50576, "name": "千石抚子", "nameCN": "千石抚子", "role": 1,
                      "infobox": [], "info": "", "summary": "简介",
                      "comment": 10, "collects": 20, "lock": false, "redirect": 0, "nsfw": false,
                      "collectedAt": 1726000000
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val api = MonoApi(testApiClient(engine))
        val character = api.getCharacter(50576, accessToken = "my_token")

        assertEquals(50576, character.id)
        assertEquals("千石抚子", character.name)
        val request = engine.requestHistory.single()
        assertEquals("/p1/characters/50576", request.url.encodedPath)
        assertEquals("Bearer my_token", recordedAuth)
    }

    @Test
    fun `getPerson 请求路径与解析`() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = """
                    {
                      "id": 1234, "name": "神前晓", "nameCN": "神前晓", "type": 1,
                      "infobox": [], "info": "", "career": ["artist"], "summary": "简介",
                      "comment": 5, "collects": 15, "lock": false, "redirect": 0, "nsfw": false
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val api = MonoApi(testApiClient(engine))
        val person = api.getPerson(1234)

        assertEquals(1234, person.id)
        assertEquals("神前晓", person.name)
        val request = engine.requestHistory.single()
        assertEquals("/p1/persons/1234", request.url.encodedPath)
        assertNull(request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `7个子列表端点分页参数正确拼装`() = runTest {
        val emptyPagedJson = """{"data":[],"total":0}"""

        val testCases = listOf<suspend (MonoApi) -> Pair<String, Map<String, String>>>(
            { api ->
                api.getCharacterCasts(50576, limit = 20, offset = 10)
                "/p1/characters/50576/casts" to mapOf("limit" to "20", "offset" to "10")
            },
            { api ->
                api.getPersonWorks(1234, limit = 30, offset = 0)
                "/p1/persons/1234/works" to mapOf("limit" to "30", "offset" to "0")
            },
            { api ->
                api.getPersonCasts(1234, limit = 15, offset = 45)
                "/p1/persons/1234/casts" to mapOf("limit" to "15", "offset" to "45")
            },
            { api ->
                api.getCharacterRelations(50576, limit = 10, offset = 20)
                "/p1/characters/50576/relations" to mapOf("limit" to "10", "offset" to "20")
            },
            { api ->
                api.getPersonRelations(1234, limit = 25, offset = 50)
                "/p1/persons/1234/relations" to mapOf("limit" to "25", "offset" to "50")
            },
            { api ->
                api.getIndexes(MonoResource.CHARACTERS, 50576, limit = 30, offset = 60)
                "/p1/characters/50576/indexes" to mapOf("limit" to "30", "offset" to "60")
            },
            { api ->
                api.getIndexes(MonoResource.PERSONS, 1234, limit = 5, offset = 15)
                "/p1/persons/1234/indexes" to mapOf("limit" to "5", "offset" to "15")
            },
        )

        for (testCase in testCases) {
            val engine = MockEngine {
                respond(
                    content = emptyPagedJson,
                    status = HttpStatusCode.OK,
                    headers = jsonHeaders,
                )
            }
            val api = MonoApi(testApiClient(engine))
            val (expectedPath, expectedParams) = testCase(api)
            val request = engine.requestHistory.single()
            assertEquals(expectedPath, request.url.encodedPath)
            for ((key, value) in expectedParams) {
                assertEquals(value, request.url.parameters[key], "参数 $key 匹配失败")
            }
        }
    }

    @Test
    fun `MonoApi 7个子列表端点携带与不携带 Authorization 头测试`() = runTest {
        val emptyPagedJson = """{"data":[],"total":0}"""
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = emptyPagedJson,
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val api = MonoApi(testApiClient(engine))

        // 1. getCharacterCasts
        api.getCharacterCasts(50576, accessToken = "token_casts")
        assertEquals("Bearer token_casts", capturedAuth)
        api.getCharacterCasts(50576, accessToken = null)
        assertNull(capturedAuth)

        // 2. getPersonWorks
        api.getPersonWorks(1234, accessToken = "token_works")
        assertEquals("Bearer token_works", capturedAuth)
        api.getPersonWorks(1234, accessToken = null)
        assertNull(capturedAuth)

        // 3. getPersonCasts
        api.getPersonCasts(1234, accessToken = "token_pcasts")
        assertEquals("Bearer token_pcasts", capturedAuth)
        api.getPersonCasts(1234, accessToken = null)
        assertNull(capturedAuth)

        // 4. getCharacterRelations
        api.getCharacterRelations(50576, accessToken = "token_crel")
        assertEquals("Bearer token_crel", capturedAuth)
        api.getCharacterRelations(50576, accessToken = null)
        assertNull(capturedAuth)

        // 5. getPersonRelations
        api.getPersonRelations(1234, accessToken = "token_prel")
        assertEquals("Bearer token_prel", capturedAuth)
        api.getPersonRelations(1234, accessToken = null)
        assertNull(capturedAuth)

        // 6. getIndexes（角色）
        api.getIndexes(MonoResource.CHARACTERS, 50576, accessToken = "token_cidx")
        assertEquals("Bearer token_cidx", capturedAuth)
        api.getIndexes(MonoResource.CHARACTERS, 50576, accessToken = null)
        assertNull(capturedAuth)

        // 7. getIndexes（人物）
        api.getIndexes(MonoResource.PERSONS, 1234, accessToken = "token_pidx")
        assertEquals("Bearer token_pidx", capturedAuth)
        api.getIndexes(MonoResource.PERSONS, 1234, accessToken = null)
        assertNull(capturedAuth)
    }

    @Test
    fun `角色与人物吐槽请求路径与 Bearer 头`() = runTest {
        val commentsJson = """[]"""
        val engine = MockEngine {
            respond(
                content = commentsJson,
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val api = MonoApi(testApiClient(engine))

        api.getComments(MonoResource.CHARACTERS, 50576, accessToken = "token_c")
        val req1 = engine.requestHistory[0]
        assertEquals("/p1/characters/50576/comments", req1.url.encodedPath)
        assertEquals("Bearer token_c", req1.headers[HttpHeaders.Authorization])

        api.getComments(MonoResource.PERSONS, 1234, accessToken = null)
        val req2 = engine.requestHistory[1]
        assertEquals("/p1/persons/1234/comments", req2.url.encodedPath)
        assertNull(req2.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `角色与人物收藏添加与删除端点使用对应 PUT 和 DELETE 方法及鉴权头`() = runTest {
        val engine = MockEngine {
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        val api = MonoApi(testApiClient(engine))

        // 1. 收藏角色
        api.setCollected(MonoResource.CHARACTERS, 50576, collected = true, accessToken = "token_add_char")
        val req1 = engine.requestHistory[0]
        assertEquals(HttpMethod.Put, req1.method)
        assertEquals("/p1/collections/characters/50576", req1.url.encodedPath)
        assertEquals("Bearer token_add_char", req1.headers[HttpHeaders.Authorization])

        // 2. 取消收藏角色
        api.setCollected(MonoResource.CHARACTERS, 50576, collected = false, accessToken = "token_del_char")
        val req2 = engine.requestHistory[1]
        assertEquals(HttpMethod.Delete, req2.method)
        assertEquals("/p1/collections/characters/50576", req2.url.encodedPath)
        assertEquals("Bearer token_del_char", req2.headers[HttpHeaders.Authorization])

        // 3. 收藏人物
        api.setCollected(MonoResource.PERSONS, 1234, collected = true, accessToken = "token_add_person")
        val req3 = engine.requestHistory[2]
        assertEquals(HttpMethod.Put, req3.method)
        assertEquals("/p1/collections/persons/1234", req3.url.encodedPath)
        assertEquals("Bearer token_add_person", req3.headers[HttpHeaders.Authorization])

        // 4. 取消收藏人物
        api.setCollected(MonoResource.PERSONS, 1234, collected = false, accessToken = "token_del_person")
        val req4 = engine.requestHistory[3]
        assertEquals(HttpMethod.Delete, req4.method)
        assertEquals("/p1/collections/persons/1234", req4.url.encodedPath)
        assertEquals("Bearer token_del_person", req4.headers[HttpHeaders.Authorization])
    }
}
