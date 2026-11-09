package io.github.touhougo.chogumi.feature.login

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 登录页固定一屏（屏幕尺寸由各用例的 [Config] 限定符给出）：矮屏、窄屏、大字号下，表单、登录按钮和两个备选入口都在屏内，页面没有可滚的距离；
 * 海报往下缩时应用名不钻进顶栏，也不压到面板上；屏幕够高时海报还是设计高度，多出的高度留在面板里。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginLayoutFitTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** 在 [Config] 给的屏幕尺寸上以 [fontScale] 字号画登录页，返回各部件的位置（dp）。 */
    private fun layoutAt(fontScale: Float): Parts {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.fillMaxSize().testTag(SCREEN)) {
                        LoginContent(
                            processing = false,
                            form = PasswordForm(),
                            onFormChange = {},
                            captcha = Load.Pending,
                            onCaptchaRefresh = {},
                            onLogin = {},
                            onBrowserLogin = {},
                            onManualToken = {},
                            onBack = {},
                            onNetworkClick = {},
                            onSettingsClick = {},
                            snackbarHostState = remember { SnackbarHostState() },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val scrollRanges = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.maxValue?.invoke() }
        return Parts(
            screen = boundsOf(compose.onNodeWithTag(SCREEN).fetchSemanticsNode().boundsInRoot),
            wordmark = boundsOf(compose.onNodeWithText(context.getString(R.string.app_name)).fetchSemanticsNode().boundsInRoot),
            email = boundsOf(compose.onNodeWithText(context.getString(R.string.login_email)).fetchSemanticsNode().boundsInRoot),
            login = boundsOf(compose.onNodeWithText(context.getString(R.string.login_bangumi)).fetchSemanticsNode().boundsInRoot),
            browser = boundsOf(compose.onNodeWithText(context.getString(R.string.login_browser_entry)).fetchSemanticsNode().boundsInRoot),
            token = boundsOf(compose.onNodeWithText(context.getString(R.string.manual_token_action)).fetchSemanticsNode().boundsInRoot),
            maxScroll = scrollRanges.maxOrNull() ?: 0f,
        )
    }

    private fun boundsOf(px: Rect): Rect = Rect(px.left / density, px.top / density, px.right / density, px.bottom / density)

    private val density: Float get() = compose.density.density

    private fun assertFits(fontScale: Float) {
        val parts = layoutAt(fontScale)
        val where = "${parts.screen.width}×${parts.screen.height}dp、${fontScale}× 字号：$parts"
        assertEquals("页面不该有可滚的距离（$where）", 0f, parts.maxScroll, 0.5f)
        listOf(parts.login, parts.browser, parts.token).forEach {
            assertTrue("按钮跑出了屏幕底边（$where）", it.bottom <= parts.screen.bottom + TOLERANCE)
        }
        assertTrue("应用名钻进了顶栏（$where）", parts.wordmark.top >= TOP_BAR - TOLERANCE)
        assertTrue("应用名压到了面板上（$where）", parts.wordmark.bottom <= parts.email.top - PANEL_TOP + TOLERANCE)
    }

    @Test
    @Config(qualifiers = "+w380dp-h640dp")
    fun 窄矮屏大字号_所有控件都在一屏内() = assertFits(fontScale = 1.1f)

    @Test
    @Config(qualifiers = "+w360dp-h600dp")
    fun 更矮的屏_海报缩到底也放得下() = assertFits(fontScale = 1.1f)

    @Test
    @Config(qualifiers = "+w800dp-h1280dp")
    fun 宽屏_所有控件都在一屏内() = assertFits(fontScale = 1f)

    @Test
    @Config(qualifiers = "+w448dp-h996dp")
    fun 高屏_海报是设计高度_备选入口沉在底部() {
        val parts = layoutAt(fontScale = 1f)
        assertEquals("海报的高度（面板顶边）：$parts", HERO, parts.email.top - PANEL_TOP, TOLERANCE)
        assertEquals("备选入口离底边：$parts", PANEL_BOTTOM, parts.screen.bottom - parts.token.bottom, ALTERNATIVE_INSET)
        assertEquals("页面不该有可滚的距离：$parts", 0f, parts.maxScroll, 0.5f)
    }

    private data class Parts(
        val screen: Rect,
        val wordmark: Rect,
        val email: Rect,
        val login: Rect,
        val browser: Rect,
        val token: Rect,
        val maxScroll: Float,
    )

    private companion object {
        const val SCREEN = "screen"
        const val TOLERANCE = 0.5f

        /** 顶栏高（M3 small top app bar 64dp；测试里没有状态栏）、面板上下边距、海报设计高度：同 LoginScreen.kt。 */
        const val TOP_BAR = 64f
        const val PANEL_TOP = 28f
        const val PANEL_BOTTOM = 24f
        const val HERO = 300f

        /** 描边按钮的可点区比画出来的容器高：节点下沿比容器下沿低最多 8dp（M3 最小触控 48dp 对 40dp 容器）。 */
        const val ALTERNATIVE_INSET = 8f
    }
}
