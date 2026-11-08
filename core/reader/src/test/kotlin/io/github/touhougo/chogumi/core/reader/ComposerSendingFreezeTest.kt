package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 发送途中编辑器停住：发出去的是按下那一刻的内容，发成功后面板连同草稿一起收走，这期间改的字没有地方留，
 * 所以标题、正文、投票卡与工具栏在发送期间都不接受改动；发送结束（失败时面板留着）又都能改。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h900dp")
class ComposerSendingFreezeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var sending by mutableStateOf(false)

    // 停住的输入框没有 SetText 动作，按「可编辑文字」找
    private fun field(label: String) = composeRule.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))

    private fun inputs(): List<SemanticsNodeInteraction> = listOf(
        field("帖子标题"),
        field("正文"),
        field("问题（可不写）"),
        field("选项 1"),
        composeRule.onNodeWithContentDescription("移除投票"),
        composeRule.onNodeWithContentDescription("选项 1 的表情"),
        composeRule.onNodeWithContentDescription("表情"),
        composeRule.onNodeWithContentDescription("插入"),
        composeRule.onNodeWithContentDescription("预览"),
        composeRule.onNodeWithContentDescription("清空"),
    )

    @Test
    fun 发送期间标题正文投票卡与工具栏都不接受改动_发送结束后恢复() {
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                    ComposerSheet(
                        title = "发帖",
                        sending = sending,
                        sent = false,
                        failures = FailureNotices(),
                        onDismiss = {},
                        onSend = gate.guard { _: ComposerDraft, _: String -> },
                        initialTitle = "帖子标题",
                        initialText = "正文",
                        onChange = {},
                        verify = false,
                        titleField = ComposerTitle.REQUIRED,
                        pollPalette = COMMON_REACTIONS,
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("插入").performClick()
        composeRule.onNodeWithContentDescription("投票").performClick()
        field("选项 1").performTextInput("男")
        inputs().forEach { it.assertIsEnabled() }

        composeRule.runOnIdle { sending = true }
        inputs().forEach { it.assertIsNotEnabled() }

        composeRule.runOnIdle { sending = false }
        inputs().forEach { it.assertIsEnabled() }
    }
}
