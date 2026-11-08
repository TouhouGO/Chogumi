package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.testApiClient
import io.github.touhougo.chogumi.core.network.toFailure
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class CollectionApiTest {

    private val collectionsJson = """
        {
          "data": [
            {
              "subject_id": 8, "subject_type": 2, "rate": 8, "type": 3,
              "comment": "看看", "tags": ["TV"], "ep_status": 5, "vol_status": 0,
              "updated_at": "2022-06-19T18:44:13.6140127+08:00", "private": false,
              "subject": {
                "id": 8, "type": 2, "name": "こんにちは アニメ", "name_cn": "你好 Anime",
                "short_summary": "简介", "images": {"large":"","common":"","medium":"","small":"","grid":""}, "volumes": 0, "eps": 26,
                "collection_total": 10, "score": 7.8, "rank": 0, "tags": []
              }
            }
          ],
          "total": 1, "limit": 50, "offset": 0
        }
    """.trimIndent()

    private fun makeApi(): Pair<CollectionApi, MockEngine> {
        val engine = MockEngine {
            respond(
                content = collectionsJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        return CollectionApi(client) to engine
    }

    @Test
    fun `getUserCollections 解析嵌套条目`() = runTest {
        val (api, engine) = makeApi()
        val page = api.getUserCollections(username = "fixture_user", subjectType = 2, type = 3)
        assertEquals(1, page.data.size)
        val collection = page.data.first()
        assertEquals(3, collection.type)
        assertEquals(5, collection.epStatus)
        assertEquals(26, collection.subject?.eps)
        val request = engine.requestHistory.single()
        assertEquals("/v0/users/fixture_user/collections", request.url.encodedPath)
        assertEquals("2", request.url.parameters["subject_type"])
        assertEquals("3", request.url.parameters["type"])
    }

    @Test
    fun `putEpisodeStatus 请求路径与方法`() = runTest {
        val engine = MockEngine {
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val client = testApiClient(engine)
        CollectionApi(client).putEpisodeStatus(episodeId = 123, type = 2, accessToken = "token-a")
        val request = engine.requestHistory.single()
        assertEquals("/v0/users/-/collections/-/episodes/123", request.url.encodedPath)
        assertEquals(io.ktor.http.HttpMethod.Put, request.method)
        assertEquals("Bearer token-a", request.headers[io.ktor.http.HttpHeaders.Authorization])
    }

    @Test
    fun `patchCollection 请求路径与方法`() = runTest {
        val engine = MockEngine {
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val client = testApiClient(engine)
        CollectionApi(client).patchCollection(
            subjectId = 8,
            body = io.github.touhougo.chogumi.core.network.model.v0.CollectionModifyDto(type = 3, epStatus = 5),
            accessToken = "token-a",
        )
        val request = engine.requestHistory.single()
        assertEquals("/v0/users/-/collections/8", request.url.encodedPath)
        assertEquals(io.ktor.http.HttpMethod.Patch, request.method)
        assertEquals("Bearer token-a", request.headers[io.ktor.http.HttpHeaders.Authorization])
    }

    @Test
    fun `patchCollection 回 404 是未收藏信号_漏到 toFailure 也是 404`() = runTest {
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }
        val api = CollectionApi(testApiClient(engine))

        val signal = kotlin.runCatching {
            api.patchCollection(8, io.github.touhougo.chogumi.core.network.model.v0.CollectionModifyDto(type = 3), "token-a")
        }.exceptionOrNull()

        assertIs<CollectionNotCollectedException>(signal)
        assertEquals(io.github.touhougo.chogumi.core.foundation.Failure.Http(404), signal.toFailure())
    }
}
