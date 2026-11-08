package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 屏蔽名单的真实界面消费方：id-only 行与普通用户名行都必须把同一个用户键交给导航。 */
@RunWith(AndroidJUnit4::class)
class BlockedUsersPageTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `id-only行按数字uid打开主页_普通用户名保持不变`() {
        var opened: String? = null
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                Surface {
                    BlockedUsersPage(
                        users = Load.Ready(BLOCKED_USERS),
                        unblocking = emptySet(),
                        onBack = {},
                        onRetry = {},
                        onUserClick = { opened = it },
                        onUnblock = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("#99").assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals("99", opened) }

        composeRule.runOnIdle { opened = null }
        composeRule.onNodeWithText("Carol").assertHasClickAction().performClick()
        composeRule.runOnIdle { assertEquals("carol", opened) }
    }

    private companion object {
        val BLOCKED_USERS = listOf(
            UserBo(id = 99, username = "", nickname = ""),
            UserBo(id = 7, username = "carol", nickname = "Carol"),
        )
    }
}

/** 同一 synthetic 名单在约定三档宽度、字体比例与深浅主题下的截图。 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class BlockedUsersScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var width by mutableStateOf(448.dp)
    private var fontScale by mutableStateOf(1f)
    private var dark by mutableStateOf(false)

    @Test
    fun `id-only行在三档宽度与深浅主题下截图`() {
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        composeRule.setContent {
            val density = minOf(metrics.widthPixels / width.value, metrics.heightPixels / VIEWPORT_HEIGHT.value)
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                ChogumiTheme(darkTheme = dark) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        Surface(Modifier.testTag(CAPTURE_TAG).requiredSize(width, VIEWPORT_HEIGHT)) {
                            BlockedUsersPage(
                                users = Load.Ready(BLOCKED_USERS),
                                unblocking = emptySet(),
                                onBack = {},
                                onRetry = {},
                                onUserClick = {},
                                onUnblock = {},
                            )
                        }
                    }
                }
            }
        }

        CONFIGS.forEach { config ->
            composeRule.runOnIdle {
                width = config.width
                fontScale = config.fontScale
                dark = config.dark
            }
            composeRule.waitForIdle()
            val bitmap = composeRule.onNodeWithTag(CAPTURE_TAG).captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, "01-01-blocked-users-${config.name}", bitmap)
        }
    }

    private data class CaptureConfig(val name: String, val width: Dp, val fontScale: Float, val dark: Boolean)

    private companion object {
        const val CAPTURE_TAG = "blocked-users-capture"
        const val FOLDER = "blocked-users"
        val VIEWPORT_HEIGHT = 800.dp
        val BLOCKED_USERS = listOf(
            UserBo(id = 99, username = "", nickname = ""),
            UserBo(id = 7, username = "carol", nickname = "Carol"),
        )
        val CONFIGS = listOf(
            CaptureConfig("448-light", 448.dp, 1f, false),
            CaptureConfig("448-dark", 448.dp, 1f, true),
            CaptureConfig("380-font1.1-light", 380.dp, 1.1f, false),
            CaptureConfig("380-font1.1-dark", 380.dp, 1.1f, true),
            CaptureConfig("800-light", 800.dp, 1f, false),
            CaptureConfig("800-dark", 800.dp, 1f, true),
        )
    }
}
