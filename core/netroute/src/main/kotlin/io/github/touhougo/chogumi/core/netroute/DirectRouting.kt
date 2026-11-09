package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 核心要的令牌头、它带回原样跳转地址的头、它自己生成的 502 上的失败原因头，名字与 Go 核心的 proxy.TokenHeader /
 * proxy.OriginalLocationHeader / proxy.ErrorHeader 相同。
 */
private const val TOKEN_HEADER = "X-Chogumi-Proxy-Token"
private const val ORIGINAL_LOCATION_HEADER = "X-Chogumi-Original-Location"
private const val PROXY_ERROR_HEADER = "X-Chogumi-Proxy-Error"

internal fun isBgmHost(host: String) = host == "bgm.tv" || host.endsWith(".bgm.tv")

/**
 * 直连开着时，把发往 bgm.tv（含子域名）的 https 请求改发给本机核心：`https://<主机>/<路径>?<查询>` →
 * `http://127.0.0.1:<端口>/<主机>/<路径>?<查询>`，带上令牌头。是应用拦截器：网络拦截器跑的时候连接已经建好，
 * 而且 OkHttp 不许它改主机与端口。
 *
 * 明文只到 127.0.0.1（netroute_network_security_config 只放行它）；核心没在跑时连不上本机端口，请求失败，不会明文出网。
 * 选了直连而核心没起来（[CoreRoute.Unavailable]）时请求直接失败，不退回系统网络。
 * 核心连上游失败（连接被重置、TLS 失败、超时）时回一个带 [PROXY_ERROR_HEADER] 的 502：这里把它换回 [IOException]，与不经核心时
 * 同一类失败（读请求照样重试，「5xx 才走网页」的写不会在结果不明时误走）；上游真的回了 5xx 就原样交出去。
 * 响应里核心改成本机形式的跳转，换回它带回来的原样地址：Ktor 自己跟随跳转、网页登录读跳转地址都看到原样；OkHttp 自己跟随
 * 跳转时（图片加载）不再经过这里，用的是本机形式，照样回到核心。
 */
internal class DirectRouting(private val routes: CoreRoutes) : OkHttpCustomizer {
    override fun customize(builder: OkHttpClient.Builder) {
        builder.addInterceptor(Rewrite())
    }

    private inner class Rewrite : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            if (!url.isHttps || !isBgmHost(url.host)) return chain.proceed(request)
            val core = when (val route = routes.current()) {
                CoreRoute.System -> return chain.proceed(request)
                CoreRoute.Unavailable -> throw IOException("direct route selected but its core is not running")
                is CoreRoute.Core -> route.endpoint
            }
            val response = chain.proceed(
                request.newBuilder().url(coreUrlOf(url, core.port)).header(TOKEN_HEADER, core.token).build(),
            )
            response.header(PROXY_ERROR_HEADER)?.let { reason ->
                response.close()
                throw IOException("direct route upstream failed: $reason")
            }
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
