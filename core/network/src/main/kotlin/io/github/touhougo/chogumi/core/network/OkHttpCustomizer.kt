package io.github.touhougo.chogumi.core.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * 建 OkHttp 客户端时对 builder 的一处调整（例如换一条连 bgm.tv 的线路）。三个接口客户端与图片加载都按 Koin 里登记的全部
 * customizer 依次套上；网络层不知道有哪些、各做什么。
 */
fun interface OkHttpCustomizer {
    fun customize(builder: OkHttpClient.Builder)
}

/** 依次套上 [customizers]。 */
fun OkHttpClient.Builder.customizedBy(customizers: List<OkHttpCustomizer>): OkHttpClient.Builder =
    apply { customizers.forEach { it.customize(this) } }

/**
 * OkHttp 自己的读写超时，所有客户端（三个接口客户端的引擎、图片加载）都是一次请求的超时 [REQUEST_TIMEOUT_MILLIS]，唯一配置处。
 * 不设时是 OkHttp 默认的 10 秒，比接口客户端的请求超时短，而且短于直连核心最坏换地址的耗时（core:netroute `coreWorstCaseMillis`）。
 */
internal fun OkHttpClient.Builder.requestTimeouts(): OkHttpClient.Builder = apply {
    readTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    writeTimeout(REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
}

/**
 * 图片加载用的 OkHttp 客户端：超时同接口客户端（[requestTimeouts]），只读请求遇网络瞬断（超时、传输中被断开，判定同接口客户端的读重试）
 * 重发一次，再套上 [customizers]（网络线路）。
 */
fun createImageOkHttpClient(customizers: List<OkHttpCustomizer>): OkHttpClient = OkHttpClient.Builder()
    .requestTimeouts()
    .addInterceptor(RetryTransientOnce)
    .customizedBy(customizers)
    .build()

private object RetryTransientOnce : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" && request.method != "HEAD") return chain.proceed(request)
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            // 调用方取消了（图片滚出屏幕）也是 IOException：那不是网络瞬断，不再发
            if (chain.call().isCanceled() || !e.isRetryableNetworkFailure()) throw e
            chain.proceed(request)
        }
    }
}
