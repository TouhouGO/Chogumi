package io.github.touhougo.chogumi.feature.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 手动令牌框的默认遮蔽态，供窄屏、宽屏和深浅色设备矩阵查看。测试值显然是合成标记。 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class LoginTokenPrivacyScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 手动令牌默认遮蔽_深浅色各存一张() {
        var dark by mutableStateOf(false)
        compose.setContent {
            ChogumiTheme(darkTheme = dark) {
                ManualTokenDialog(
                    value = "SYNTHETIC_MANUAL_TOKEN_7c2e",
                    onValueChange = {},
                    onConfirm = {},
                    onDismiss = {},
                    enabled = true,
                )
            }
        }
        listOf(false, true).forEach { night ->
            compose.runOnIdle { dark = night }
            compose.waitForIdle()
            saveScreenshot(
                "login",
                "manual-token-${if (night) "dark" else "light"}",
                compose.onNode(isDialog()).captureToImage().asAndroidBitmap(),
            )
        }
    }
}
