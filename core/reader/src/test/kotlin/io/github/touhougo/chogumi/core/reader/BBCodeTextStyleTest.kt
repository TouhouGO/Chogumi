package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** [BBCodeText] 的 `style` 一路带进嵌套的块：调用方给的阅读字号在引用块里同样生效。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class BBCodeTextStyleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `引用块里的正文用调用方给的字号`() {
        composeRule.setContent {
            ChogumiTheme { BBCodeText(BBCodeParser.parse("正文[quote]引用里的字[/quote]"), style = TextStyle(fontSize = 30.sp)) }
        }

        assertEquals(30.sp, fontSizeOf("正文"))
        assertEquals("引用块与外面的正文同一个字号", 30.sp, fontSizeOf("引用里的字"))
    }

    private fun fontSizeOf(text: String) = buildList<TextLayoutResult> {
        composeRule.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(this) }
    }.single().layoutInput.style.fontSize
}
