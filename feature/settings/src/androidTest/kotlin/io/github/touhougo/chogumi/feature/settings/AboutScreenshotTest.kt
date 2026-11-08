package io.github.touhougo.chogumi.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 关于页随包素材与许可的浅深色截图（[saveScreenshot] 的 `about/`），不连接账号。 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class AboutScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private var dark by mutableStateOf(false)

    @Test
    fun 随包素材与许可原文各存浅深色截图() {
        compose.setContent {
            ChogumiTheme(darkTheme = dark) { PageSheetTestHost { AboutScreen(onBack = {}) } }
        }
        listOf(false, true).forEach { isDark ->
            compose.runOnIdle { dark = isDark }
            compose.onNodeWithTag(AboutListTag).performScrollToNode(hasText("Bangumi 表情"))
            compose.waitForIdle()
            saveScreenshot("about", if (isDark) "materials-dark" else "materials-light", compose.onRoot().captureToImage().asAndroidBitmap())
            compose.onNodeWithTag(AboutListTag).performScrollToNode(hasText("GPL-3.0"))
            compose.waitForIdle()
            saveScreenshot("about", if (isDark) "licenses-dark" else "licenses-light", compose.onRoot().captureToImage().asAndroidBitmap())
        }
    }
}
