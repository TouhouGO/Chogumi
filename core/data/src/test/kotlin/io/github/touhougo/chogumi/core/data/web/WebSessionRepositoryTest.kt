package io.github.touhougo.chogumi.core.data.web

import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.token.SessionChangedException
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class WebSessionRepositoryTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val site = TestWebSite(userId = 7)
    private val tokenStore: TokenStore = testTokenStore(resources, userId = 7)
    private val repository = testWebSessionRepository(tokenStore, site.client)

    private suspend fun saveCookie() = repository.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))

    private suspend fun assertLoginRequired() {
        val e = assertFailsWith<FailureException> { repository.perform { formhash -> "https://bgm.tv/act?gh=$formhash" } }
        assertEquals(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED), e.failure)
    }

    @Test
    fun `带会话打开操作链接_链接上是网页给的表单校验值`() = runTest {
        saveCookie()
        repository.perform { formhash -> "https://bgm.tv/act?gh=$formhash" }
        assertEquals(listOf<Pair<String, String?>>("https://bgm.tv/act?gh=0a1b2c3d" to "chii_auth=c00k1e"), site.opened)
    }

    @Test
    fun `没有网页会话时要账号密码登录且不开网页`() = runTest {
        assertLoginRequired()
        assertTrue(site.opened.isEmpty())
    }

    @Test
    fun `网页那边已登出时要账号密码登录并丢掉会话`() = runTest {
        saveCookie()
        site.userId = 0
        assertLoginRequired()
        assertTrue(site.opened.isEmpty())
        assertNull(tokenStore.webSessionFor(tokenStore.session()))
    }

    @Test
    fun `网页上登录的不是当前账号时不开链接并丢掉会话`() = runTest {
        saveCookie()
        site.userId = 8
        assertLoginRequired()
        assertTrue(site.opened.isEmpty())
        assertNull(tokenStore.webSessionFor(tokenStore.session()))
    }

    @Test
    fun `登出与重新登录都清掉网页会话`() = runTest {
        saveCookie()
        assertNotNull(tokenStore.webSessionFor(tokenStore.session()))
        tokenStore.clear()
        assertNull(tokenStore.webSessionFor(tokenStore.session()), "登出")

        val session = tokenStore.completeSession(tokenStore.beginSession("token"), userId = 7, username = "fixture_user")
        repository.save(session, WebSession("chii_auth=c00k1e"))
        tokenStore.beginSession("another")
        assertNull(tokenStore.webSessionFor(tokenStore.session()), "换号登录")
    }

    @Test
    fun `拼操作链接期间换号不打开链接`() = runTest {
        saveCookie()
        assertFailsWith<SessionChangedException> {
            repository.perform { formhash ->
                tokenStore.completeSession(tokenStore.beginSession("other"), userId = 8, username = "bob")
                "https://bgm.tv/act?gh=$formhash"
            }
        }
        assertTrue(site.opened.isEmpty())
    }

    private fun switchingRepository(path: String, body: String, requests: ConcurrentRequestRecorder) =
        testWebSessionRepository(tokenStore, testWebClient { request ->
            if (request.url.encodedPath == "/settings/privacy") {
                respond("""CHOBITS_UID = 7; <a href="/logout/a1b2">登出</a>""", HttpStatusCode.OK)
            } else {
                requests.record(request)
                if (request.url.encodedPath == path) {
                    tokenStore.completeSession(tokenStore.beginSession("other"), userId = 8, username = "bob")
                }
                respond(body, HttpStatusCode.OK)
            }
        })

    @Test
    fun `操作页返回时换号不打开页面上的操作链接`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val repo = switchingRepository("/page", "<a href='/act'>操作</a>", requests)
        repo.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        assertFailsWith<SessionChangedException> {
            repo.performOnPage("https://bgm.tv/page", Failure.BadResponse) { "/act" }
        }
        assertEquals(listOf(HttpMethod.Get), requests.map { it.method })
    }

    @Test
    fun `操作响应期间换号不返回成功`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val repo = switchingRepository("/act", "", requests)
        repo.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        assertFailsWith<SessionChangedException> { repo.perform { "https://bgm.tv/act" } }
        assertEquals(listOf(HttpMethod.Get), requests.map { it.method })
    }

    @Test
    fun `确认表单返回时换号不提交表单`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val repo = switchingRepository(
            "/act",
            """<form action="/act" method="post"><input type="hidden" name="formhash" value="a1b2"><input type="submit" name="submit" value="确定"></form>""",
            requests,
        )
        repo.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        assertFailsWith<SessionChangedException> { repo.perform { "https://bgm.tv/act" } }
        assertEquals(listOf(HttpMethod.Get), requests.map { it.method })
    }

    @Test
    fun `普通取消在账号切换后仍原样传播`() = runTest {
        saveCookie()
        val cancellation = CancellationException("操作已取消")
        val thrown = assertFailsWith<CancellationException> {
            repository.perform {
                tokenStore.completeSession(tokenStore.beginSession("other"), userId = 8, username = "bob")
                throw cancellation
            }
        }
        assertSame(cancellation, thrown)
        assertTrue(site.opened.isEmpty())
    }

}
