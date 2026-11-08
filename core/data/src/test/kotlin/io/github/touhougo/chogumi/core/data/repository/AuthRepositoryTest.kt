package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.StoredWebSession
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.web.CookieCipher
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.createTokenHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class AuthRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private val config = AuthConfig("cid", "secret", "chogumi://oauth/callback")

    private class Handler {
        val tokenRequests = AtomicInteger()
        val meRequests = AtomicInteger()
        @Volatile var meStatus: HttpStatusCode = HttpStatusCode.OK
        val requests = ConcurrentRequestRecorder()
        var exchangeGate: suspend () -> Unit = {}

        /** 置非空：`/v0/me` 停在这里等它（模拟登录途中）；[meEntered] 在请求到达时完成。 */
        var meGate: CompletableDeferred<Unit>? = null
        val meEntered = CompletableDeferred<Unit>()

        /** 期望被带上的 token；非空时，缺失或不匹配一律按 bgm.tv 真实行为返回 401 need Login。 */
        @Volatile var expectedToken: String? = null

        /** 每次 `/v0/me` 实际带上的 Authorization 头，按发出顺序（这些用例里的请求一个接一个）。 */
        fun authorizationHeaders(): List<String?> = requests
            .filter { it.url.encodedPath == "/v0/me" }
            .map { it.headers["Authorization"] }
    }

    private val tokenJson =
        """{"access_token":"at","expires_in":604800,"token_type":"Bearer","refresh_token":"rt","user_id":"42"}"""

    private val meJson =
        """{"id":42,"username":"fixture_user","nickname":"Fixture User","user_group":10,
            "avatar":{"large":"","medium":"","small":""},"sign":""}"""

    private fun buildRepo(
        handler: Handler,
        webClient: HttpClient = resources.own(noWebClient()),
        cipher: CookieCipher = PlainCookieCipher,
        newTokenStore: (CoroutineScope) -> TokenStore = ::testTokenStore,
    ): Pair<AuthRepository, TokenStore> {
        val engine = MockEngine { request ->
            handler.requests.record(request)
            when (request.url.encodedPath) {
                "/oauth/access_token" -> {
                    handler.tokenRequests.incrementAndGet()
                    handler.exchangeGate()
                    respond(
                        content = tokenJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

                "/v0/me" -> {
                    handler.meRequests.incrementAndGet()
                    handler.meEntered.complete(Unit)
                    handler.meGate?.await()
                    val auth = request.headers["Authorization"]
                    // 与 bgm.tv 实测行为对齐：缺头 → 401 {"title":"Unauthorized","description":"need Login"}
                    val expected = handler.expectedToken
                    val unauthorized = expected != null && auth != "Bearer $expected"
                    respond(
                        content = if (unauthorized) {
                            """{"title":"Unauthorized","description":"need Login"}"""
                        } else {
                            meJson
                        },
                        status = if (unauthorized) HttpStatusCode.Unauthorized else handler.meStatus,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }

                else -> respond(content = "{}", status = HttpStatusCode.NotFound)
            }
        }
        val client = resources.apiClient(engine)
        val scope = resources.own(CoroutineScope(Dispatchers.IO + SupervisorJob()))
        val tokenStore = newTokenStore(scope)
        return Pair(
            testAuthRepository(resources, client, tokenStore, config, scope, webClient, createTokenHttpClient("test", engine), cipher),
            tokenStore,
        )
    }

    @Test
    fun `成功登录路径必须携带 token 访问 v0-me`() = runTest {
        // 模拟真实服务端：token 不对就 401。若实现发出无 Authorization 的请求，本用例必红。
        val handler = Handler().apply { expectedToken = "at" }
        val (repo, tokenStore) = buildRepo(handler)
        val state = repo.buildAuthorizeUrl().substringAfter("state=")

        assertTrue(repo.onCallback(code = "the_code", state = state), "登录应当成功")

        assertEquals(listOf<String?>("Bearer at"), handler.authorizationHeaders())
        assertTrue(repo.isLoggedIn.value)
        assertEquals("at", tokenStore.accessToken())
    }

    @Test
    fun `onCallback 成功后进入登录态并保存用户`() = runTest {
        val handler = Handler()
        val (repo, tokenStore) = buildRepo(handler)
        val url = repo.buildAuthorizeUrl()
        assertTrue(url.startsWith("https://bgm.tv/oauth/authorize?client_id=cid&response_type=code"))
        assertTrue(url.contains("redirect_uri=chogumi%3A%2F%2Foauth%2Fcallback"))
        val state = url.substringAfter("state=")
        assertTrue(repo.onCallback(code = "the_code", state = state))
        assertTrue(repo.isLoggedIn.value)
        // 用过即删：state 在校验时已消费
        assertFalse(tokenStore.consumeOAuthState(state))
        assertEquals("at", tokenStore.accessToken())
        assertEquals("rt", tokenStore.refreshGrant()?.refreshToken)
        assertEquals(42, tokenStore.session().userId)
        assertEquals("fixture_user", tokenStore.session().username)
        assertEquals(1, handler.tokenRequests.get())
        assertEquals(1, handler.meRequests.get())
    }

    @Test
    fun `state 不匹配时拒绝回调`() = runTest {
        val handler = Handler()
        val (repo, _) = buildRepo(handler)
        repo.buildAuthorizeUrl()
        assertFalse(repo.onCallback(code = "code", state = "wrong_state"))
        assertFalse(repo.isLoggedIn.value)
        assertEquals(0, handler.tokenRequests.get())
        assertEquals(Failure.Rejected(RejectReason.LOGIN_CALLBACK_MISMATCH), repo.loginError.value, "回调对不上不是换了号")
    }

    @Test
    fun `令牌登录后迟到的旧 OAuth 回调被拒 不覆盖当前账号也不留登录错误`() = runTest {
        val handler = Handler()
        val (repo, tokenStore) = buildRepo(handler)
        val staleState = repo.buildAuthorizeUrl().substringAfter("state=")

        assertTrue(repo.loginWithToken("account-a-token"))
        assertFalse(repo.onCallback(code = "account-b-code", state = staleState))

        assertEquals("account-a-token", tokenStore.accessToken())
        assertEquals(42, tokenStore.session().userId)
        assertEquals(0, handler.tokenRequests.get(), "旧回调不得开始换令牌请求")
        assertNull(repo.loginError.value, "已登录时被拒的回调没人在等，不留到下次打开登录页")
    }

    @Test
    fun `登录途中到达的旧授权回调等这次登录结束后被拒 不换令牌`() = runTest {
        callbackDuringPasswordLogin(stateSavedBeforeLogin = true)
    }

    @Test
    fun `登录途中新发起的授权 回调在这次登录结束前到达也被拒 不换令牌`() = runTest {
        callbackDuringPasswordLogin(stateSavedBeforeLogin = false)
    }

    /**
     * 账号密码登录停在换令牌时，浏览器授权的回调到达。[stateSavedBeforeLogin]：授权在这次登录开始前发起，否则在它途中发起。
     * 两种都必须等这次登录结束再核对 state，那时授权已被作废：回调被拒，不再换令牌，账号仍是这次登录的。
     */
    private suspend fun TestScope.callbackDuringPasswordLogin(stateSavedBeforeLogin: Boolean) {
        val handler = Handler()
        val (repo, tokenStore) = buildRepo(handler, webSite)
        val attempt = (repo.passwordCaptcha(attempt = null) as Outcome.Success).value.attempt
        val savedBefore = if (stateSavedBeforeLogin) repo.buildAuthorizeUrl().substringAfter("state=") else null
        val exchangeEntered = CompletableDeferred<Unit>()
        val exchangeRelease = CompletableDeferred<Unit>()
        handler.exchangeGate = {
            exchangeEntered.complete(Unit)
            exchangeRelease.await()
        }

        val login = async { repo.loginWithPassword(attempt, "a@b.c", "pw", "abcd") }
        exchangeEntered.await()
        val state = savedBefore ?: repo.buildAuthorizeUrl().substringAfter("state=")
        // UNDISPATCHED：回调当场跑到第一次挂起为止，登录权还在这次登录手里，回调在登录结束前已经到了。
        val callback = async(start = CoroutineStart.UNDISPATCHED) { repo.onCallback(code = "other-account-code", state = state) }

        exchangeRelease.complete(Unit)
        assertTrue(login.await())
        assertFalse(callback.await(), "回调核对 state 时这次登录已结束，授权已作废")
        assertEquals(1, handler.tokenRequests.get(), "被拒的回调不得再换一次令牌")
        assertEquals("at", tokenStore.accessToken())
        assertEquals(42, tokenStore.session().userId)
    }

    @Test
    fun `伪造的回调不消费真 state 真回调照样登录`() = runTest {
        val handler = Handler()
        val (repo, _) = buildRepo(handler)
        val state = repo.buildAuthorizeUrl().substringAfter("state=")

        assertFalse(repo.onCallback(code = "forged", state = "forged_state"))
        assertTrue(repo.onCallback(code = "the_code", state = state), "正在进行的登录不被伪造回调打断")
        assertTrue(repo.isLoggedIn.value)
    }

    @Test
    fun `回调交出去后界面被销毁 登录照样完成`() = runTest {
        val handler = Handler().apply { meGate = CompletableDeferred() }
        val (repo, tokenStore) = buildRepo(handler)
        val state = repo.buildAuthorizeUrl().substringAfter("state=")
        val activity = CoroutineScope(Dispatchers.IO + SupervisorJob())

        activity.launch { repo.handleCallback(code = "the_code", state = state) }
        handler.meEntered.await()
        activity.coroutineContext[Job]!!.cancelAndJoin()
        handler.meGate!!.complete(Unit)

        withContext(Dispatchers.IO) { withTimeout(WAIT_BUDGET_MS) { repo.isLoggedIn.first { it } } }
        assertEquals("at", tokenStore.accessToken())
    }

    @Test
    fun `me 失败时报告错误且不进入登录态`() = runTest {
        val handler = Handler().apply { meStatus = HttpStatusCode.Unauthorized }
        val (repo, tokenStore) = buildRepo(handler)
        val url = repo.buildAuthorizeUrl()
        val state = url.substringAfter("state=")
        assertFalse(repo.onCallback(code = "c", state = state))
        assertFalse(repo.isLoggedIn.value)
        assertEquals(Failure.Http(401), repo.loginError.value)
        assertEquals(1, handler.tokenRequests.get())
        assertEquals(1, handler.meRequests.get(), "失败必须来自携带新令牌访问当前用户")
        assertEquals(listOf("Bearer at"), handler.authorizationHeaders())
        assertEquals(null, tokenStore.accessToken())
    }

    @Test
    fun `登录途中取消_写了一半的令牌被清掉`() = runTest {
        val handler = Handler().apply { meGate = CompletableDeferred() }
        val (repo, tokenStore) = buildRepo(handler)
        val login = launch { repo.loginWithToken("half") }
        handler.meEntered.await()
        assertEquals("half", tokenStore.accessToken(), "前提：令牌已写下、账号信息还没到")

        login.cancelAndJoin()

        assertNull(tokenStore.accessToken())
        assertFalse(repo.isLoggedIn.value)
    }

    @Test
    fun `回调里本机写入失败 收成登录失败而不是冲出作用域`() = runTest {
        lateinit var dataStore: ControllableDataStore
        val (repo) = buildRepo(Handler()) { scope ->
            dataStore = ControllableDataStore(scope)
            TokenStore(dataStore)
        }
        repo.ensureInitialized()
        val state = repo.buildAuthorizeUrl().substringAfter("state=")
        dataStore.failWrites = true

        repo.handleCallback(code = "the_code", state = state)

        awaitUntil("本机写入失败记为登录失败") { repo.loginError.value != null }
    }

    /** 包着真实 DataStore：可以让写入抛 [IOException]。 */
    private inner class ControllableDataStore(scope: CoroutineScope) : DataStore<Preferences> {
        private val directory = resources.directory("token-ctl")
        private val real = PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "tokens.preferences_pb")
        }
        @Volatile var failWrites = false

        override val data: Flow<Preferences> = real.data

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failWrites) throw IOException("磁盘满")
            return real.updateData(transform)
        }
    }

    @Test
    fun `手动 token 登录成功`() = runTest {
        val handler = Handler()
        val (repo, tokenStore) = buildRepo(handler)
        assertTrue(repo.loginWithToken("manual_token"))
        assertTrue(repo.isLoggedIn.value)
        assertEquals("manual_token", tokenStore.accessToken())
    }

    @Test
    fun `登出清空令牌并同步为未登录状态`() = runTest {
        val handler = Handler()
        val (repo, tokenStore) = buildRepo(handler)
        assertTrue(repo.loginWithToken("t"))
        assertEquals(42, tokenStore.session().userId)
        repo.logout()
        assertFalse(repo.isLoggedIn.value)
        assertEquals(null, tokenStore.accessToken())
        assertEquals(SessionState.Unauthenticated, repo.sessionState.value)
        assertFalse(tokenStore.session().authenticated)
    }

    @Test
    fun `同一 code 与 state 重放被拒且 token 完好`() = runTest {
        val handler = Handler()
        val (repo, tokenStore) = buildRepo(handler)
        val url = repo.buildAuthorizeUrl()
        val state = url.substringAfter("state=")
        assertTrue(repo.onCallback(code = "the_code", state = state))
        assertTrue(repo.isLoggedIn.value)
        val tokenBefore = tokenStore.accessToken()
        assertFalse(repo.onCallback(code = "the_code", state = state))
        assertEquals(tokenBefore, tokenStore.accessToken())
        assertTrue(repo.isLoggedIn.value)
    }

    @Test
    fun `空 state 深链被拒`() = runTest {
        val handler = Handler()
        val (repo, _) = buildRepo(handler)
        repo.buildAuthorizeUrl()
        assertFalse(repo.onCallback(code = "c", state = ""))
        assertFalse(repo.isLoggedIn.value)
        assertEquals(0, handler.tokenRequests.get())
    }

    /** bgm.tv 网页：验证码 abcd 才登录成功（发 chii_auth），授权过本应用，授权页直接跳回回调地址带 code。 */
    private val webSite = testWebClient { request ->
        when (request.url.encodedPath) {
            "/login" -> respond("""<input type="hidden" name="formhash" value="f0f0" />""", HttpStatusCode.OK)
            "/signup/captcha" -> respond(byteArrayOf(0x47), HttpStatusCode.OK)
            "/FollowTheRabbit" ->
                if ((request.body as FormDataContent).formData["captcha_challenge_field"] == "abcd") {
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.SetCookie, "chii_auth=AUTH; path=/"))
                } else {
                    respond("""<div class="message"><p class="text">验证码错误</p></div>""", HttpStatusCode.OK)
                }
            "/oauth/authorize" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "chogumi://oauth/callback?code=the_code"))
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `账号密码登录一次拿到令牌与网页会话`() = runTest {
        val handler = Handler().apply { expectedToken = "at" }
        val (repo, tokenStore) = buildRepo(handler, webSite)
        val attempt = (repo.passwordCaptcha(attempt = null) as Outcome.Success).value.attempt

        assertTrue(repo.loginWithPassword(attempt, "a@b.c", "pw", "abcd"), "登录应当成功")

        assertTrue(repo.isLoggedIn.value)
        assertEquals("at", tokenStore.accessToken())
        assertEquals(1, handler.tokenRequests.get())
        assertEquals(StoredWebSession("chii_auth=AUTH"), tokenStore.webSessionFor(tokenStore.session()))
    }

    @Test
    fun `账号密码登录时 Cookie 加不了密 登录照常成功只是没有网页会话`() = runTest {
        val handler = Handler().apply { expectedToken = "at" }
        val unsealable = object : CookieCipher {
            override fun seal(plain: String): String? = null
            override fun open(sealed: String): String? = null
        }
        val (repo, tokenStore) = buildRepo(handler, webSite, unsealable)
        val attempt = (repo.passwordCaptcha(attempt = null) as Outcome.Success).value.attempt

        assertTrue(repo.loginWithPassword(attempt, "a@b.c", "pw", "abcd"), "登录应当成功")

        assertTrue(repo.isLoggedIn.value)
        assertEquals("at", tokenStore.accessToken())
        assertNull(repo.loginError.value)
        assertNull(tokenStore.webSessionFor(tokenStore.session()))
    }

    @Test
    fun `账号密码登录验证码错时不登录_原因是验证码错`() = runTest {
        val handler = Handler()
        val (repo, tokenStore) = buildRepo(handler, webSite)
        val attempt = (repo.passwordCaptcha(attempt = null) as Outcome.Success).value.attempt

        assertFalse(repo.loginWithPassword(attempt, "a@b.c", "pw", "wrong"))

        assertFalse(repo.isLoggedIn.value)
        assertEquals(Failure.Rejected(RejectReason.LOGIN_CAPTCHA_WRONG), repo.loginError.value)
        assertEquals(0, handler.tokenRequests.get())
        assertNull(tokenStore.webSessionFor(tokenStore.session()))
    }

    @Test
    fun `账号密码登录失败后旧 OAuth 回调也已作废`() = runTest {
        val handler = Handler()
        val (repo, tokenStore) = buildRepo(handler, webSite)
        val staleState = repo.buildAuthorizeUrl().substringAfter("state=")
        val attempt = (repo.passwordCaptcha(attempt = null) as Outcome.Success).value.attempt

        assertFalse(repo.loginWithPassword(attempt, "a@b.c", "pw", "wrong"))
        assertEquals(Failure.Rejected(RejectReason.LOGIN_CAPTCHA_WRONG), repo.loginError.value)

        assertFalse(repo.onCallback(code = "stale-code", state = staleState))
        assertFalse(repo.isLoggedIn.value)
        assertEquals(Failure.Rejected(RejectReason.LOGIN_CALLBACK_MISMATCH), repo.loginError.value)
        assertEquals(0, handler.tokenRequests.get())
        assertNull(tokenStore.accessToken())
    }
}
