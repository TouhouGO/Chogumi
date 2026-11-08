package io.github.touhougo.chogumi.core.data.token

import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.AuthApi
import io.github.touhougo.chogumi.core.network.createTokenHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/** 401 时的令牌刷新：什么失败才算登录失效、并发的 401 只刷一次、途中换号不写给新账号。 */
class TokenRefresherImplTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val store = testTokenStore(resources)
    private val refreshRequests = AtomicInteger()

    private fun refresher(answer: suspend MockRequestHandleScope.() -> HttpResponseData): TokenRefresherImpl {
        val engine = MockEngine {
            refreshRequests.incrementAndGet()
            answer()
        }
        val api = AuthApi(resources.own(createTokenHttpClient("test", engine)), AuthConfig("id", "secret", "chogumi://oauth/callback"))
        return TokenRefresherImpl(api, store)
    }

    private fun MockRequestHandleScope.json(status: HttpStatusCode, body: String) =
        respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString()))

    private fun MockRequestHandleScope.newTokens() = json(
        HttpStatusCode.OK,
        """{"access_token":"at-2","expires_in":604800,"token_type":"Bearer","refresh_token":"rt-2","user_id":"1"}""",
    )

    private suspend fun signIn(): AccountSession =
        store.completeSession(store.beginSession("at-1", refreshToken = "rt-1"), userId = 1, username = "fixture_user")

    @Test
    fun `刷新时断网_保留登录与令牌`() = runTest {
        val session = signIn()

        assertFalse(refresher { throw UnknownHostException("bgm.tv") }.refresh("at-1"))

        assertEquals(session, store.session())
        assertEquals("at-1", store.accessToken())
        assertEquals("rt-1", store.refreshGrant()?.refreshToken)
    }

    @Test
    fun `令牌端点 5xx_保留登录`() = runTest {
        val session = signIn()

        assertFalse(refresher { json(HttpStatusCode.BadGateway, "{}") }.refresh("at-1"))

        assertEquals(session, store.session())
        assertEquals("at-1", store.accessToken())
    }

    @Test
    fun `服务端拒绝刷新令牌_清空会话`() = runTest {
        signIn()

        val ok = refresher { json(HttpStatusCode.BadRequest, """{"error":"invalid_grant"}""") }.refresh("at-1")

        assertFalse(ok)
        assertFalse(store.session().authenticated)
        assertNull(store.accessToken())
    }

    @Test
    fun `刷新成功_写入新令牌`() = runTest {
        val session = signIn()

        assertTrue(refresher { newTokens() }.refresh("at-1"))

        assertEquals("at-2", store.accessToken())
        assertEquals("rt-2", store.refreshGrant()?.refreshToken)
        assertEquals(session.generation, store.session().generation)
    }

    @Test
    fun `两个请求同时挨 401_只发一次刷新`() = runTest {
        signIn()
        val subject = refresher { newTokens() }

        val results = listOf(async { subject.refresh("at-1") }, async { subject.refresh("at-1") }).awaitAll()

        assertEquals(listOf(true, true), results)
        assertEquals(1, refreshRequests.get())
        assertEquals("at-2", store.accessToken())
    }

    @Test
    fun `没带令牌的请求挨 401_不刷新`() = runTest {
        signIn()

        assertFalse(refresher { newTokens() }.refresh(null))

        assertEquals(0, refreshRequests.get())
        assertEquals("at-1", store.accessToken())
    }

    @Test
    fun `刷新途中换了账号_新令牌不写给新账号`() = runTest {
        signIn()
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val subject = refresher {
            arrived.complete(Unit)
            release.await()
            newTokens()
        }

        val refreshing = async { subject.refresh("at-1") }
        arrived.await()
        val other = store.completeSession(store.beginSession("other-at", refreshToken = "other-rt"), userId = 2, username = "b")
        release.complete(Unit)

        assertFalse(refreshing.await())
        assertEquals(other, store.session())
        assertEquals("other-at", store.accessToken())
        assertEquals("other-rt", store.refreshGrant()?.refreshToken)
    }
}
