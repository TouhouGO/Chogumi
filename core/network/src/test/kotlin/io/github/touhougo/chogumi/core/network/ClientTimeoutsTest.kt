package io.github.touhougo.chogumi.core.network

import io.ktor.client.request.get
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** 接口与图片客户端底下的 OkHttp：读超时就是一次请求的超时；图片对只读请求的网络瞬断重发一次。 */
class ClientTimeoutsTest {

    /** 最后一个拦截器：按 [failures] 依次抛出（用完了就回 200），记下看到的读超时与次数。 */
    private class End(private vararg val failures: IOException) : OkHttpCustomizer, Interceptor {
        val attempts = AtomicInteger()
        @Volatile var readTimeoutMillis = -1

        override fun customize(builder: okhttp3.OkHttpClient.Builder) {
            builder.addInterceptor(this)
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            readTimeoutMillis = chain.readTimeoutMillis()
            val n = attempts.getAndIncrement()
            if (n < failures.size) throw failures[n]
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("".toResponseBody()).build()
        }
    }

    private fun image(end: End) =
        createImageOkHttpClient(listOf(end)).newCall(Request.Builder().url("https://lain.bgm.tv/pic/a.jpg").build()).execute()

    @Test
    fun `接口客户端底下的读超时就是请求超时`() = runBlocking {
        val end = End()
        createWebHttpClient(customizers = listOf(end)).get("https://bgm.tv/")

        assertEquals(REQUEST_TIMEOUT_MILLIS.toInt(), end.readTimeoutMillis)
    }

    @Test
    fun `图片客户端的读超时就是请求超时`() {
        val end = End()
        image(end).close()

        assertEquals(REQUEST_TIMEOUT_MILLIS.toInt(), end.readTimeoutMillis)
    }

    @Test
    fun `图片遇网络瞬断重发一次`() {
        val end = End(SocketTimeoutException("read timed out"))
        image(end).use { assertEquals(200, it.code) }

        assertEquals(2, end.attempts.get())
    }

    @Test
    fun `图片只重发一次`() {
        val end = End(SocketTimeoutException("read timed out"), IOException("connection reset"))
        assertFailsWith<IOException> { image(end) }

        assertEquals(2, end.attempts.get())
    }

    @Test
    fun `连不上（解析不了）不重发`() {
        val end = End(UnknownHostException("lain.bgm.tv"))
        assertFailsWith<UnknownHostException> { image(end) }

        assertEquals(1, end.attempts.get())
    }
}
