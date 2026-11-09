package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.TokenProvider
import io.github.touhougo.chogumi.core.network.TokenRefresher
import io.github.touhougo.chogumi.core.network.createHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `pinSessionToken` 的回归契约。
 *
 * 这些用例跑在**真实生产客户端**（[createHttpClient]，含 Auth 插件）上，
 * 而不是精简的测试客户端——这类缺陷恰好藏在"测试客户端没装 Auth 插件"这个
 * 差异里隐身：假服务端无条件 200，从来看不到 token 有没有被带上。
 *
 * 因此这里的假服务端会像 bgm.tv 一样挑食：Authorization 不对就 401 need Login。
 */
class SessionRequestTest {

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    /** 记录全局 token，模拟 TokenStore。 */
    private class Provider(var token: String?) : TokenProvider {
        override suspend fun accessToken(): String? = token
    }

    private object NoRefresh : TokenRefresher {
        override suspend fun refresh(rejectedAccessToken: String?): Boolean = false
    }

    /**
     * 构造真实生产客户端 + 会校验鉴权头的假服务端。
     * @param expectedToken 服务端认可的 token；不匹配或缺失一律 401。
     */
    private fun fixture(provider: Provider, expectedToken: String?): Pair<io.ktor.client.HttpClient, List<String?>> {
        val seen = CopyOnWriteArrayList<String?>()
        val engine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            seen += auth
            // 与 bgm.tv 实测一致：无头 → 401 need Login
            val ok = expectedToken == null || auth == "Bearer $expectedToken"
            respond(
                content = if (ok) """{"ok":true}""" else """{"title":"Unauthorized","description":"need Login"}""",
                status = if (ok) HttpStatusCode.OK else HttpStatusCode.Unauthorized,
                headers = jsonHeaders,
            )
        }
        return createHttpClient(provider, NoRefresh, enableLogging = false, engine = engine, appVersion = "test", customizers = emptyList()) to seen
    }

    @Test
    fun `显式 token 优先于全局 token（插件不得覆盖）`() = runTest {
        // 全局 token 是另一个账号的。若插件覆盖，服务端会看到 "Bearer other" → 命中 401。
        val (client, seen) = fixture(Provider("other_account_token"), expectedToken = "mine")
        client.get("https://api.bgm.tv/v0/me") { pinSessionToken("mine") }
        assertEquals(listOf<String?>("Bearer mine"), seen)
    }

    @Test
    fun `匿名请求不带 Authorization 头`() = runTest {
        val (client, seen) = fixture(Provider("global"), expectedToken = null)
        client.get("https://next.bgm.tv/p1/timeline") { pinSessionToken(null) }
        assertNull(seen.single())
    }

    @Test
    fun `空白 token 等同匿名而不是发空 Bearer`() = runTest {
        val (client, seen) = fixture(Provider("global"), expectedToken = null)
        client.get("https://next.bgm.tv/p1/timeline") { pinSessionToken("   ") }
        assertNull(seen.single())
    }

    @Test
    fun `显式 pin 的请求不依赖插件刷新（已知限制，钉住行为）`() = runTest {
        // 实测对照：
        //   纯插件驱动（无显式头） → 刷新后重试发送 [old, new]  ✅ 刷新生效
        //   显式 pin（本用例）      → 刷新后重试发送 [old, old]  ❌ 重试沿用已构建的请求
        // 二者不可兼得：插件刷新隐含"一个 client 一个身份"，而 pinSessionToken 固定"发起时身份"。
        // 本用例钉住这个取舍，防止有人误以为这里有自动刷新而写出依赖它的代码。
        // 若将来自建 401 单飞刷新，此用例应随之更新。
        val seen = CopyOnWriteArrayList<String?>()
        val calls = AtomicInteger()
        val refreshed = AtomicBoolean()
        val engine = MockEngine { request ->
            seen += request.headers[HttpHeaders.Authorization]
            if (calls.incrementAndGet() == 1) {
                respond("""{"title":"Unauthorized","description":"expired"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            } else {
                respond("""{"ok":true}""", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = createHttpClient(Provider("global"), object : TokenRefresher {
            override suspend fun refresh(rejectedAccessToken: String?): Boolean { refreshed.set(true); return true }
        }, engine = engine, appVersion = "test", customizers = emptyList())

        runCatching { client.get("https://api.bgm.tv/v0/me") { pinSessionToken("expired_token") } }

        assertTrue(refreshed.get(), "过期时仍会触发刷新链路（写回 TokenStore 供后续请求使用）")
        assertEquals(
            listOf<String?>("Bearer expired_token", "Bearer expired_token"),
            seen,
            "重试沿用首次构建的请求，不用刷新结果——这是已知限制，不是回归",
        )
    }

    @Test
    fun `多账号交替请求不得串号`() = runTest {
        // pinSessionToken 存在的意义就是支撑"一个 client 服务多个身份"。
        // 服务端对多账号各认各的 token；任一次串号都会 401。
        val seen = CopyOnWriteArrayList<String?>()
        val engine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            seen += auth
            val allowed = setOf("Bearer token_a", "Bearer token_b")
            respond(
                content = if (auth in allowed) """{"ok":true}""" else """{"title":"Unauthorized","description":"need Login"}""",
                status = if (auth in allowed) HttpStatusCode.OK else HttpStatusCode.Unauthorized,
                headers = jsonHeaders,
            )
        }
        val client = createHttpClient(Provider("global_should_never_be_used"), NoRefresh, engine = engine, appVersion = "test", customizers = emptyList())

        client.get("https://api.bgm.tv/v0/me") { pinSessionToken("token_a") }
        client.get("https://api.bgm.tv/v0/me") { pinSessionToken("token_b") }
        client.get("https://api.bgm.tv/v0/me") { pinSessionToken("token_a") }

        assertEquals(
            listOf<String?>("Bearer token_a", "Bearer token_b", "Bearer token_a"),
            seen,
            "每个请求必须携带各自捕获的账号 token，不得受全局 token 或前一个请求影响",
        )
    }
}
