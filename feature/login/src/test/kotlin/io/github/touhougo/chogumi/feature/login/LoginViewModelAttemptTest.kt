package io.github.touhougo.chogumi.feature.login

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.res.R
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 登录页的状态全 App 一份、一直在：登上了就丢掉这次登录与填过的字（密码不留到下次），登出后备好新的一次登录；
 * 一次提交在途时再点不发第二次。
 */
class LoginViewModelAttemptTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val main = TestMain()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var viewModel: LoginViewModel

    /** 网站登录页被打开的次数：每次就是开始一次登录。 */
    private val loginPages = AtomicInteger()

    /** `/v0/me`（核对令牌）被请求的次数：每次就是提交一次令牌登录。 */
    private val meRequests = AtomicInteger()

    /** 非 null 时 `/v0/me` 停到放行为止，让第一次提交确定地还在途。 */
    private var meGate: CompletableDeferred<Unit>? = null

    private var meStatus = HttpStatusCode.OK

    private val repository = testAuthRepository(
        resources,
        client = resources.apiClient { request ->
            when (request.url.encodedPath) {
                "/v0/me" -> {
                    meRequests.incrementAndGet()
                    meGate?.await()
                    respond(ME_JSON, meStatus, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                }
                else -> error("不该请求接口：${request.url}")
            }
        },
        tokenStore = testTokenStore(scope),
        authConfig = AuthConfig("cid", "secret", "chogumi://oauth/callback"),
        scope = scope,
        webClient = testWebClient { request ->
            when (request.url.encodedPath) {
                "/login" -> {
                    loginPages.incrementAndGet()
                    respond(LOGIN_PAGE, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "chii_sid=s1; path=/"))
                }
                "/signup/captcha" -> respond(byteArrayOf(0x47, 0x49, 0x46, 0x38), HttpStatusCode.OK)
                else -> error("不该打开网页：${request.url}")
            }
        },
    )

    @BeforeTest
    fun setUp() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(listOf(viewModel.viewModelScope, scope))
    }

    @Test
    fun `登上后丢掉这次登录与填过的字_登出后备好新的一次`() = runBlocking {
        viewModel = withContext(main.dispatcher) { LoginViewModel(repository) }
        awaitUntil("第一张验证码到了") { viewModel.uiState.value.captcha is Load.Ready }
        withContext(main.dispatcher) { viewModel.onFormChange(PasswordForm(email = "a@b.c", password = "pw", captcha = "abcd")) }

        assertTrue(repository.loginWithToken("token"), "令牌登录应当成功")
        awaitUntil("登上后表单清空") { viewModel.uiState.value.form == PasswordForm() }
        assertEquals(Load.Pending, viewModel.uiState.value.captcha, "登上后不留这次登录的验证码")
        assertEquals(1, loginPages.get(), "登着的时候不开始新的登录")

        repository.logout()
        awaitUntil("登出后新的一次登录备好了") { loginPages.get() == 2 && viewModel.uiState.value.captcha is Load.Ready }
    }

    @Test
    fun `令牌登录在途时再点提交_不发第二次`() = runBlocking {
        viewModel = withContext(main.dispatcher) { LoginViewModel(repository) }
        val gate = CompletableDeferred<Unit>().also { meGate = it }
        val viewModelJob = viewModel.viewModelScope.coroutineContext[Job]!!
        withContext(main.dispatcher) {
            viewModel.onManualTokenChange("token")
            val before = viewModelJob.children.toSet()
            // 同一帧里点两下：仓库回流的 processing 还没到。提交在主线程上同步起协程，这里当场就数得清起了几次
            viewModel.onManualTokenSubmit()
            viewModel.onManualTokenSubmit()
            assertEquals(1, viewModelJob.children.count { it !in before }, "同一帧里点两下只起一次提交")
        }
        awaitUntil("第一次提交到了服务端") { meRequests.get() == 1 }
        withContext(main.dispatcher) {
            viewModel.onManualTokenChange(REPLACEMENT_TOKEN)
            val before = viewModelJob.children.toSet()
            viewModel.onManualTokenSubmit()
            assertEquals(REPLACEMENT_TOKEN, viewModel.uiState.value.manualToken, "在途拒绝不能清掉用户新输入")
            assertEquals(0, viewModelJob.children.count { it !in before }, "提交在途时再点不起第二次")
        }

        gate.complete(Unit)
        awaitUntil("登上了") { repository.isLoggedIn.value }
        assertEquals(1, meRequests.get(), "只核对过一次令牌")
    }

    @Test
    fun `令牌提交时立即取走并清空输入_成功前也不保留`() = runBlocking {
        viewModel = withContext(main.dispatcher) { LoginViewModel(repository) }
        val gate = CompletableDeferred<Unit>().also { meGate = it }
        withContext(main.dispatcher) {
            viewModel.onManualTokenChange(SYNTHETIC_TOKEN)
            viewModel.onManualTokenSubmit()
            assertEquals("", viewModel.uiState.value.manualToken, "提交那一刻就清空输入")
        }
        awaitUntil("合成令牌到达核对接口") { meRequests.get() == 1 }

        gate.complete(Unit)
        awaitUntil("令牌登录成功") { repository.isLoggedIn.value }
    }

    @Test
    fun `令牌登录失败后输入仍为空`() = runBlocking {
        meStatus = HttpStatusCode.Unauthorized
        viewModel = withContext(main.dispatcher) { LoginViewModel(repository) }
        withContext(main.dispatcher) {
            viewModel.onManualTokenChange(SYNTHETIC_TOKEN)
            viewModel.onManualTokenSubmit()
            assertEquals("", viewModel.uiState.value.manualToken, "失败结果回来前就清空输入")
        }
        awaitUntil("令牌登录失败") { repository.loginError.value != null }

        assertEquals("", viewModel.uiState.value.manualToken, "失败也不把令牌留在表单里")
    }

    @Test
    fun `空白令牌仍按现有契约提示且不发请求`() = runBlocking {
        viewModel = withContext(main.dispatcher) { LoginViewModel(repository) }

        listOf("", "   ").forEach { raw ->
            withContext(main.dispatcher) {
                viewModel.onManualTokenChange(raw)
                viewModel.onManualTokenSubmit()
            }

            assertEquals(NoticeText.Resource(R.string.login_token_required), viewModel.failures.current.value?.text)
            assertEquals("", viewModel.uiState.value.manualToken)
        }
        assertEquals(0, meRequests.get(), "空令牌不提交")
    }

    private companion object {
        const val SYNTHETIC_TOKEN = "SYNTHETIC_MANUAL_TOKEN_7c2e"
        const val REPLACEMENT_TOKEN = "SYNTHETIC_REPLACEMENT_TOKEN_9b4f"

        val LOGIN_PAGE = """<form id="loginForm" method="post" action="/FollowTheRabbit"><input type="hidden" name="formhash" value="f0f0" /></form>"""

        val ME_JSON = """{"id":42,"username":"fixture_user","nickname":"Fixture User","user_group":10,"avatar":{"large":"","medium":"","small":""},"sign":""}"""
    }
}
