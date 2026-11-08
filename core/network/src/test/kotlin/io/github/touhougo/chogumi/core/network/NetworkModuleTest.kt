package io.github.touhougo.chogumi.core.network

import io.github.touhougo.chogumi.core.network.api.pinSessionToken
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class NetworkModuleTest {

    private class FakeTokenProvider(var token: String? = "old_token") : TokenProvider {
        override suspend fun accessToken(): String? = token
    }

    private class FakeTokenRefresher(var result: Boolean = true) : TokenRefresher {
        val called = AtomicInteger()
        val rejected = CopyOnWriteArrayList<String?>()
        override suspend fun refresh(rejectedAccessToken: String?): Boolean {
            called.incrementAndGet()
            rejected += rejectedAccessToken
            return result
        }
    }

    @Test
    fun `共享客户端不自动注入全局 token，由调用方显式指定`() = runTest {
        // 共享 HttpClient 服务多个身份，插件预注入会覆盖调用方显式设置的头
        // （实测：装了 Auth 插件时 loadTokens 的结果会顶掉请求块里的 bearerAuth）。
        // 因此 loadTokens 恒为 null，认证头由每个请求经 pinSessionToken 自行负责。
        // 详见 SessionRequestTest 与 SessionRequest.pinSessionToken 的注释。
        val provider = FakeTokenProvider()
        val engine = MockEngine {
            respond(
                content = """{"id":1,"username":"a","nickname":"A"}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = createHttpClient(provider, FakeTokenRefresher(), engine = engine, appVersion = "test")
        client.get("https://api.bgm.tv/v0/me")
        val request = engine.requestHistory.single()
        assertNull(request.headers["Authorization"], "插件不得自动注入 token，以免覆盖显式身份")
        assertEquals(userAgent("test"), request.headers["User-Agent"])
    }

    @Test
    fun `401 触发刷新并重试成功`() = runTest {
        val provider = FakeTokenProvider()
        val refresher = FakeTokenRefresher(result = true)
        val callCount = AtomicInteger()
        val engine = MockEngine {
            if (callCount.incrementAndGet() == 1) {
                respond(content = "expired", status = HttpStatusCode.Unauthorized)
            } else {
                respond(
                    content = """{"id":1,"username":"a","nickname":"A"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }
        provider.token = "new_token"
        val client = createHttpClient(provider, refresher, engine = engine, appVersion = "test")
        val response = client.get("https://api.bgm.tv/v0/me")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, callCount.get())
        assertEquals(1, refresher.called.get())
    }

    @Test
    fun `刷新失败时透传 401 异常`() = runTest {
        val provider = FakeTokenProvider()
        val refresher = FakeTokenRefresher(result = false)
        val engine = MockEngine {
            respond(content = "denied", status = HttpStatusCode.Unauthorized)
        }
        val client = createHttpClient(provider, refresher, engine = engine, appVersion = "test")
        // expectSuccess = true：最终 401 以 ClientRequestException 抛出（上层 toFailure 映射）
        assertFailsWith<io.ktor.client.plugins.ClientRequestException> {
            client.get("https://api.bgm.tv/v0/me")
        }
        assertEquals(1, refresher.called.get())
    }

    @Test
    fun `刷新拿到的是挨了 401 的那个请求带的令牌`() = runTest {
        val refresher = FakeTokenRefresher(result = false)
        val engine = MockEngine { respond(content = "expired", status = HttpStatusCode.Unauthorized) }
        val client = createHttpClient(FakeTokenProvider(), refresher, engine = engine, appVersion = "test")

        assertFailsWith<io.ktor.client.plugins.ClientRequestException> {
            client.get("https://api.bgm.tv/v0/me") { pinSessionToken("pinned_token") }
        }

        assertEquals(listOf<String?>("pinned_token"), refresher.rejected)
    }

    @Test
    fun `p1 的 401 不触发刷新`() = runTest {
        val refresher = FakeTokenRefresher(result = true)
        val engine = MockEngine { respond(content = "need login", status = HttpStatusCode.Unauthorized) }
        val client = createHttpClient(FakeTokenProvider(), refresher, engine = engine, appVersion = "test")

        assertFailsWith<io.ktor.client.plugins.ClientRequestException> {
            client.get("https://next.bgm.tv/p1/me") { pinSessionToken("pinned_token") }
        }

        assertEquals(0, refresher.called.get(), "p1 拒绝 OAuth 令牌不等于 v0 会话失效")
        assertEquals(1, engine.requestHistory.size)
    }
}
