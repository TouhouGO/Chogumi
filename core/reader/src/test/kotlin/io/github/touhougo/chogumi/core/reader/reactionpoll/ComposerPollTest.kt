package io.github.touhougo.chogumi.core.reader.reactionpoll

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.reader.ComposerSheet
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 编辑器里的投票：能贴贴的地方「插入」里有「投票」，加上的投票卡写好每个选项才能发，发出去的是正文后面接上投票块；
 * 打开时正文里的投票块读回成投票卡，原样发出去还是原来的正文。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h900dp")
class ComposerPollTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sent = mutableListOf<ComposerDraft>()
    private val changes = mutableListOf<ComposerDraft>()

    private fun setComposer(palette: List<Int>, initialText: String = "", restoration: StateRestorationTester? = null) {
        val setContent: (@Composable () -> Unit) -> Unit = restoration?.let { it::setContent } ?: composeRule::setContent
        setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                    ComposerSheet(
                        title = "回复楼主",
                        sending = false,
                        sent = false,
                        failures = FailureNotices(),
                        onDismiss = {},
                        onSend = gate.guard { draft: ComposerDraft, _: String -> sent += draft },
                        initialText = initialText,
                        onChange = { changes += it },
                        verify = false,
                        pollPalette = palette,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun field(label: String) = composeRule.onNode(hasText(label) and hasSetTextAction())

    private fun insertPoll() {
        composeRule.onNodeWithContentDescription("插入").performClick()
        composeRule.onNodeWithContentDescription("投票").performClick()
    }

    @Test
    fun 插入投票后写好每个选项才能发发出去的是正文接上投票块() {
        setComposer(COMMON_REACTIONS)
        composeRule.onNode(hasSetTextAction()).performTextInput("想做个统计。")
        insertPoll()

        composeRule.onNodeWithText("发送").assertIsNotEnabled()
        field("选项 1").performTextInput("男")
        field("选项 2").performTextInput("女")
        composeRule.onNodeWithText("发送").assertIsEnabled().performClick()

        val expected = ReactionPollDraft("", listOf(ReactionPollDraft.Option(COMMON_REACTIONS[0], "男"), ReactionPollDraft.Option(COMMON_REACTIONS[1], "女")))
        assertEquals(listOf(joinReactionPoll("想做个统计。", expected)), sent.map { it.text })
        assertEquals("草稿里存的也是带投票块的正文", sent.single().text, changes.last().text)
    }

    @Test
    fun 打开时正文里的投票读回成投票卡原样发出去不变() {
        val draft = ReactionPollDraft("开局选哪个？", listOf(ReactionPollDraft.Option(54, "男"), ReactionPollDraft.Option(140, "女")))
        val source = joinReactionPoll("想做个统计。", draft)
        setComposer(COMMON_REACTIONS, initialText = source)

        composeRule.onNode(hasText("想做个统计。") and hasSetTextAction()).assertExists()
        composeRule.onNode(hasText("男") and hasSetTextAction()).assertExists()
        composeRule.onNodeWithText("发送").performClick()

        assertEquals(listOf(source), sent.map { it.text })
    }

    @Test
    fun 移除投票后发出去的只有正文() {
        setComposer(COMMON_REACTIONS)
        composeRule.onNode(hasSetTextAction()).performTextInput("只有正文")
        insertPoll()

        composeRule.onNodeWithContentDescription("移除投票").performClick()
        composeRule.onNodeWithText("发送").performClick()

        assertEquals(listOf("只有正文"), sent.map { it.text })
    }

    @Test
    fun 投票卡随面板保存重建后还在() {
        val restoration = StateRestorationTester(composeRule)
        setComposer(COMMON_REACTIONS, restoration = restoration)
        insertPoll()
        field("选项 1").performTextInput("男")

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNode(hasText("男") and hasSetTextAction()).assertExists()
        field("选项 2").assertExists()
    }

    @Test
    fun 不能贴贴的地方插入里没有投票() {
        setComposer(emptyList())

        composeRule.onNodeWithContentDescription("插入").performClick()

        composeRule.onNodeWithContentDescription("链接").assertExists()
        composeRule.onNodeWithContentDescription("投票").assertDoesNotExist()
    }
}
