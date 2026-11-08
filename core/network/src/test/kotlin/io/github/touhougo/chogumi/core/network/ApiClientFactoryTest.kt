package io.github.touhougo.chogumi.core.network

import io.github.touhougo.chogumi.core.network.api.pinSessionToken
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class ApiClientFactoryTest {
    @Test
    fun `API错误保留HTTP状态而不是解析成成功响应`() = runTest {
        val client = testApiClient { respond("denied", HttpStatusCode.Forbidden) }
        client.use {
            val failure = assertFailsWith<ClientRequestException> { it.get("https://api.bgm.tv/v0/subjects/8") }
            assertEquals(HttpStatusCode.Forbidden, failure.response.status)
        }
    }

    @Test
    fun `API不在HTTP层缓存响应并保留请求身份`() = runTest {
        val engine = MockEngine {
            respond("subject", headers = headersOf(HttpHeaders.CacheControl, "public, max-age=3600"))
        }
        testApiClient(engine).use { client ->
            repeat(2) {
                assertEquals("subject", client.get("https://api.bgm.tv/v0/subjects/8").bodyAsText())
            }
            assertEquals(2, engine.requestHistory.size, "带 max-age 的响应也不留在客户端里应答下一次请求")
            val request = engine.requestHistory.first()
            assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("Chogumi/test (Android; https://github.com/TouhouGO/Chogumi)", request.headers[HttpHeaders.UserAgent])
            client.get("https://api.bgm.tv/v0/me") { pinSessionToken("account") }
            assertEquals("Bearer account", engine.requestHistory.last().headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun `API跟随普通HTTP跳转`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/v0/start") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/v0/end"))
            } else {
                respond("finished")
            }
        }
        testApiClient(engine).use {
            assertEquals("finished", it.get("https://api.bgm.tv/v0/start").bodyAsText())
            assertEquals(listOf("/v0/start", "/v0/end"), engine.requestHistory.map { request -> request.url.encodedPath })
        }
    }
}
