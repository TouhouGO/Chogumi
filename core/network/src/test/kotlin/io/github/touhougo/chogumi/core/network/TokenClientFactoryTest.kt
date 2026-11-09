package io.github.touhougo.chogumi.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class TokenClientFactoryTest {
    @Test
    fun `凭据客户端携带应用标识并每次回源不注入OAuth`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            respond("credential-${calls.incrementAndGet()}", headers = headersOf(HttpHeaders.CacheControl, "public, max-age=3600"))
        }
        createTokenHttpClient("test", engine, customizers = emptyList()).use {
            assertEquals("credential-1", it.get("https://bgm.tv/oauth/access_token").bodyAsText())
            assertEquals("credential-2", it.get("https://bgm.tv/oauth/access_token").bodyAsText())
        }
        assertEquals(2, engine.requestHistory.size)
        engine.requestHistory.forEach { request ->
            assertEquals("Chogumi/test (Android; https://github.com/TouhouGO/Chogumi)", request.headers[HttpHeaders.UserAgent])
            assertNull(request.headers[HttpHeaders.Authorization])
        }
    }
}
