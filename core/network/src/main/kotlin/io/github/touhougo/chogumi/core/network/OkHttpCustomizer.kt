package io.github.touhougo.chogumi.core.network

import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * 重发一次，跳转在 [customizers]（网络线路）之上跟随（[FollowRedirects]），再套上 [customizers]。
 */
fun createImageOkHttpClient(customizers: List<OkHttpCustomizer>): OkHttpClient = OkHttpClient.Builder()
    .requestTimeouts()
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor(RetryTransientOnce)
    .addInterceptor(FollowRedirects)
    .customizedBy(customizers)
    .build()

/**
 * 图片的跳转在这里跟随，不交给 OkHttp：OkHttp 自己跟随时，后面几跳在应用拦截器之下生成，不再经过 [OkHttpCustomizer] 装上的线路
 * （外站图片跳到 bgm.tv 的图床时，那一跳会走系统网络）。这里每一跳都从线路之上重新进链。只跟随读（GET、HEAD，图片只有读），
 * 最多 [MAX_REDIRECTS] 跳；换了主机不带 Authorization。接口与网页客户端由 Ktor 跟随或不跟随，每一跳本来就是一次新请求，不需要它。
 */
private object FollowRedirects : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var response = chain.proceed(request)
        var hops = 0
        while (true) {
            val next = redirectOf(request, response) ?: return response
            response.close()
            if (++hops > MAX_REDIRECTS) throw ProtocolException("too many redirects: ${chain.request().url}")
            request = next
            response = chain.proceed(request)
        }
    }

    private fun redirectOf(request: Request, response: Response): Request? {
        if (response.code !in REDIRECT_CODES || request.method != "GET" && request.method != "HEAD") return null
        val target = response.header("Location")?.let(request.url::resolve) ?: return null
        return request.newBuilder().url(target).apply {
            if (target.host != request.url.host) removeHeader("Authorization")
        }.build()
    }
}

/** OkHttp 自己跟随时的跳数上限与跳转状态码。 */
private const val MAX_REDIRECTS = 20
private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

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
