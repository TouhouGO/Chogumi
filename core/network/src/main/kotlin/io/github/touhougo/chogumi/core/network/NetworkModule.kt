package io.github.touhougo.chogumi.core.network

import io.github.touhougo.chogumi.core.network.api.AuthApi
import io.github.touhougo.chogumi.core.network.api.BangumiDataApi
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.CommunityApi
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.MonoApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.api.ReleaseApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.TimelineApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.api.WebLoginApi
import io.github.touhougo.chogumi.core.network.api.WebPageApi
import io.github.touhougo.chogumi.core.network.api.WebSessionApi
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.http.ContentType
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.util.AttributeKey
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** 请求超时：单次尝试的上限（含重试时每一次尝试各算各的，见 [installReadRetryAndTimeout]）。 */
private const val REQUEST_TIMEOUT_MILLIS = 15_000L

/** 连接超时：单次尝试内建立连接的上限。 */
private const val CONNECT_TIMEOUT_MILLIS = 10_000L

/**
 * 只读请求的最大重试次数（不含首次）。**本项目设计决定（not in M3）**：
 * 线上瞬断是「个别请求被掐、同时段多数请求成功」，一两次重试足以覆盖；
 * 再多只会在真断网时把用户的等待拉得更长，而且放大对服务端的请求量。
 */
private const val READ_RETRY_MAX = 2

/** 第一次重试前的基础退避；之后每次翻倍（500ms、1000ms）。**设计决定（not in M3）**。 */
private const val READ_RETRY_BASE_DELAY_MILLIS = 500L

/** 退避抖动上限：每次退避再加 [0, 500) ms 随机量，避免同一时刻失败的多个请求同步重试。 */
private const val READ_RETRY_JITTER_MILLIS = 500L

private val V0_HOST = Url(API_V0_HOST).host

/** `Authorization` 头里访问令牌前面的那段（pinSessionToken 用 bearerAuth 写入）。 */
private const val BEARER_PREFIX = "Bearer "

/** 网页客户端的 Koin 限定名。 */
private const val WEB_CLIENT = "webClient"

/** 服务端 JSON 的解码规则，两个客户端与 [io.github.touhougo.chogumi.core.network.api.BangumiDataApi] 共用。 */
internal val ApiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * JSON 内容协商，两个接口客户端共用（唯一配置处）。解码挪到 [Dispatchers.Default]（[BackgroundDecoding]）：
 * Ktor 在调用 `body()` 的那个协程里跑转换器，调用方多是 `viewModelScope`（主线程）；
 * `/p1/home` 一次约 660 KB，探索页的 v0 搜索货架每个 130–170 KB，在主线程解析正好撞上刷新时的界面更新。
 */
private fun HttpClientConfig<*>.installJson() {
    install(ContentNegotiation) {
        register(ContentType.Application.Json, BackgroundDecoding(KotlinxSerializationConverter(ApiJson), Dispatchers.Default))
    }
}

/** 把 [inner] 的解码放到 [dispatcher] 上跑；编码（请求体都很小）照旧。 */
internal class BackgroundDecoding(
    private val inner: ContentConverter,
    private val dispatcher: CoroutineDispatcher,
) : ContentConverter by inner {
    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? =
        withContext(dispatcher) { inner.deserialize(charset, typeInfo, content) }
}

/**
 * bangumi API 要求自定义 User-Agent；版本号取自 app 的版本（唯一来源 gradle.properties 的 chogumi.version）。
 * 接口客户端与图片加载器（`api.bgm.tv` 的头像入口）都用它。
 */
fun userAgent(appVersion: String) = "Chogumi/$appVersion (Android; https://github.com/TouhouGO/Chogumi)"

/**
 * 网页请求（网页登录与网页会话）的 User-Agent：bgm.tv 的登录 Cookie 和 UA 绑在一起（服务端解 Cookie 的密钥是站点密钥加请求的 UA），
 * UA 一变，存着的网页会话就解不开，网页那边当作没登录。所以它不带版本号，**永远不改**：改了每个人都要重新用账号密码登录。
 */
internal const val WEB_USER_AGENT = "Chogumi (Android; https://github.com/TouhouGO/Chogumi)"

/** 网页操作可能通过 GET 修改服务端；属性随请求保留，让重试决策识别真实操作语义。 */
internal val WebOperation = AttributeKey<Unit>("WebOperation")

/**
 * 请求是否只读，即重复发送不会改变服务端状态：
 * 没有操作标记的 `GET`，以及白名单内明确只读的 `POST`（目前只有 v0 条目搜索）。
 * 网页操作、`PATCH`、`PUT`、`DELETE` 与其余 `POST`（OAuth 换令牌、收藏、时间线发帖、清通知）一律不算——
 * 网络在响应途中断开时写入可能已在服务端生效，重发会重复写入。
 * 放开新的只读 POST 只改这里。
 */
private fun HttpRequestBuilder.isReadOnly(): Boolean = !attributes.contains(WebOperation) && when (method) {
    HttpMethod.Get -> true
    HttpMethod.Post -> url.host == V0_HOST && url.encodedPath == V0_SEARCH_SUBJECTS_PATH
    else -> false
}

/**
 * 两个 HttpClient（共享客户端、令牌客户端）共用的「只读重试 + 超时」配置，唯一配置处。
 *
 * ## 重试什么
 *
 * 只在**网络层瞬断**时重试（[isRetryableNetworkFailure]：读超时、TLS 握手被关闭、EOF、连接被重置；域名解析不了、连不上是没网，不重试），
 * 且仅限只读请求（[isReadOnly]）。
 * - HTTP 4xx：请求本身有问题，重发结果不变，不重试。
 * - HTTP 5xx：**不重试**。线上实测里没有 5xx 瞬断；已知的 5xx（p1 隐私 PATCH 恒 500、取消收藏人物 DELETE 500）
 *   都是确定性的服务端缺陷，重发只会让用户多等一轮，并给本就出问题的服务端加压。
 * - 取消（`CancellationException`）：调用方放弃了请求，绝不重试；
 *   但超时可能以「携带 [io.ktor.client.plugins.HttpRequestTimeoutException] 的取消」形式冒出
 *   （Ktor 自带的重试判定同样如此处理），所以先 [unwrapCancellationException] 剥壳再判：
 *   剥出 IOException 的是超时，剥不出的才是调用方的取消。
 *
 * ## 次数与最坏耗时
 *
 * 最多重试 [READ_RETRY_MAX] 次（共 3 次尝试），指数退避加抖动：第 1 次重试前 500 + [0,500) ms，
 * 第 2 次重试前 1000 + [0,500) ms。
 * 最坏情形是三次尝试都耗满 [REQUEST_TIMEOUT_MILLIS]：3 × 15 s + 退避上限 (999 + 1499) ms ≈ **47.5 s**。
 * 典型的 TLS 断连在毫秒级就失败，实际多耗的只有退避的 0.5–1.5 s。
 *
 * ## 为什么 HttpTimeout 装在重试之后
 *
 * Ktor 文档要求：想让超时异常进入重试判定，[HttpTimeout] 必须装在 [HttpRequestRetry] **之后**。
 * 这样超时是**每次尝试各计各的**（总量由上面的最坏耗时给出），而不是三次尝试共用一个 15 s。
 * 两者的先后顺序因此是配置的一部分，放在同一个函数里，两个客户端不会装反。
 */
private fun HttpClientConfig<*>.installReadRetryAndTimeout() {
    install(HttpRequestRetry) {
        // 默认配置会重试 5xx（构造时 retryOnExceptionOrServerErrors(3)），先清掉，再只开下面这一条。
        noRetry()
        retryOnExceptionIf(maxRetries = READ_RETRY_MAX) { request, cause ->
            request.isReadOnly() && cause.unwrapCancellationException().isRetryableNetworkFailure()
        }
        // 只按异常重试，没有按响应重试，Retry-After 头无从谈起。
        exponentialDelay(
            baseDelayMs = READ_RETRY_BASE_DELAY_MILLIS,
            randomizationMs = READ_RETRY_JITTER_MILLIS,
            respectRetryAfterHeader = false,
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
    }
}

/**
 * 构建共享 HttpClient：
 * - 不自动带令牌：客户端由多个身份共用，`loadTokens` 恒为 null，Authorization 由每个请求经 `pinSessionToken` 显式给
 *   （插件若返回全局令牌，会覆盖调用方已设的头，换号就串号）
 * - v0 请求遇 401 单飞刷新令牌（refreshTokens），新令牌给后续请求用
 * - 只读请求遇网络瞬断自动重试，见 [installReadRetryAndTimeout]
 * - 自定义 User-Agent（bangumi API 要求）
 * - 每个域名的并发上限 [API_MAX_REQUESTS_PER_HOST]（见 [apiOkHttpDispatcher]）
 *
 * **不装 HttpCache**：缓存在数据层（Room 的 SWR，`cachedNetworkCall`）。HTTP 层几乎无从命中——p1 响应不带缓存头、
 * v0 搜索是 POST，只有 `/v0/subjects/{id}` 回 `max-age`，而它本来就有 Room 缓存——Ktor 默认的内存存储却把每个
 * 不同地址的 GET 响应体整份留在堆里、从不淘汰（`/p1/home` 一次约 660 KB），还不按 Authorization 区分身份。
 */
fun createHttpClient(
    tokenProvider: TokenProvider,
    tokenRefresher: TokenRefresher,
    appVersion: String,
    enableLogging: Boolean = false,
    engine: HttpClientEngine? = null,
): HttpClient {
    val config: HttpClientConfig<*>.() -> Unit = {
        // 必须 true：toFailure 靠 ResponseException（4xx / 5xx 抛出）认出状态码
        // （Ktor 3 默认 false，4xx 会走错误体解析抛 SerializationException，错误映射全部失效）
        expectSuccess = true

        installJson()

        installReadRetryAndTimeout()

        install(DefaultRequest) {
            headers.append("User-Agent", userAgent(appVersion))
        }

        install(Auth) {
            bearer {
                // 关闭内存缓存：默认 true 时 token 只在首次读取，登出换号后会继续发送旧账号 token
                cacheTokens = false
                // 故意不预注入：本客户端由多个身份共用，token 必须由每个请求经
                // pinSessionToken 显式指定。若这里返回全局 token，Ktor 会用它**覆盖**
                // 调用方已设的 Authorization（实测），换号串号风险回归。
                // 故此处恒为 null，认证头完全由请求方负责。
                loadTokens { null }
                refreshTokens {
                    // 已知限制：Ktor 在 401 重试时不会重跑请求块，也不会应用这里的刷新结果
                    // （实测 blockRuns=1、重试仍发旧 token）。保留此分支只为让刷新链路在
                    // token 过期时仍被触发并写回 TokenStore，供**后续**请求使用；
                    // 不允许对 p1 生效，且 p1 拒绝 OAuth 令牌不等于 v0 会话失效。
                    val request = response.call.request
                    if (request.url.host != V0_HOST) return@refreshTokens null
                    val rejected = request.headers[HttpHeaders.Authorization]?.removePrefix(BEARER_PREFIX)
                    if (tokenRefresher.refresh(rejected)) {
                        tokenProvider.accessToken()?.let { BearerTokens(it, "") }
                    } else {
                        null
                    }
                }
            }
        }

        if (enableLogging) {
            install(Logging) {
                // 只记请求行与状态（INFO），不记响应体：BODY 会把每个响应整份写进 logcat
                // （/p1/home 单次约 660 KB，首页一次刷新十几个请求），排障用不上，只是灌日志。
                level = LogLevel.INFO
            }
        }
    }

    return if (engine != null) {
        HttpClient(engine, config)
    } else {
        HttpClient(OkHttp) {
            engine { config { dispatcher(apiOkHttpDispatcher()) } }
            apply(config)
        }
    }
}

/**
 * 接口客户端每个域名最多同时几个请求。OkHttp 默认 5：探索页一轮刷新正好 5 个 v0 搜索打 api.bgm.tv，
 * 首页的章节补全、进度页的逐集预取、生涯同步也打这个域名，前台请求就得排队，而 15 秒请求超时连排队一起算。
 * 两个接口域名都走 HTTP/2，一条连接多路复用，多几个并发不多开连接（设计决定，not in M3）。
 */
internal const val API_MAX_REQUESTS_PER_HOST = 10

/** 接口客户端的 OkHttp 调度器（唯一配置处）。 */
internal fun apiOkHttpDispatcher() = Dispatcher().apply { maxRequestsPerHost = API_MAX_REQUESTS_PER_HOST }

/** OAuth 凭据请求：不安装 Bearer，避免令牌刷新依赖自身。 */
fun createTokenHttpClient(appVersion: String, engine: HttpClientEngine? = null): HttpClient {
    val config: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = true
        installJson()
        installReadRetryAndTimeout()
        install(DefaultRequest) {
            headers.append("User-Agent", userAgent(appVersion))
        }
    }
    return if (engine != null) HttpClient(engine, config) else HttpClient(OkHttp, config)
}

/** 网页请求：不跟随跳转、不缓存、不带 OAuth，状态与 Cookie 都由调用方处理。 */
fun createWebHttpClient(engine: HttpClientEngine? = null): HttpClient {
    val config: HttpClientConfig<*>.() -> Unit = {
        expectSuccess = false
        followRedirects = false
        installReadRetryAndTimeout()
        install(DefaultRequest) {
            headers.append("User-Agent", WEB_USER_AGENT)
        }
    }
    return if (engine != null) HttpClient(engine, config) else HttpClient(OkHttp, config)
}

/** core:network 的 Koin 模块（app 壳调用，传入 App 版本、BuildConfig.DEBUG 与 AuthConfig） */
fun networkModule(
    appVersion: String,
    enableLogging: Boolean,
    authConfig: AuthConfig,
) = module {
    single { createHttpClient(get(), get(), appVersion, enableLogging) }
    single {
        // AuthApi 使用独立客户端：token 端点是凭据型请求，不需要 Bearer 注入与 401 刷新；
        // 若复用共享 HttpClient 会形成构造循环（HttpClient → TokenRefresher → AuthApi → HttpClient）
        AuthApi(createTokenHttpClient(appVersion), authConfig)
    }
    single { UserApi(get()) }
    single { IndexApi(get()) }
    single { SubjectApi(get()) }
    single { CollectionApi(get()) }
    single { NextApi(get()) }
    // 透视页是公开的只读网页，不需要 Cookie、不看跳转，所以用接口客户端：5xx 照常抛 ServerResponseException（冷页重试靠它），
    // 令牌由 WebPageApi 每个请求摘掉（pinSessionToken(null)）。要 Cookie 或要看跳转的网页走下面的网页客户端。
    single { WebPageApi(get()) }
    single(named(WEB_CLIENT)) {
        // 网页（不是接口）用独立客户端：不跟随跳转（登录、授权、操作链接的结果都在跳转本身）、不缓存、不带令牌，
        // 非 2xx 由使用方自己判。Cookie 由每个请求显式带上。
        createWebHttpClient()
    }
    single { WebSessionApi(get(named(WEB_CLIENT))) }
    single { WebLoginApi(get(named(WEB_CLIENT)), authConfig) }
    single { PrivacyApi(get()) }
    single { TimelineApi(get()) }
    single { CommunityApi(get()) }
    single { MonoApi(get()) }
    // bangumi-data 不是 bgm.tv 的接口，但只读、要解码、要只读重试，和透视页一样用接口客户端，令牌由它每个请求摘掉。
    single { BangumiDataApi(get()) }
    // 检查更新读本应用 GitHub Release 的 release.json：同上，只读、用接口客户端，令牌由它每个请求摘掉。
    single { ReleaseApi(get()) }
}
