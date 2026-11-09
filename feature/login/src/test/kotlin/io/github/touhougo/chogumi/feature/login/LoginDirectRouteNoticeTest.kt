package io.github.touhougo.chogumi.feature.login

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.netroute.DirectRouteHint
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.res.R
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 连不上 bgm.tv（验证码那一步就断了）时，登录页的提示条是「连不上 bgm.tv」+「试试直连」；
 * 已经在直连或设备不支持时不出现，服务端回了错误（连得上）时也不出现。
 */
class LoginDirectRouteNoticeTest {

    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()

    private val main = TestMain()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var viewModel: LoginViewModel

    /** 网站的登录页与验证码图：给 null 就是正常的那一份。 */
    private fun repository(
        loginPage: MockRequestHandleScope.() -> HttpResponseData?,
        captcha: MockRequestHandleScope.() -> HttpResponseData? = { null },
    ) = testAuthRepository(
        resources,
        client = resources.apiClient { request -> error("不该请求接口：${request.url}") },
        tokenStore = testTokenStore(scope),
        authConfig = AuthConfig("cid", "secret", "chogumi://oauth/callback"),
        scope = scope,
        webClient = testWebClient { request ->
            when (request.url.encodedPath) {
                "/login" -> loginPage() ?: respond(LOGIN_PAGE, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "chii_sid=s1; path=/"))
                "/signup/captcha" -> captcha() ?: respond(byteArrayOf(0x47, 0x49, 0x46, 0x38), HttpStatusCode.OK)
                else -> error("不该打开网页：${request.url}")
            }
        },
    )

    private suspend fun open(hint: DirectRouteHint, loginPage: MockRequestHandleScope.() -> HttpResponseData?) {
        val repository = repository(loginPage)
        viewModel = withContext(main.dispatcher) { LoginViewModel(repository, hint) }
        // 提示在失败落到页面状态之前发出：看到失败时该有的提示已经在了
        awaitUntil("验证码取不到") { viewModel.uiState.value.captcha is Load.Failed }
    }

    @BeforeTest
    fun setUp() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(listOf(viewModel.viewModelScope, scope))
    }

    @Test
    fun `连不上 bgm 点 tv 时提示试试直连`() = runBlocking {
        open(DirectRouteAvailable) { throw IOException("connection reset") }

        val notice = viewModel.failures.current.value
        assertEquals(TryDirectRoute, notice?.action)
        assertEquals(NoticeText.Resource(R.string.login_unreachable), notice?.text)
    }

    @Test
    fun `已在直连或不支持时不提示`() = runBlocking {
        open(NoDirectRoute) { throw IOException("connection reset") }

        assertNull(viewModel.failures.current.value)
    }

    @Test
    fun `连得上但服务端出错时不提示`() = runBlocking {
        open(DirectRouteAvailable) { respond("", HttpStatusCode.InternalServerError) }

        assertNull(viewModel.failures.current.value?.action)
    }

    /** 先取到一张验证码，再换一张：第二张按 [second] 失败。等到失败提示出来、主线程上这一轮跑完。 */
    private suspend fun refreshFails(second: MockRequestHandleScope.() -> HttpResponseData) {
        val captchas = AtomicInteger()
        val repository = repository(loginPage = { null }, captcha = { if (captchas.incrementAndGet() > 1) second() else null })
        viewModel = withContext(main.dispatcher) { LoginViewModel(repository, DirectRouteAvailable) }
        awaitUntil("第一张验证码到了") { viewModel.uiState.value.captcha is Load.Ready }
        withContext(main.dispatcher) { viewModel.onCaptchaRefresh() }
        awaitUntil("换图失败的提示") { viewModel.failures.current.value != null }
        // 提示与区块落定在主线程的同一拍里，中间不挂起：排空主线程之后不会再有一条盖上来
        withContext(main.dispatcher) { }
    }

    @Test
    fun `已有验证码时换图连不上_提示仍是试试直连`() = runBlocking {
        refreshFails { throw IOException("connection reset") }

        assertEquals(TryDirectRoute, viewModel.failures.current.value?.action)
    }

    @Test
    fun `已有验证码时换图遇服务端出错_照常提示并可重试`() = runBlocking {
        refreshFails { respond("", HttpStatusCode.InternalServerError) }

        val notice = viewModel.failures.current.value
        assertNull(notice?.action)
        assertTrue(notice?.retry != null, "旧图还在框里时要带重试")
    }

    private companion object {
        const val LOGIN_PAGE = """<form id="loginForm" method="post" action="/FollowTheRabbit"><input type="hidden" name="formhash" value="f0f0" /></form>"""
    }
}
