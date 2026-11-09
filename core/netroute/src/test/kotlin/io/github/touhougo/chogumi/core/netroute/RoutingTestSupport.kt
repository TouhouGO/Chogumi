package io.github.touhougo.chogumi.core.netroute

import io.github.touhougo.chogumi.core.network.OkHttpCustomizer
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 放在 [DirectRouting] 后面的最后一个拦截器：记下它收到的请求（也就是会发给核心或系统网络的那一个），按 [respond] 回一个响应，
 * 不连网。
 */
internal class RecordingEnd(
    private val respond: (Request) -> Response.Builder = { Response.Builder().code(200).message("OK") },
) : OkHttpCustomizer, Interceptor {
    val requests = mutableListOf<Request>()

    override fun customize(builder: okhttp3.OkHttpClient.Builder) {
        builder.addInterceptor(this)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        synchronized(requests) { requests += request }
        return respond(request).request(request).protocol(Protocol.HTTP_1_1).body("".toResponseBody()).build()
    }
}
