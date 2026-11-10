package io.github.touhougo.chogumi.core.netroute

import com.sun.net.httpserver.HttpServer
import io.github.touhougo.chogumi.core.network.createImageOkHttpClient
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 请求真的经代理发出：本机起一个 HTTP 代理（收绝对地址的请求行）与一个最小的 SOCKS5 代理（无认证、CONNECT），客户端是生产的图片客户端
 * 套上线路调整，域名解析只记下、不解析；要访问的站点用保留域名 `.invalid`，实现坏了也连不出去。线路由测试直接给（[CoreRoutes]），
 * 这里只看网络层照线路怎么发。
 */
class ProxyForwardingTest {
    private val closers = mutableListOf<() -> Unit>()
    private val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val noDns = Dns { host ->
        lookups += host
        throw UnknownHostException("测试里不解析 $host")
    }

    @After
    fun stop() = closers.forEach { it() }

    /** 本机的 HTTP 服务器：记下收到的请求行里的地址，回 [body]。当 HTTP 代理时请求行是绝对地址。 */
    private fun httpServer(body: String): Pair<Int, List<String>> {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())
        server.createContext("/") { exchange ->
            seen += exchange.requestURI.toString()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        closers += { server.stop(0) }
        return server.address.port to seen
    }

    /** 最小的 SOCKS5 代理：无认证，只做 CONNECT；记下客户端要连的目标（域名原样），把连接接到本机的 [upstreamPort]。 */
    private fun socks5(upstreamPort: Int): Pair<Int, List<String>> {
        val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val targets: MutableList<String> = Collections.synchronizedList(mutableListOf())
        closers += { listener.close() }
        thread(isDaemon = true) {
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (e: IOException) { break }
                thread(isDaemon = true) { client.use { relay(it, upstreamPort, targets) } }
            }
        }
        return listener.localPort to targets
    }

    private fun relay(client: Socket, upstreamPort: Int, targets: MutableList<String>) {
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        input.readByte() // 版本 5
        repeat(input.readUnsignedByte()) { input.readByte() } // 认证方式
        output.write(byteArrayOf(5, 0))
        input.readByte(); input.readByte(); input.readByte() // 版本、CONNECT、保留
        val host = when (val type = input.readUnsignedByte()) {
            1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xff).toString() }
            3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
            else -> error("不支持的地址类型 $type")
        }
        targets += "$host:${input.readUnsignedShort()}"
        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
        Socket(InetAddress.getLoopbackAddress(), upstreamPort).use { upstream ->
            thread(isDaemon = true) { runCatching { client.getInputStream().copyTo(upstream.getOutputStream()) } }
            upstream.getInputStream().copyTo(output)
        }
    }

    private fun clientFor(route: () -> CoreRoute) = createImageOkHttpClient(listOf(ProxyRouting { route() }, DirectRouting { route() }))
        .newBuilder().dns(noDns).build()

    private fun fetch(client: okhttp3.OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    @Test
    fun `HTTP 代理_外站请求也经代理`() {
        val (port, seen) = httpServer("via-http-proxy")
        val client = clientFor { CoreRoute.Proxied(ProxyServer(ProxyType.HTTP, "127.0.0.1", port)) }

        assertEquals("via-http-proxy", fetch(client, "http://images.example.invalid/cover.jpg"))

        assertEquals(listOf("http://images.example.invalid/cover.jpg"), seen.toList())
        assertEquals("要访问的站点名交给代理，本机不解析", emptyList<String>(), lookups.toList())
    }

    @Test
    fun `SOCKS5 代理由代理那边解析域名`() {
        val (upstream, _) = httpServer("via-socks")
        val (port, targets) = socks5(upstream)
        val client = clientFor { CoreRoute.Proxied(ProxyServer(ProxyType.SOCKS5, "127.0.0.1", port)) }

        assertEquals("via-socks", fetch(client, "http://images.example.invalid/pic/1.jpg"))

        assertEquals(listOf("images.example.invalid:80"), targets.toList())
        assertEquals("本机不解析要访问的站点", emptyList<String>(), lookups.toList())
    }

    @Test
    fun `换到代理之后开着的连接不再接新请求`() {
        val (direct, directSeen) = httpServer("direct")
        val (proxy, proxySeen) = httpServer("via-proxy")
        var route: CoreRoute = CoreRoute.System
        val client = clientFor { route }
        assertEquals("direct", fetch(client, "http://127.0.0.1:$direct/a"))

        route = CoreRoute.Proxied(ProxyServer(ProxyType.HTTP, "127.0.0.1", proxy))

        assertEquals("via-proxy", fetch(client, "http://127.0.0.1:$direct/b"))
        assertEquals(listOf("/a"), directSeen.toList())
        assertEquals(listOf("http://127.0.0.1:$direct/b"), proxySeen.toList())
    }

    @Test
    fun `系统网络下照常直连`() {
        val (direct, seen) = httpServer("direct")
        val client = clientFor { CoreRoute.System }

        assertEquals("direct", fetch(client, "http://127.0.0.1:$direct/a"))
        assertEquals(listOf("/a"), seen.toList())
    }

    @Test
    fun `换线路后旧的 h2 连接上在途的请求照常跑完_新请求走新线路`() {
        val heldStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heldArrivals = AtomicInteger(0)
        val server = MockWebServer()
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath != "/held") return MockResponse(body = "ok")
                heldArrivals.incrementAndGet()
                heldStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
                return MockResponse(body = "held")
            }
        }
        server.start(InetAddress.getLoopbackAddress(), 0)
        closers += { server.close() }
        val (socks, targets) = socks5(server.port)
        val route = AtomicReference<CoreRoute>(CoreRoute.System)
        val client = clientFor { route.get() }.newBuilder().protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE)).build()
        val base = "http://127.0.0.1:${server.port}"
        val pool = Executors.newSingleThreadExecutor()
        closers += { pool.shutdownNow() }
        // 系统网络下的一条 h2 连接上挂着一个请求
        val held = pool.submit<String> { runCatching { fetch(client, "$base/held") }.getOrElse { "失败：$it" } }
        assertEquals(true, heldStarted.await(10, TimeUnit.SECONDS))

        route.set(CoreRoute.Proxied(ProxyServer(ProxyType.SOCKS5, "127.0.0.1", socks)))

        assertEquals("ok", fetch(client, "$base/after"))
        release.countDown()
        assertEquals("held", held.get(10, TimeUnit.SECONDS))
        assertEquals("换线之前就在跑的请求不能被掐断重发", 1, heldArrivals.get())
        assertEquals("只有新请求经代理", listOf("127.0.0.1:${server.port}"), targets.toList())
    }
}
