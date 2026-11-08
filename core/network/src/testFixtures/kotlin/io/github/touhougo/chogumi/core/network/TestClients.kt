package io.github.touhougo.chogumi.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler

private object GuestTokenProvider : TokenProvider {
    override suspend fun accessToken(): String? = null
}

private object NoTokenRefresh : TokenRefresher {
    override suspend fun refresh(rejectedAccessToken: String?): Boolean = false
}

/** API 测试只替换传输层，序列化、鉴权、缓存与错误处理全部保留生产配置。 */
fun testApiClient(engine: HttpClientEngine): HttpClient = createHttpClient(
    tokenProvider = GuestTokenProvider,
    tokenRefresher = NoTokenRefresh,
    appVersion = "test",
    engine = engine,
)

fun testApiClient(handler: MockRequestHandler): HttpClient = testApiClient(MockEngine(handler))
