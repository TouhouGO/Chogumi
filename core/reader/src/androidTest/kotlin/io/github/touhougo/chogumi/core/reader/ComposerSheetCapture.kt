package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.common.bbcode.QuoteHeader
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 编辑器正常高度下的回复与发帖画面，截图窗口为人工验收边界（not in M3）。 */
@VisualCapture
@OptIn(ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
class ComposerSheetCapture {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun 回复编辑器画面() = capture(fontScale = 1f, titleField = ComposerTitle.NONE, name = "reply")

    @Test
    fun 发帖编辑器画面() = capture(fontScale = 1.1f, titleField = ComposerTitle.REQUIRED, name = "topic")

    private fun capture(fontScale: Float, titleField: ComposerTitle, name: String) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme(darkTheme = false) {
                    Box(Modifier.size(380.dp, 800.dp).consumeWindowInsets(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                        PageSheetTestHost {
                            val gate = rememberLoginGate(true, {})
                            ComposerSheet(
                                title = "回复 #6 测试作者",
                                sending = false,
                                sent = false,
                                failures = FailureNotices(),
                                onDismiss = {},
                                onSend = gate.guard { _: ComposerDraft, _: String -> },
                                quote = QuoteHeader("测试作者", "这是需要保留在编辑器里的引用摘要。"),
                                titleField = titleField,
                                onChange = {},
                                verify = false,
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val prefix = InstrumentationRegistry.getArguments().getString("captureName", "composer")
        saveScreenshot("composer", "$prefix-$name", composeRule.onRoot().captureToImage().asAndroidBitmap())
    }
}
