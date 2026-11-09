package io.github.touhougo.chogumi.core.network

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 只读请求的有限重试（[createHttpClient] 里的 `installReadRetryAndTimeout`）。
 *
 * 全部走**生产客户端** + MockEngine：重试插件的行为取决于它与 Auth、HttpTimeout、期望成功校验
 * 的安装顺序，裸客户端测出来的结论不能代表生产配置。
 * 退避用的是 `kotlinx.coroutines.delay`，在 `runTest` 里是虚拟时间，不会真睡。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadRetryTest {

    private val okBody = """{"ok":true}"""

    /**
     * [attempts] 记引擎**收到**的每一次请求。不能用 `MockEngine.requestHistory`：
     * 它只记成功返回的请求，抛异常的尝试不入账，会把重试次数数少。
     */
    private class Harness(val client: HttpClient, val attempts: List<HttpRequestData>) {
        val requests: Int get() = attempts.size
    }

    private fun harness(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Harness {
        val attempts = CopyOnWriteArrayList<HttpRequestData>()
        val engine = MockEngine { request ->
            attempts += request
            handler(request)
        }
        val client = createHttpClient(
            object : TokenProvider {
                override suspend fun accessToken(): String? = null
            },
            object : TokenRefresher {
                override suspend fun refresh(rejectedAccessToken: String?): Boolean = false
            },
            engine = engine,
            appVersion = "test",
            customizers = emptyList(),
        )
        return Harness(client, attempts)
    }

    private fun MockRequestHandleScope.ok(): HttpResponseData =
        respond(okBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    /** 前 [failures] 次请求抛 [failure]，之后成功。 */
    private fun flaky(failures: Int, failure: () -> Throwable): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData {
        val seen = AtomicInteger()
        return { if (seen.getAndIncrement() < failures) throw failure() else ok() }
    }

    // ---- 重试什么：只读请求 + 网络层瞬断 ----

    @Test
    fun `GET 第一次 TLS 握手被关闭 第二次成功 最终成功且请求了两次`() = runTest {
        val h = harness(flaky(1) { SSLHandshakeException("connection closed") })
        assertEquals(okBody, h.client.get("https://api.bgm.tv/v0/me").bodyAsText())
        assertEquals(2, h.requests)
    }

    @Test
    fun `GET 的 EOF 与连接被重置 与读超时都会重试`() = runTest {
        for (failure in listOf<() -> Throwable>(
            { EOFException() },
            { SocketException("Connection reset") },
            { SocketTimeoutException("Read timed out") },
        )) {
            val h = harness(flaky(1, failure))
            assertEquals(okBody, h.client.get("https://api.bgm.tv/v0/me").bodyAsText())
            assertEquals(2, h.requests)
        }
    }

    @Test
    fun `域名解析不了与连不上是没网 只请求一次 不让离线提示晚几秒`() = runTest {
        for (failure in listOf<() -> Throwable>({ UnknownHostException("api.bgm.tv") }, { ConnectException("Connection refused") })) {
            val h = harness { throw failure() }
            assertFailsWith<java.io.IOException> { h.client.get("https://api.bgm.tv/v0/me") }
            assertEquals(1, h.requests)
        }
    }

    @Test
    fun `Ktor 自己的请求超时携带在取消里冒出时也被识别为网络层失败而重试`() = runTest {
        // HttpTimeout 超时时是以「带 HttpRequestTimeoutException 原因的取消」形式抛给上层的，
        // 不剥壳就会被当成调用方取消而不重试，线上最常见的 SocketTimeout 之外的这一路就失效了。
        val h = harness(flaky(1) {
            CancellationException("timeout", HttpRequestTimeoutException("https://api.bgm.tv/v0/me", 15_000))
        })
        assertEquals(okBody, h.client.get("https://api.bgm.tv/v0/me").bodyAsText())
        assertEquals(2, h.requests)
    }

    @Test
    fun `最多重试两次 共三次尝试 仍失败就把原异常抛出`() = runTest {
        val h = harness { throw SSLHandshakeException("connection closed") }
        assertFailsWith<SSLHandshakeException> { h.client.get("https://api.bgm.tv/v0/me") }
        assertEquals(3, h.requests)
    }

    @Test
    fun `退避是指数加抖动 两次重试共等 1500 到 2498 毫秒`() = runTest {
        val h = harness { throw SSLHandshakeException("connection closed") }
        val start = currentTime
        assertFailsWith<SSLHandshakeException> { h.client.get("https://api.bgm.tv/v0/me") }
        val waited = currentTime - start
        // 第 1 次重试前 500 + [0,500)，第 2 次重试前 1000 + [0,500)
        assertTrue(waited in 1_500L..2_498L, "实际退避 $waited ms")
    }

    // ---- 不重试什么 ----

    @Test
    fun `PATCH 抛异常只请求一次`() = runTest {
        // 写入可能已在服务端生效，重发会重复写入。
        val h = harness(flaky(1) { SSLHandshakeException("connection closed") })
        assertFailsWith<SSLHandshakeException> { h.client.patch("https://next.bgm.tv/p1/privacy") }
        assertEquals(1, h.requests)
    }

    @Test
    fun `PUT 与 DELETE 抛异常也只请求一次`() = runTest {
        val put = harness(flaky(1) { EOFException() })
        assertFailsWith<EOFException> { put.client.put("https://next.bgm.tv/p1/timeline/1/like") }
        assertEquals(1, put.requests)

        val delete = harness(flaky(1) { EOFException() })
        assertFailsWith<EOFException> { delete.client.delete("https://next.bgm.tv/p1/timeline/1") }
        assertEquals(1, delete.requests)
    }

    @Test
    fun `白名单内的只读 POST 条目搜索会重试`() = runTest {
        val h = harness(flaky(1) { SSLHandshakeException("connection closed") })
        val body = h.client.post("https://api.bgm.tv/v0/search/subjects") {
            contentType(ContentType.Application.Json)
            setBody("""{"keyword":"x"}""")
        }.bodyAsText()
        assertEquals(okBody, body)
        assertEquals(2, h.requests)
        // 重试发出的是同一个请求体
        assertEquals(
            h.attempts[0].body.toString(),
            h.attempts[1].body.toString(),
        )
    }

    @Test
    fun `白名单外的 POST 不重试`() = runTest {
        val writes = listOf(
            "https://api.bgm.tv/v0/users/-/collections/1", // 收藏
            "https://next.bgm.tv/p1/timeline", // 发时间线
            "https://next.bgm.tv/p1/clear-notify", // 清通知
            "https://bgm.tv/oauth/access_token", // 换令牌：刷新令牌一次性，重发会作废
            "https://next.bgm.tv/v0/search/subjects", // 路径同名但不是 v0 主机
        )
        for (url in writes) {
            val h = harness(flaky(1) { SSLHandshakeException("connection closed") })
            assertFailsWith<SSLHandshakeException>(url) {
                h.client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            }
            assertEquals(1, h.requests, url)
        }
    }

    @Test
    fun `4xx 不重试`() = runTest {
        val h = harness { respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertFailsWith<ClientRequestException> { h.client.get("https://api.bgm.tv/v0/subjects/1") }
        assertEquals(1, h.requests)
    }

    @Test
    fun `5xx 也不重试`() = runTest {
        val h = harness { respond("{}", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertFailsWith<ServerResponseException> { h.client.get("https://next.bgm.tv/p1/home") }
        assertEquals(1, h.requests)
    }

    @Test
    fun `非网络层的异常不重试`() = runTest {
        val h = harness(flaky(1) { IllegalStateException("boom") })
        assertFailsWith<IllegalStateException> { h.client.get("https://api.bgm.tv/v0/me") }
        assertEquals(1, h.requests)
    }

    // ---- 取消 ----

    @Test
    fun `引擎抛出的取消不被当作失败去重试`() = runTest {
        // 调用方并没有取消，下一次尝试本可以成功；若取消被当作瞬断重试，这里就会成功。
        val h = harness(flaky(1) { CancellationException("cancelled") })
        assertFailsWith<CancellationException> { h.client.get("https://api.bgm.tv/v0/me") }
        assertEquals(1, h.requests)
    }

    @Test
    fun `调用方取消请求后不再发第二次`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val h = harness {
            entered.complete(Unit)
            never.await()
            ok()
        }
        val job = launch { h.client.get("https://api.bgm.tv/v0/me") }
        // 等引擎收到了第一次请求（一次性信号，不是轮询瞬态）
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, h.requests)
    }
}
