package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 搜索入口与输入框同一个容器；窄屏、2× 字号下提示文字仍只占一行，容器高度不变。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContainedSearchEntryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var clicks = 0

    @Test
    fun `300dp宽2倍字号下提示文字只占一行且容器仍是56dp`() {
        setEntry()
        val layout = mutableListOf<TextLayoutResult>()
        composeRule.onNode(hasText("搜索", substring = true), useUnmergedTree = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
        assertEquals(1, layout.single().lineCount)
        composeRule.onNodeWithTag(ENTRY).assertHeightIsEqualTo(56.dp)
    }

    @Test
    fun `点一下触发 onClick`() {
        setEntry()
        composeRule.onNodeWithTag(ENTRY).performClick()
        assertEquals(1, clicks)
    }

    private fun setEntry() {
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                        ContainedSearchEntry(
                            placeholder = "搜索动画、书籍、游戏…",
                            onClick = { clicks++ },
                            modifier = Modifier.testTag(ENTRY),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val ENTRY = "entry"
    }
}
