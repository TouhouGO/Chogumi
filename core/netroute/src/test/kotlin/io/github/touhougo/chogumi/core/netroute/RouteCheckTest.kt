package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.util.Collections
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCheckTest {
    @Test
    fun `检查连接走网络层的线路调整_对站点发一次 HEAD`() = runBlocking {
        val end = RecordingEnd()
        val check = RouteCheck(listOf(end))

        val result = check.measure(CheckSite.IMAGES)

        assertTrue("连上了是毫秒数：$result", result is Outcome.Success && result.value >= 0)
        val sent = end.requests.single()
        assertEquals("https://lain.bgm.tv/", sent.url.toString())
        assertEquals("HEAD", sent.method)
    }

    @Test
    fun `线路让请求失败时这一格是连不上`() = runBlocking {
        val refusing = OkHttpCustomizer { builder -> builder.addInterceptor(Interceptor { throw IOException("proxy selected but not connected") }) }
        val check = RouteCheck(listOf(refusing))

        assertTrue(check.measure(CheckSite.WEB) is Outcome.Failed)
    }

    @Test
    fun `站点回跳转也算连上_不跟着跳转走`() = runBlocking {
        val end = RecordingEnd { Response.Builder().code(302).message("Found").header("Location", "https://unreachable.example.invalid/") }
        val check = RouteCheck(listOf(end))

        val result = check.measure(CheckSite.WEB)

        assertTrue("收到第一个响应就是连上：$result", result is Outcome.Success)
        assertEquals("只发到站点本身", listOf("https://bgm.tv/"), end.requests.map { it.url.toString() })
    }

    @Test
    fun `新一轮清掉上一轮的连接_套接字不在调用方的线程上关`() = runBlocking {
        // 页面在主线程上开始新一轮；关一条 TLS 连接要写 close_notify，主线程上写套接字在 Android 上抛 NetworkOnMainThreadException
        val server = MockWebServer()
        server.start(InetAddress.getLoopbackAddress(), 0)
        try {
            server.enqueue(MockResponse())
            val sockets = CloseRecordingSockets()
            val local = OkHttpCustomizer { builder ->
                builder.proxy(Proxy.NO_PROXY).socketFactory(sockets)
                builder.addInterceptor(Interceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/")).build()) })
            }
            val check = RouteCheck(listOf(local))
            assertTrue(check.measure(CheckSite.WEB) is Outcome.Success)

            val caller = Thread.currentThread()
            check.startRound()

            assertEquals("上一轮留下的那条连接被关掉", 1, sockets.closedOn.size)
            assertNotEquals("在调用方的线程上关了套接字", caller, sockets.closedOn.single())
        } finally {
            server.close()
        }
    }
}

/** 造普通套接字，记下每一条连上过的连接是在哪个线程上关的；没连上的拨号（localhost 解析出 ::1 而服务器只听 127.0.0.1 时）不算。 */
private class CloseRecordingSockets : SocketFactory() {
    val closedOn: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    override fun createSocket(): Socket = object : Socket() {
        override fun close() {
            if (!isClosed && isConnected) closedOn += Thread.currentThread()
            super.close()
        }
    }

    override fun createSocket(host: String, port: Int): Socket = throw UnsupportedOperationException()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = throw UnsupportedOperationException()

    override fun createSocket(host: InetAddress, port: Int): Socket = throw UnsupportedOperationException()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        throw UnsupportedOperationException()
}
