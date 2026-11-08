package io.github.touhougo.chogumi.feature.messages

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.PmConversationSummary
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 写信页选收件人：点最近联系人进那段会话并弹出回复面板，点好友写给该用户，输入用户名可以直接写给它。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h900dp")
class PmRecipientsPageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val picked = mutableListOf<PmChatKey>()

    private fun setPage() {
        val state = PmRecipientsUiState(
            recent = Load.Ready(
                listOf(PmConversationSummary(300003, UserBo(id = 400100, username = "", nickname = "anonymous"), date = null, preview = "你好", unread = 0)),
            ),
            friends = Load.Ready(listOf(UserBo(id = 1, username = "alice", nickname = "爱丽丝"))),
        )
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    PmRecipientsPage(state = state, onBack = {}, onRetryRecent = {}, onRetryFriends = {}, onPick = { picked += it })
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun 点最近联系人进那段会话并弹出回复面板_点好友写给该用户() {
        setPage()

        composeRule.onNodeWithText("anonymous").performClick()
        composeRule.onNodeWithText("爱丽丝").performClick()

        assertEquals(listOf(PmChatKey.Conversation(300003, compose = true), PmChatKey.Peer("alice")), picked)
    }

    @Test
    fun 输入的用户名不是哪位好友_多一行直接写给它() {
        setPage()

        composeRule.onNode(hasSetTextAction()).performTextInput("someone")
        composeRule.onNodeWithText("写给 @someone").performClick()

        assertEquals(listOf<PmChatKey>(PmChatKey.Peer("someone")), picked)
        composeRule.onNodeWithText("爱丽丝").assertDoesNotExist()
    }
}
