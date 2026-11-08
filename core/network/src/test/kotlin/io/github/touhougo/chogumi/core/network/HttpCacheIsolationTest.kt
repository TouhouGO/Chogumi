package io.github.touhougo.chogumi.core.network

import io.github.touhougo.chogumi.core.network.api.pinSessionToken
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpCacheIsolationTest {

    private class FakeTokenProvider(var token: String? = "token_user_a") : TokenProvider {
        override suspend fun accessToken(): String? = token
    }

    private object FakeRefresher : TokenRefresher {
        override suspend fun refresh(rejectedAccessToken: String?): Boolean = false
    }

    /**
     * 接口客户端不做 HTTP 缓存：Ktor 的 HttpCache 不按 Authorization 区分身份，装着它时，登录态取回的
     * `public, max-age` 响应会原样应答之后同一地址的匿名请求。
     */
    @Test
    fun `同一地址换身份再请求必须打到服务端，不拿另一个身份的响应`() = runTest {
        val engineRequests = AtomicInteger()
        val engine = MockEngine { request ->
            engineRequests.incrementAndGet()
            respond(
                content = """{"id":1,"auth":"${request.headers["Authorization"].orEmpty()}"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "Content-Type" to listOf("application/json"),
                    "Cache-Control" to listOf("public, max-age=3600"),
                    "Vary" to listOf("Origin, accept-encoding"),
                ),
            )
        }
        val client = createHttpClient(FakeTokenProvider(), FakeRefresher, engine = engine, appVersion = "test")

        client.get("https://api.bgm.tv/v0/subjects/1") { pinSessionToken("token_user_a") }
        val anonymous = client.get("https://api.bgm.tv/v0/subjects/1") { pinSessionToken(null) }

        assertEquals(2, engineRequests.get(), "匿名请求被另一个身份的缓存响应应答了")
        assertEquals("""{"id":1,"auth":""}""", anonymous.bodyAsText())
    }
}
