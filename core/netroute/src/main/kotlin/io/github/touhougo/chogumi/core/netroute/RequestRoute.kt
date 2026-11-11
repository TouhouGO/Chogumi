package io.github.touhougo.chogumi.core.netroute

import java.net.Proxy
import java.net.ProxySelector
import okhttp3.Request

/** 一次请求的线路与代理在进入线路链时定下；后续改写、选连接和重试都读它，不再读全局开关。 */
internal class RequestRoute(val route: CoreRoute, val proxies: List<Proxy>)

internal fun Request.withRoute(routes: CoreRoutes): Request {
    if (tag(RequestRoute::class.java) != null) return this
    val route = routes.current()
    val proxies = when {
        route is CoreRoute.Core && url.isHttps && isBgmHost(url.host) -> listOf(Proxy.NO_PROXY)
        route is CoreRoute.Proxied -> listOf(route.server.toProxy())
        else -> ProxySelector.getDefault()?.select(url.toUri())?.toList()?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
    }
    return newBuilder().tag(RequestRoute::class.java, RequestRoute(route, proxies)).build()
}
