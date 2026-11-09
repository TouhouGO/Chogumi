package io.github.touhougo.chogumi.core.netroute

import com.sun.net.httpserver.HttpServer
import io.github.touhougo.chogumi.core.network.createImageOkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Collections
import okhttp3.Dns
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图片加载（生产的图片客户端）跟随跳转：外站图片跳到 bgm.tv 的图床时，那一跳照样经过线路。客户端走真的 HTTP，两个本机服务器
 * 分别当外站与核心；不经代理、域名解析只记下不解析：任何一跳要连系统网络上的 bgm.tv 都会被记下，也连不出去。
 */
class ImageRedirectThroughCoreTest {
    private val servers = mutableListOf<HttpServer>()
    private val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val noDns = Dns { host ->
        lookups += host
        throw UnknownHostException("测试里不解析 $host")
    }

    /** 本机的一个 HTTP 服务器收到的路径与令牌头。 */
    private class Served(val port: Int, val paths: List<String>, val tokens: List<String?>)

    /** 本机的一个 HTTP 服务器：每个请求回 [status]，带 [location] 时作为跳转地址。 */
    private fun serve(status: Int, location: String? = null): Served {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val tokens: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.toString()
            tokens += exchange.requestHeaders.getFirst("X-Chogumi-Proxy-Token")
            location?.let { exchange.responseHeaders.add("Location", it) }
            val body = "img".toByteArray()
            if (location != null) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        servers += server
        return Served(server.address.port, paths, tokens)
    }

    @After
    fun stop() = servers.forEach { it.stop(0) }

    private fun fetch(url: String, route: CoreRoute): Int {
        val client = createImageOkHttpClient(listOf(DirectRouting { route })).newBuilder().proxy(Proxy.NO_PROXY).dns(noDns).build()
        return client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
    }

    @Test
    fun `外站图片跳到 bgm 点 tv 时那一跳经过核心`() {
        val core = serve(200)
        val external = serve(302, location = "https://lain.bgm.tv/pic/cover/l/1.jpg")

        val code = try {
            fetch("http://127.0.0.1:${external.port}/ext.jpg", CoreRoute.Core(CoreEndpoint(core.port, "secret")))
        } catch (e: IOException) {
            -1 // 先看下面它走了哪条路
        }

        assertEquals("这一跳不能走系统网络（不能有要解析的域名）", emptyList<String>(), lookups.toList())
        assertEquals(listOf("/lain.bgm.tv/pic/cover/l/1.jpg"), core.paths)
        assertEquals(listOf("secret"), core.tokens)
        assertEquals(200, code)
    }

    @Test
    fun `系统网络下图片照样跟随跳转`() {
        val target = serve(200)
        val external = serve(302, location = "http://127.0.0.1:${target.port}/moved.jpg")

        val code = fetch("http://127.0.0.1:${external.port}/ext.jpg", CoreRoute.System)

        assertEquals(listOf("/moved.jpg"), target.paths)
        assertEquals(200, code)
    }
}
