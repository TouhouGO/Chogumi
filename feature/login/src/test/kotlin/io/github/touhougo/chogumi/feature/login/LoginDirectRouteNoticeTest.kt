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
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    private fun repository(loginPage: () -> Nothing?) = testAuthRepository(
        resources,
        client = resources.apiClient { request -> error("不该请求接口：${request.url}") },
        tokenStore = testTokenStore(scope),
        authConfig = AuthConfig("cid", "secret", "chogumi://oauth/callback"),
        scope = scope,
        webClient = testWebClient { request ->
            when (request.url.encodedPath) {
                "/login" -> loginPage() ?: respond("", HttpStatusCode.InternalServerError)
                else -> error("不该打开网页：${request.url}")
            }
        },
    )

    private suspend fun open(hint: DirectRouteHint, loginPage: () -> Nothing?) {
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
        open(DirectRouteAvailable) { null }

        assertNull(viewModel.failures.current.value?.action)
    }
}
