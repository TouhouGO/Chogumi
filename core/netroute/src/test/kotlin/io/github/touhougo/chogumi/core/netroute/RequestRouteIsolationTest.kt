package io.github.touhougo.chogumi.core.netroute

import com.sun.net.httpserver.HttpServer
import io.github.touhougo.chogumi.core.network.createWebHttpClient
import io.github.touhougo.chogumi.core.network.createHttpClient
import io.github.touhougo.chogumi.core.network.createTokenHttpClient
import io.github.touhougo.chogumi.core.network.createImageOkHttpClient
import io.github.touhougo.chogumi.core.network.TokenProvider
import io.github.touhougo.chogumi.core.network.TokenRefresher
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import okhttp3.Request
import kotlinx.coroutines.runBlocking
import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.SocketAddress
import java.io.IOException

class RequestRouteIsolationTest {
    @Test fun `改写后切代理_网页凭据仍只交本机核心`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val seen = AtomicReference<String?>()
        server.createContext("/") { exchange ->
            seen.set("${exchange.requestURI} auth=${exchange.requestHeaders.getFirst("Authorization")} cookie=${exchange.requestHeaders.getFirst("Cookie")} coreToken=${exchange.requestHeaders.getFirst("X-Chogumi-Proxy-Token")}")
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        val core = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val received = AtomicReference<String?>()
        core.createContext("/") { exchange ->
            received.set(exchange.requestHeaders.getFirst("Cookie"))
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        core.start()
        val state = AtomicReference<CoreRoute>(CoreRoute.Core(CoreEndpoint(core.address.port, "fixture-core-secret")))
        val system = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.address.port)))
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        val routes = CoreRoutes { state.get() }
        val switchBetweenInterceptors = OkHttpCustomizer { builder ->
            builder.addInterceptor { chain ->
                // 用户在 Rewrite 已生成本机请求、后面的代理选择还没发生时切换线路。
                state.set(CoreRoute.Proxied(ProxyServer(ProxyType.HTTP, "127.0.0.1", server.address.port)))
                chain.proceed(chain.request())
            }
        }
        val adjustments = listOf(DirectRouting(routes), switchBetweenInterceptors, ProxyRouting(routes))
        val provider = object : TokenProvider { override suspend fun accessToken(): String? = null }
        val refresher = object : TokenRefresher { override suspend fun refresh(rejectedAccessToken: String?) = false }
        val clients = listOf(
            createWebHttpClient(customizers = adjustments),
            createHttpClient(provider, refresher, "test", customizers = adjustments),
            createTokenHttpClient("test", customizers = adjustments),
        )
        val image = createImageOkHttpClient(adjustments)
        try {
            for (client in clients) {
                state.set(CoreRoute.Core(CoreEndpoint(core.address.port, "fixture-core-secret")))
                client.get("https://bgm.tv/settings/privacy") { header("Cookie", "chii_auth=fixture-cookie") }.bodyAsText()
                assertEquals("chii_auth=fixture-cookie", received.get())
                assertNull("本机改写不能进入代理", seen.get())
            }
            state.set(CoreRoute.Core(CoreEndpoint(core.address.port, "fixture-core-secret")))
            image.newCall(Request.Builder().url("https://bgm.tv/settings/privacy").header("Cookie", "chii_auth=fixture-cookie").build()).execute().close()
            assertNull("图片客户端同样保持回环通道", seen.get())
        } finally {
            clients.forEach { it.close() }
            cancelAndAwait(clients + clients.map { it.engine })
            image.connectionPool.evictAll()
            image.dispatcher.executorService.shutdown()
            ProxySelector.setDefault(system)
            core.stop(0)
            server.stop(0)
        }
    }
}
