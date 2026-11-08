package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 编辑器的标题框：发帖必填（不填不能发），短信可选（只写正文就能发，填了随内容一起交出去）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h900dp")
class ComposerTitleFieldTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sent = mutableListOf<ComposerDraft>()

    private fun setComposer(titleField: ComposerTitle) {
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                    ComposerSheet(
                        title = "回复 测试",
                        sending = false,
                        sent = false,
                        failures = FailureNotices(),
                        onDismiss = {},
                        onSend = gate.guard { draft: ComposerDraft, _: String -> sent += draft },
                        onChange = {},
                        verify = false,
                        titleField = titleField,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun field(placeholder: String) = composeRule.onNode(hasText(placeholder) and hasSetTextAction())

    @Test
    fun 可选的主题不填也能发_交出去的标题是空的() {
        setComposer(ComposerTitle.OPTIONAL)
        field("说点什么…").performTextInput("只有正文")

        composeRule.onNodeWithText("发送").assertIsEnabled().performClick()

        assertEquals(listOf(ComposerDraft(title = "", text = "只有正文")), sent)
    }

    @Test
    fun 可选的主题填了就随正文交出去() {
        setComposer(ComposerTitle.OPTIONAL)
        field("主题（可选）").performTextInput("  新主题 ")
        field("说点什么…").performTextInput("正文")

        composeRule.onNodeWithText("发送").performClick()

        assertEquals(listOf(ComposerDraft(title = "新主题", text = "正文")), sent)
    }

    @Test
    fun 必填的标题没写不能发() {
        setComposer(ComposerTitle.REQUIRED)
        field("正文").performTextInput("只有正文")

        composeRule.onNodeWithText("发送").assertIsNotEnabled()
        field("标题").performTextInput("标题")
        composeRule.onNodeWithText("发送").assertIsEnabled()
    }
}
