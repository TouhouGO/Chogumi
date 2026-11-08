package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class WebClientFactoryTest {
    @Test
    fun `网页请求携带应用标识与显式Cookie`() = runTest {
        val client = testWebClient { request ->
            assertEquals("Chogumi/test (Android; https://github.com/TouhouGO/Chogumi)", request.headers[HttpHeaders.UserAgent])
            assertEquals("chii_auth=account", request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("page")
        }
        client.use {
            assertEquals("page", it.get("https://bgm.tv/settings/privacy") {
                headers.append(HttpHeaders.Cookie, "chii_auth=account")
            }.bodyAsText())
        }
    }

    @Test
    fun `网页跳转与未授权由调用方处理不跟随也不刷新`() = runTest {
        val calls = AtomicInteger()
        val client = testWebClient {
            if (calls.incrementAndGet() == 1) respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/login"))
            else respond("sign in", HttpStatusCode.Unauthorized)
        }
        client.use {
            assertEquals(HttpStatusCode.Found, it.get("https://bgm.tv/settings/privacy").status)
            assertEquals(1, calls.get())
            assertEquals(HttpStatusCode.Unauthorized, it.get("https://bgm.tv/settings/privacy").status)
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `网页每次读取当前页面不存HTTP缓存`() = runTest {
        val calls = AtomicInteger()
        val client = testWebClient {
            respond("page-${calls.incrementAndGet()}", headers = headersOf(HttpHeaders.CacheControl, "public, max-age=3600"))
        }
        client.use {
            assertEquals("page-1", it.get("https://bgm.tv/settings/privacy").bodyAsText())
            assertEquals("page-2", it.get("https://bgm.tv/settings/privacy").bodyAsText())
            assertEquals(2, calls.get())
        }
    }
}
