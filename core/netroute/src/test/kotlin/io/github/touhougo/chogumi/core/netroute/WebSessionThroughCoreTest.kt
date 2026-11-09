package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.core.network.createWebHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.setCookie
import kotlinx.coroutines.test.runTest
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 网页会话经核心：生产的网页客户端（不跟随跳转、Cookie 由调用方自己带）套上 [DirectRouting] 之后，请求改发到本机核心，
 * 调用方带的 Cookie 原样到核心，核心带回的 Set-Cookie 原样回到调用方，跳转地址还原成 bgm.tv 原样的那一个。
 * 网页会话的 Cookie 是调用方按 bgm.tv 存、按 bgm.tv 带的（没有 OkHttp CookieJar），改写只发生在 OkHttp 里，调用方看不见。
 */
class WebSessionThroughCoreTest {
    private val core = CoreEndpoint(4242, "secret")

    @Test
    fun `网页会话的 Cookie 与跳转经核心原样往返`() = runTest {
        val end = RecordingEnd {
            Response.Builder().code(302).message("Found")
                .header("Set-Cookie", "chii_auth=xyz; domain=.bgm.tv; path=/; secure; HttpOnly")
                .header("Location", "/bgm.tv/user/sai")
                .header("X-Chogumi-Original-Location", "/user/sai")
        }
        val client = createWebHttpClient(customizers = listOf(DirectRouting { core }, end))

        val response = client.get("https://bgm.tv/login") { header(HttpHeaders.Cookie, "chii_sid=abc") }

        val sent = end.requests.single()
        assertEquals("http://127.0.0.1:4242/bgm.tv/login", sent.url.toString())
        assertEquals("chii_sid=abc", sent.header("Cookie"))
        assertEquals("secret", sent.header("X-Chogumi-Proxy-Token"))
        assertEquals("https://bgm.tv/login", response.call.request.url.toString())
        assertEquals("/user/sai", response.headers[HttpHeaders.Location])
        assertEquals(null, response.headers["X-Chogumi-Original-Location"])
        assertEquals("xyz", response.setCookie().single { it.name == "chii_auth" }.value)
    }

    @Test
    fun `不是 bgm 点 tv 的请求不经核心`() = runTest {
        val end = RecordingEnd()
        val client = createWebHttpClient(customizers = listOf(DirectRouting { core }, end))

        client.get("https://example.com/a")

        assertEquals("https://example.com/a", end.requests.single().url.toString())
        assertEquals(null, end.requests.single().header("X-Chogumi-Proxy-Token"))
    }
}
