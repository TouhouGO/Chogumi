package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [SheetTitleRow]：很长的标题在句尾省略，右侧的确认动作仍然完整地在行内。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetTitleRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `长标题单行省略且右侧动作完整，行高不低于 48dp`() {
        val title = "举报这位名字特别特别特别特别特别长的用户"
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 2f)) {
                    SheetTitleRow(
                        title = title,
                        modifier = Modifier.testTag(ROW),
                    ) { Text("提交", modifier = Modifier.testTag(ACTION)) }
                }
            }
        }
        val row = composeRule.onNodeWithTag(ROW).fetchSemanticsNode().boundsInRoot
        val action = composeRule.onNodeWithTag(ACTION).fetchSemanticsNode().boundsInRoot
        assertTrue("动作应完整在行内：行 $row，动作 $action", row.left <= action.left && action.right <= row.right)
        assertTrue("行高不低于 48dp（2x 密度即 96px）：$row", row.height >= 96f)
        fun layoutOf(text: String): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        val titleLayout = layoutOf(title)
        assertEquals("标题只占一行", 1, titleLayout.lineCount)
        assertTrue("放不下的标题应在句尾省略", titleLayout.isLineEllipsized(0))
        val actionLayout = layoutOf("提交")
        assertFalse("右侧动作不能省略", actionLayout.isLineEllipsized(0))
        assertFalse("右侧动作不能被限高", actionLayout.didOverflowHeight)
    }

    private companion object {
        const val ROW = "row"
        const val ACTION = "action"
    }
}
