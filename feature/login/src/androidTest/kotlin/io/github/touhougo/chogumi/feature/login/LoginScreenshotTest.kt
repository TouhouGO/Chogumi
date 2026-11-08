package io.github.touhougo.chogumi.feature.login

import android.util.Base64
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 登录页的截图（[saveScreenshot] 的 `login/`）：验证码还没到、验证码没取到、表单填好（有验证码图）、登录进行中、
 * 正在填密码（那一格鼓成整圆、换色）。
 * 不必登出测试账号就能看未登录界面。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class LoginScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private var processing by mutableStateOf(false)
    private var form by mutableStateOf(PasswordForm())
    private var captcha by mutableStateOf<Load<ByteArray>>(Load.Pending)

    @Test
    fun 未登录页各状态各存一张截图() {
        composeRule.setContent {
            ChogumiTheme {
                LoginContent(
                    processing = processing,
                    form = form,
                    onFormChange = { form = it },
                    captcha = captcha,
                    onCaptchaRefresh = {},
                    onLogin = {},
                    onBrowserLogin = {},
                    onManualToken = {},
                    onBack = {},
                    onSettingsClick = {},
                    snackbarHostState = remember { SnackbarHostState() },
                )
            }
        }
        val filled = PasswordForm(email = "fixture_user@example.com", password = "password", captcha = "abcd")
        listOf(
            "route" to { processing = false },
            "captcha-failed" to { captcha = Load.Failed(Failure.Offline) },
            "filled" to { form = filled; captcha = Load.Ready(sampleCaptcha()) },
            "processing" to { processing = true },
        ).forEach { (name, apply) ->
            composeRule.runOnIdle(apply)
            composeRule.waitForIdle()
            saveScreenshot("login", name, composeRule.onRoot().captureToImage().asAndroidBitmap())
        }
        composeRule.runOnIdle { processing = false }
        composeRule.onAllNodes(hasSetTextAction())[1].performClick()
        composeRule.waitForIdle()
        saveScreenshot("login", "focused", composeRule.onRoot().captureToImage().asAndroidBitmap())
    }

    /** 一张样例验证码图（96×32 的 PNG）：浅底上一行深色方块，看得出图在框里怎么放。 */
    private fun sampleCaptcha(): ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAGAAAAAgCAIAAABiouoDAAAATElEQVR42u3XIREAAAgEQaLRPw0RSAASwezpV+s+SmuBABAgQIAAAQIESIAugHLo6x4QIECAAAECBAgQIFfDFwMECBAgQIAECBCgqxqTQ4jDHXptLgAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )
}
