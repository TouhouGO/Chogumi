package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/** 核心要的令牌头与它带回原样跳转地址的头，名字与 Go 核心的 proxy.TokenHeader / proxy.OriginalLocationHeader 相同。 */
private const val TOKEN_HEADER = "X-Chogumi-Proxy-Token"
private const val ORIGINAL_LOCATION_HEADER = "X-Chogumi-Original-Location"

internal fun isBgmHost(host: String) = host == "bgm.tv" || host.endsWith(".bgm.tv")

/**
 * 直连开着时，把发往 bgm.tv（含子域名）的 https 请求改发给本机核心：`https://<主机>/<路径>?<查询>` →
 * `http://127.0.0.1:<端口>/<主机>/<路径>?<查询>`，带上令牌头。是应用拦截器：网络拦截器跑的时候连接已经建好，
 * 而且 OkHttp 不许它改主机与端口。
 *
 * 明文只到 127.0.0.1（netroute_network_security_config 只放行它）；核心没在跑时连不上本机端口，请求失败，不会明文出网。
 * 响应里核心改成本机形式的跳转，换回它带回来的原样地址：Ktor 自己跟随跳转、网页登录读跳转地址都看到原样；OkHttp 自己跟随
 * 跳转时（图片加载）不再经过这里，用的是本机形式，照样回到核心。
 */
internal class DirectRouting(private val endpoints: CoreEndpoints) : OkHttpCustomizer {
    override fun customize(builder: OkHttpClient.Builder) {
        builder.addInterceptor(Rewrite())
    }

    private inner class Rewrite : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            if (!url.isHttps || !isBgmHost(url.host)) return chain.proceed(request)
            val core = endpoints.current() ?: return chain.proceed(request)
            val response = chain.proceed(
                request.newBuilder().url(coreUrlOf(url, core.port)).header(TOKEN_HEADER, core.token).build(),
            )
            val original = response.header(ORIGINAL_LOCATION_HEADER) ?: return response
            return response.newBuilder().header("Location", original).removeHeader(ORIGINAL_LOCATION_HEADER).build()
        }
    }
}

/** `https://<主机>/<路径>?<查询>` 在本机核心上的地址。 */
internal fun coreUrlOf(url: HttpUrl, port: Int): HttpUrl = HttpUrl.Builder()
    .scheme("http")
    .host("127.0.0.1")
    .port(port)
    .addPathSegment(url.host)
    .addEncodedPathSegments(url.encodedPath.removePrefix("/"))
    .encodedQuery(url.encodedQuery)
    .build()
