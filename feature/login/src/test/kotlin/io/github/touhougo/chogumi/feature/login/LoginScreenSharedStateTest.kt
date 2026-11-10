package io.github.touhougo.chogumi.feature.login

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 压在不同 tab 栈里的登录页是同一个登录页：从一处换到另一处，还是那张验证码，
 * 不会再开始一次网站登录、再要一张。每个导航页有自己的 ViewModelStore，这里用两个各自的存储冒充两个 tab 里的登录页。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class LoginScreenSharedStateTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    @get:Rule
    val compose = createComposeRule()

    /** 网站登录页被打开的次数（每次就是开始一次登录）与要过的验证码张数。 */
    private val loginPages = AtomicInteger()
    private val captchas = AtomicInteger()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 一个导航页的 ViewModel 存储（同 rememberViewModelStoreNavEntryDecorator 给每页的那份）。 */
    private class EntryStore : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private val entries = List(2) { EntryStore() }

    private val refreshLabel = ApplicationProvider.getApplicationContext<Context>().getString(R.string.login_captcha_refresh)

    private val repository = testAuthRepository(
        resources,
        client = resources.apiClient { request -> error("不该请求接口：${request.url}") },
        tokenStore = testTokenStore(scope),
        authConfig = AuthConfig("cid", "secret", "chogumi://oauth/callback"),
        scope = scope,
        webClient = testWebClient { request ->
            when (request.url.encodedPath) {
                "/login" -> {
                    loginPages.incrementAndGet()
                    respond(LOGIN_PAGE, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "chii_sid=s1; path=/"))
                }
                "/signup/captcha" -> {
                    captchas.incrementAndGet()
                    respond(CAPTCHA_IMAGE, HttpStatusCode.OK)
                }
                else -> error("不该打开网页：${request.url}")
            }
        },
    )

    @After
    fun tearDown() {
        entries.forEach { it.viewModelStore.clear() }
        cancelAndAwait(listOf(scope))
    }

    /** 屏上有一张能点了换的验证码（图到了才能点；到了就一直在）。 */
    private fun captchaShown(): Boolean = compose.onAllNodes(
        SemanticsMatcher("能换的验证码") { it.config.getOrNull(SemanticsActions.OnClick)?.label == refreshLabel },
    ).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun 换到另一处登录页_还是那张验证码_不再开始一次登录() {
        var shown by mutableIntStateOf(0)
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalViewModelStoreOwner provides entries[shown]) {
                    key(shown) {
                        // 同 LoginScreen 取 ViewModel 的那一行，只是不经 Koin（单测里的 startKoin 会被编译插件当整图校验）
                        val viewModel = viewModel(viewModelStoreOwner = loginViewModelOwner()) { LoginViewModel(repository, NoDirectRoute) }
                        LoginScreen(viewModel, onBack = {}, onNetworkClick = {}, onSettingsClick = {}, modifier = Modifier)
                    }
                }
            }
        }
        compose.waitUntil(WAIT_MS) { captchaShown() }

        compose.runOnIdle { shown = 1 }
        compose.waitForIdle()
        // 另一处一出现验证码就看：各自一份状态时，它是自己又开了一次登录、要来第二张之后才出现的
        compose.waitUntil(WAIT_MS) { captchaShown() }

        assertEquals("开始登录的次数", 1, loginPages.get())
        assertEquals("要验证码的次数", 1, captchas.get())
    }

    private companion object {
        const val WAIT_MS = 10_000L

        val LOGIN_PAGE = """<form id="loginForm" method="post" action="/FollowTheRabbit"><input type="hidden" name="formhash" value="f0f0" /></form>"""

        val CAPTCHA_IMAGE = byteArrayOf(0x47, 0x49, 0x46, 0x38)
    }
}
