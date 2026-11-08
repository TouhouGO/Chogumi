package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 读屏下加标签的输入框是**一个**带「添加标签」的编辑框：占位字并在输入框自己的语义里，
 * 外面没有另一个可点的元素把它包起来成第二个焦点（否则 TalkBack 念「编辑框」，且要走两下焦点）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class TagFieldSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `加标签输入框是一个带标签的编辑框`() {
        composeRule.setContent {
            ChogumiTheme {
                TagsSection(tags = emptyList(), suggestions = emptyList(), input = TextFieldState(), onChange = {})
            }
        }
        composeRule.onNode(hasSetTextAction() and hasText("添加标签")).assertExists()
        composeRule.onAllNodes(hasClickAction() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true).assertCountEquals(0)
    }
}
