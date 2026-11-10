package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 代理线路给网络层的调整（每个 OkHttp 客户端、每个主机，不只 bgm.tv）：
 * - 代理选择器：线路是 [CoreRoute.Proxied] 时每个连接都经那个代理，否则照系统（[ProxySelector.getDefault]，含系统里配的代理）。
 * - 选了代理而还没连上（[CoreRoute.ProxyUnavailable]）、线路还没定下来（[CoreRoute.Undecided]）时请求失败，不改走系统网络。
 * - 换了线路，旧连接不再接新请求：OkHttp 按同一个代理选择器认连接池里的连接，开着的连接（h2 上还在跑别的请求的那条）照样会被
 *   拿来发新请求。每个请求拿到连接之后比一次：这条连接走的代理不是现在该走的，就关掉它并让这次请求以连接失败结束，OkHttp 把它
 *   当可重试的连接失败，按现在的线路重新拨号再发（请求还没写出去）。
 */
internal class ProxyRouting(private val routes: CoreRoutes) : OkHttpCustomizer {
    private val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = when (val route = routes.current()) {
            is CoreRoute.Proxied -> listOf(route.server.toProxy())
            else -> systemProxies(uri)
        }

        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) {
            if (routes.current() !is CoreRoute.Proxied) ProxySelector.getDefault()?.connectFailed(uri, address, failure)
        }
    }

    override fun customize(builder: OkHttpClient.Builder) {
        builder.proxySelector(selector)
        builder.addInterceptor(FailClosed())
        builder.addNetworkInterceptor(SameRoute())
    }

    private inner class FailClosed : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = when (routes.current()) {
            CoreRoute.ProxyUnavailable -> throw IOException("proxy selected but not connected")
            CoreRoute.Undecided -> throw IOException("route not decided yet")
            else -> chain.proceed(chain.request())
        }
    }

    private inner class SameRoute : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val connection = chain.connection() ?: return chain.proceed(chain.request())
            val expected = selector.select(chain.request().url.toUri())
            if (connection.route().proxy !in expected) {
                connection.socket().close()
                throw IOException("connection belongs to a previous route")
            }
            return chain.proceed(chain.request())
        }
    }
}

/** 系统给这个地址的代理（系统里配的代理、或直连）；系统没有代理选择器时直连。 */
private fun systemProxies(uri: URI): List<Proxy> =
    ProxySelector.getDefault()?.select(uri)?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
