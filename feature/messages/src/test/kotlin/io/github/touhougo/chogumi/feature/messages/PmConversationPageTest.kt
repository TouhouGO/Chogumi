package io.github.touhougo.chogumi.feature.messages

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.PmConversation
import io.github.touhougo.chogumi.core.model.PmMessage
import io.github.touhougo.chogumi.core.model.PmThread
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.reader.ComposerState
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 会话页的写操作从界面到请求：长按一封 → 删除 → 确认，交出去的是那一封；点主题标签回复那条线，FAB 回复最新那条。
 * 写给一个人而没有会话时：一句空态，FAB 写第一封，编辑器的标题是「写给 某某」。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h900dp")
class PmConversationPageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val conversation = PmConversation(
        id = 300003,
        peer = UserBo(id = 400100, username = "400100", nickname = "anonymous"),
        threads = listOf(PmThread(500003, "新主题"), PmThread(500001, "第一个主题")),
        messages = listOf(
            PmMessage(500001, 500001, mine = true, body = "第一封", sentAt = 1_000L),
            PmMessage(500002, 500001, mine = false, body = "对方的回信", sentAt = 2_000L),
            PmMessage(500003, 500003, mine = true, body = "新主题的第一封", sentAt = 3_000L),
        ),
        replyThreadId = 500003,
    )
    private val erased = mutableListOf<PmMessage>()
    private val replies = mutableListOf<Int?>()

    private fun setPage(
        state: PmConversationUiState = PmConversationUiState(chat = Load.Ready(PmChat(conversation.peer, conversation))),
        onUserClick: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    PmConversationPage(
                        state = state,
                        failures = FailureNotices(),
                        composerFailures = FailureNotices(),
                        gate = rememberLoginGate(isLoggedIn = true, onLogin = {}),
                        onBack = {},
                        onUserClick = onUserClick,
                        onRetry = {},
                        onReply = { replies += it },
                        onErase = { erased += it },
                        onSend = { _, _ -> },
                        onCloseComposer = {},
                        onComposerChange = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun 长按一封再删除并确认_交给删除的就是那一封() {
        setPage()

        composeRule.onNodeWithText("对方的回信").performTouchInput { longClick() }
        composeRule.onNodeWithText("删除").performClick()
        composeRule.onNodeWithText("删除这条短信？").assertExists()
        composeRule.onNodeWithText("删除").performClick()

        assertEquals(listOf(conversation.messages[1]), erased)
    }

    @Test
    fun 点主题标签回复那条线_FAB回复最新那条() {
        setPage()

        composeRule.onNodeWithText("第一个主题").performClick()
        composeRule.onNodeWithContentDescription("回复").performClick()

        assertEquals(listOf(500001, null), replies)
    }

    @Test
    fun 没有会话时是一句空态_FAB写第一封() {
        setPage(PmConversationUiState(chat = Load.Ready(PmChat(conversation.peer, conversation = null))))

        composeRule.onNodeWithText("没有找到之前的短信").assertExists()
        composeRule.onNodeWithContentDescription("回复").performClick()
        assertEquals(listOf<Int?>(null), replies)
    }

    @Test
    fun 写第一封的编辑器标题是写给对方() {
        setPage(
            PmConversationUiState(
                chat = Load.Ready(PmChat(conversation.peer, conversation = null)),
                composer = ComposerState(target = PmReplyTarget(receiverId = 400100, threadId = null, quote = null)),
            ),
        )

        composeRule.onNodeWithText("写给 anonymous").assertExists()
    }

    @Test
    fun 对方没有用户名时点会话标题按uid打开主页() {
        val unnamed = conversation.copy(peer = conversation.peer.copy(username = ""))
        val opened = mutableListOf<String>()
        setPage(
            state = PmConversationUiState(chat = Load.Ready(PmChat(unnamed.peer, unnamed))),
            onUserClick = { opened += it },
        )

        composeRule.onNodeWithText("anonymous").performClick()

        assertEquals(listOf("400100"), opened)
    }
}
