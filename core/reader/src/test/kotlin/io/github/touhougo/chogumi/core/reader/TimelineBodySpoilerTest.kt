package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 时间线正文里的剧透走 BBCode 的行内遮罩：标签不当字面显示。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class TimelineBodySpoilerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `剧透标签不当字面显示_正文与遮住的字同在一行`() {
        composeRule.setContent { ChogumiTheme { TimelineBody("结局是[mask]主角死了[/mask]呢") } }

        composeRule.onAllNodes(hasText("[mask", substring = true)).assertCountEquals(0)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("结局是主角死了呢")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals("前后正文与遮罩在同一行", 1, layout.lineCount)
        val hidden = layout.layoutInput.text.spanStyles.filter { it.start == 3 && it.end == 7 }
        assertTrue("遮罩内每个字都被同色文字与背景遮住", hidden.any {
            it.item.background != Color.Unspecified && it.item.background.alpha > 0f && it.item.color == it.item.background
        })
    }
}
