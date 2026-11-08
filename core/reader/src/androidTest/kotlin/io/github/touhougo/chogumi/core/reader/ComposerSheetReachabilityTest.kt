package io.github.touhougo.chogumi.core.reader

import android.content.Context
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.QuoteHeader
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 狭窄页面与键盘压缩后的可用高度：引用、正文与工具栏都必须能滚动抵达。窗口尺寸是测试边界，不是设计值。 */
@OptIn(ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
class ComposerSheetReachabilityTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun 放大字号的回复工具栏在受限高度下仍可达() = checkToolbar(fontScale = 2f, titleField = ComposerTitle.NONE)

    @Test
    fun 发帖的正文工具栏在受限高度下仍可达() = checkToolbar(fontScale = 1.1f, titleField = ComposerTitle.REQUIRED)

    private fun checkToolbar(fontScale: Float, titleField: ComposerTitle) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.size(380.dp, 440.dp).consumeWindowInsets(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                        PageSheetTestHost {
                            val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clear = context.getString(R.string.composer_clear)
        composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(hasContentDescription(clear)),
        ).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10000f) }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1000)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(clear).assertIsDisplayed()
    }
}
