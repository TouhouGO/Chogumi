package io.github.touhougo.chogumi.feature.login

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testAuthRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 登录页两个凭据输入共用同一套默认遮蔽、显隐按钮和读屏标签。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class LoginSecretFieldsTest {
    @get:Rule
    val resources = TestResources()

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val loginPages = AtomicInteger()
    private val meRequests = AtomicInteger()
    private lateinit var viewModel: LoginViewModel
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val repository = testAuthRepository(
        resources,
        client = resources.apiClient { request ->
            meRequests.incrementAndGet()
            error("UI 隐私测试不访问账号接口：${request.url}")
        },
        tokenStore = testTokenStore(scope),
        authConfig = AuthConfig("cid", "secret", "chogumi://oauth/callback"),
        scope = scope,
        webClient = testWebClient { request ->
            when (request.url.encodedPath) {
                "/login" -> {
                    loginPages.incrementAndGet()
                    respond(LOGIN_PAGE, HttpStatusCode.OK)
                }
                "/signup/captcha" -> respond(CAPTCHA_IMAGE, HttpStatusCode.OK)
                else -> error("UI 隐私测试不打开其他网页：${request.url}")
            }
        },
    )

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) cancelAndAwait(listOf(viewModel.viewModelScope, scope))
    }

    @Test
    fun `手动令牌显隐保留焦点和光标并继续输入`() {
        var token by mutableStateOf("")
        compose.setContent {
            ChogumiTheme {
                ManualTokenDialog(
                    value = token,
                    onValueChange = { token = it },
                    onConfirm = {},
                    onDismiss = {},
                    enabled = true,
                )
            }
        }
        compose.onNodeWithText(text(R.string.manual_token_hint)).performTextInput(SYNTHETIC_TOKEN)
        assertMasked(R.string.manual_token_hint, SYNTHETIC_TOKEN)
        field(R.string.manual_token_hint).performTextInputSelection(TextRange(5))
        field(R.string.manual_token_hint).assertIsFocused()

        compose.onNodeWithContentDescription(text(R.string.manual_token_show)).performClick()
        assertShown(R.string.manual_token_hint, SYNTHETIC_TOKEN)
        assertFocusedSelection(R.string.manual_token_hint, 5)
        field(R.string.manual_token_hint).performTextInput("X")
        val edited = SYNTHETIC_TOKEN.take(5) + "X" + SYNTHETIC_TOKEN.drop(5)
        assertShown(R.string.manual_token_hint, edited)
        compose.onNodeWithContentDescription(text(R.string.manual_token_hide)).performClick()
        assertMasked(R.string.manual_token_hint, edited)
        assertFocusedSelection(R.string.manual_token_hint, 6)
    }

    @Test
    fun `普通密码框显隐保留焦点和光标并继续输入`() {
        compose.setContent {
            val login = remember { LoginViewModel(repository).also { viewModel = it } }
            ChogumiTheme {
                LoginScreen(login, onBack = {}, onSettingsClick = {}, modifier = Modifier)
            }
        }

        compose.onNodeWithText(text(R.string.login_password)).performTextInput(SYNTHETIC_PASSWORD)
        assertMasked(R.string.login_password, SYNTHETIC_PASSWORD)
        field(R.string.login_password).performTextInputSelection(TextRange(5))
        field(R.string.login_password).assertIsFocused()
        compose.onNodeWithContentDescription(text(R.string.login_password_show)).performClick()
        assertShown(R.string.login_password, SYNTHETIC_PASSWORD)
        assertFocusedSelection(R.string.login_password, 5)
        field(R.string.login_password).performTextInput("X")
        val edited = SYNTHETIC_PASSWORD.take(5) + "X" + SYNTHETIC_PASSWORD.drop(5)
        assertShown(R.string.login_password, edited)
        compose.onNodeWithContentDescription(text(R.string.login_password_hide)).performClick()
        assertMasked(R.string.login_password, edited)
        assertFocusedSelection(R.string.login_password, 6)
        assertEquals(
            "普通密码继续按 Next",
            ImeAction.Next,
            field(R.string.login_password).fetchSemanticsNode().config[SemanticsProperties.ImeAction],
        )
        assertEquals("切换遮蔽不重复开始登录", 1, loginPages.get())
    }

    @Test
    fun `真实登录页取消后清空并恢复默认遮蔽`() {
        compose.setContent {
            val login = remember { LoginViewModel(repository).also { viewModel = it } }
            ChogumiTheme {
                LoginScreen(login, onBack = {}, onSettingsClick = {}, modifier = Modifier)
            }
        }

        compose.onNode(hasText(text(R.string.manual_token_action)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
        dialogField().performTextInput(SYNTHETIC_TOKEN)
        assertEquals(SYNTHETIC_TOKEN, viewModel.uiState.value.manualToken)
        // 取消前先显示明文：重开时回到遮蔽才说明显隐状态没有活过这次取消
        compose.onNodeWithContentDescription(text(R.string.manual_token_show)).performClick()
        assertEquals(SYNTHETIC_TOKEN, visibleText(dialogField()))
        compose.onNode(hasText(text(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals("真实取消接线清空 ViewModel", "", viewModel.uiState.value.manualToken)

        compose.onNode(hasText(text(R.string.manual_token_action)) and hasClickAction()).performScrollTo().performClick()
        val reopened = dialogField()
        assertEquals("重开输入为空", "", visibleText(reopened))
        assertTrue("重开恢复默认遮蔽", hasPasswordSemantics(reopened))
        assertEquals("取消不能提交 Token", 0, meRequests.get())
    }

    private fun text(id: Int): String = context.getString(id)

    private fun assertMasked(label: Int, plaintext: String) {
        assertTrue("${text(label)} 默认应带 Password 语义", hasPasswordSemantics(label))
        val visible = visibleText(label)
        assertEquals(plaintext.length, visible.length)
        assertTrue("${text(label)} 应显示遮蔽字符，实际为 $visible", visible.all { it == '\u2022' })
    }

    private fun assertShown(label: Int, plaintext: String) {
        assertEquals(plaintext, visibleText(label))
    }

    private fun assertFocusedSelection(label: Int, cursor: Int) {
        val field = field(label)
        field.assertIsFocused()
        assertEquals(TextRange(cursor), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    private fun hasPasswordSemantics(label: Int): Boolean = hasPasswordSemantics(field(label))

    private fun hasPasswordSemantics(field: SemanticsNodeInteraction): Boolean =
        field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Password) != null

    private fun visibleText(label: Int): String = visibleText(field(label))

    private fun visibleText(field: SemanticsNodeInteraction): String {
        val results = mutableListOf<TextLayoutResult>()
        val action = field.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        check(action?.invoke(results) == true && results.size == 1) { "字段没有唯一的可见文本布局" }
        return results.single().layoutInput.text.text
    }

    private fun field(label: Int) = compose.onNodeWithText(text(label))

    private fun dialogField() = compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))

    private companion object {
        const val SYNTHETIC_TOKEN = "SYNTHETIC_MANUAL_TOKEN_7c2e"
        const val SYNTHETIC_PASSWORD = "SYNTHETIC_PASSWORD_4a1d"
        const val LOGIN_PAGE = """<form id="loginForm" method="post" action="/FollowTheRabbit"><input type="hidden" name="formhash" value="f0f0" /></form>"""
        val CAPTCHA_IMAGE = byteArrayOf(0x47, 0x49, 0x46, 0x38)
    }
}
