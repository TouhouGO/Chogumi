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
import okhttp3.internal.connection.RealConnection

/**
 * 一次请求只用进入线路链时的 [RequestRoute]。核心回环固定 NO_PROXY，绝不交给应用或系统代理。
 * OkHttp 的 ProxySelector 没有 Request 参数；其路由规划在同步 intercept/proceed 的同一调用线程上执行，
 * 因此只在 proceed 的栈内借用线程局部值，finally 归还；不把它留给线程池里的下一次调用。
 * 网络拦截器按请求上的同一决策拒绝旧池连接，只让它退役，不打断原本在途的流。
 */
internal class ProxyRouting(private val routes: CoreRoutes) : OkHttpCustomizer {
    private val current = ThreadLocal<RequestRoute>()
    private val selector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = checkNotNull(current.get()) { "request route missing" }.proxies
        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
    }

    override fun customize(builder: OkHttpClient.Builder) {
        builder.proxySelector(selector)
        builder.addInterceptor(PinnedRoute())
        builder.addNetworkInterceptor(SameRoute())
    }

    private inner class PinnedRoute : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().withRoute(routes)
            val decision = requireNotNull(request.tag(RequestRoute::class.java))
            when (decision.route) {
                CoreRoute.ProxyUnavailable -> throw IOException("proxy selected but not connected")
                CoreRoute.Undecided -> throw IOException("route not decided yet")
                else -> Unit
            }
            val previous = current.get()
            current.set(decision)
            return try {
                chain.proceed(request)
            } finally {
                if (previous == null) current.remove() else current.set(previous)
            }
        }
    }

    private inner class SameRoute : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val connection = chain.connection() ?: return chain.proceed(chain.request())
            val expected = requireNotNull(chain.request().tag(RequestRoute::class.java)).proxies
            if (connection.route().proxy !in expected) {
                (connection as RealConnection).noNewExchanges()
                throw IOException("connection belongs to a previous route")
            }
            return chain.proceed(chain.request())
        }
    }
}
