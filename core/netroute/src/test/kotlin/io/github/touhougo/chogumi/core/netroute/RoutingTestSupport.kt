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

/** 测试里不该被起起来的核心：起它就是测试错了。 */
internal object NoCore : DirectCore {
    override fun start(addresses: List<String>, config: ByteArray, edgeBudgetMillis: Long, onConfig: (ByteArray) -> Unit): CoreEndpoint =
        error("这条测试不该起核心")

    override fun stop() = Unit
}

/** 起不来的核心（例如本机端口开不出来）。 */
internal object BrokenCore : DirectCore {
    override fun start(addresses: List<String>, config: ByteArray, edgeBudgetMillis: Long, onConfig: (ByteArray) -> Unit): CoreEndpoint =
        throw java.io.IOException("listen failed")

    override fun stop() = Unit
}

/** 起得来的核心，数它起了几次、停了几次。 */
internal class CountingCore : DirectCore {
    @Volatile var starts = 0
    @Volatile var stops = 0
    @Volatile var budget = 0L

    override fun start(addresses: List<String>, config: ByteArray, edgeBudgetMillis: Long, onConfig: (ByteArray) -> Unit): CoreEndpoint {
        starts++
        budget = edgeBudgetMillis
        return CoreEndpoint(4242, "secret")
    }

    override fun stop() {
        stops++
    }
}
