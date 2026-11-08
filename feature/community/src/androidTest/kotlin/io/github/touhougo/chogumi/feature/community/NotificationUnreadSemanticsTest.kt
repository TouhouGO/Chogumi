package io.github.touhougo.chogumi.feature.community

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.composables.LocalNow
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The visible notification and message rows enter screen-reader state through their shared row frame. The ordinary
 * read rows and the two skeleton siblings must stay silent about unread state, while every row keeps its own content.
 * The same live notification and message rows also move from unread to read through recomposition.
 */
@RunWith(AndroidJUnit4::class)
class NotificationUnreadSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `中文未读通知与短信行朗读未读_普通行和骨架不误报`() {
        assertRows(language = "zh", unread = "未读")
    }

    @Test
    fun `英文未读通知与短信行朗读未读_普通行和骨架不误报`() {
        assertRows(language = "en", unread = "Unread")
    }

    private fun assertRows(language: String, unread: String) {
        var noticeClicks = 0
        var messageClicks = 0
        var notice by mutableStateOf(NotificationSyntheticFixtures.notice(unread = true))
        var conversation by mutableStateOf(
            NotificationSyntheticFixtures.conversation(NotificationSyntheticFixtures.UNREAD_COUNT),
        )
        compose.setContent {
            Localized(language) {
                Row(
                    tag = NOTIFICATION_DYNAMIC,
                    content = {
                        NotificationItemRow(
                            item = notice,
                            onUser = {},
                            onOpen = { noticeClicks++ },
                        )
                    },
                )
                Row(
                    tag = NOTIFICATION_READ,
                    content = {
                        NotificationItemRow(
                            item = NotificationSyntheticFixtures.notice(unread = false),
                            onUser = {},
                            onOpen = {},
                        )
                    },
                )
                Row(
                    tag = CONVERSATION_DYNAMIC,
                    content = {
                        ConversationRow(
                            conversation = conversation,
                            onClick = { messageClicks++ },
                        )
                    },
                )
                Row(
                    tag = CONVERSATION_READ,
                    content = {
                        ConversationRow(
                            conversation = NotificationSyntheticFixtures.conversation(unread = 0),
                            onClick = {},
                        )
                    },
                )
                Row(NOTIFICATION_SKELETON, content = { NotificationItemSkeleton() })
                Row(CONVERSATION_SKELETON, content = { ConversationRowSkeleton() })
            }
        }

        assertUnreadState(NOTIFICATION_DYNAMIC, unread, NotificationSyntheticFixtures.SENDER_NAME)
        assertUnreadState(CONVERSATION_DYNAMIC, unread, NotificationSyntheticFixtures.CONVERSATION_NAME)
        listOf(NOTIFICATION_READ, CONVERSATION_READ, NOTIFICATION_SKELETON, CONVERSATION_SKELETON).forEach { tag ->
            compose.onNode(stateIn(tag)).assertDoesNotExist()
        }

        assertRowBodies()
        compose.onNodeWithText(NotificationSyntheticFixtures.UNREAD_COUNT.toString(), useUnmergedTree = true).assertExists()

        compose.onNode(unreadRow(NOTIFICATION_DYNAMIC, unread)).performClick()
        compose.onNode(unreadRow(CONVERSATION_DYNAMIC, unread)).performClick()
        compose.runOnIdle {
            assertTrue("the notification row keeps its own click", noticeClicks == 1)
            assertTrue("the message row keeps its own click", messageClicks == 1)
            notice = notice.copy(unread = false)
            conversation = conversation.copy(unread = 0)
        }
        compose.waitForIdle()

        listOf(NOTIFICATION_DYNAMIC, CONVERSATION_DYNAMIC).forEach { tag ->
            compose.onNode(stateIn(tag)).assertDoesNotExist()
        }
        assertRowBodies()
        compose.onNode(clickableRow(NOTIFICATION_DYNAMIC, NotificationSyntheticFixtures.SENDER_NAME)).performClick()
        compose.onNode(clickableRow(CONVERSATION_DYNAMIC, NotificationSyntheticFixtures.CONVERSATION_NAME)).performClick()
        compose.runOnIdle {
            assertTrue("the notification row keeps its click after becoming read", noticeClicks == 2)
            assertTrue("the message row keeps its click after becoming read", messageClicks == 2)
        }
    }

    private fun assertRowBodies() {
        compose.onAllNodesWithText(NotificationSyntheticFixtures.SENDER_NAME, useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText(NotificationSyntheticFixtures.NOTICE_TITLE, useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText(NotificationSyntheticFixtures.CONVERSATION_NAME, useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText(NotificationSyntheticFixtures.CONVERSATION_PREVIEW, useUnmergedTree = true).assertCountEquals(2)
    }

    private fun assertUnreadState(tag: String, unread: String, body: String) {
        compose.onNode(unreadRow(tag, unread))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
            .assert(hasText(body, substring = true))
    }

    private fun stateIn(tag: String): SemanticsMatcher =
        hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)

    private fun unreadRow(tag: String, unread: String): SemanticsMatcher =
        clickableRow(tag, body = null) and hasStateDescription(unread)

    private fun clickableRow(tag: String, body: String?): SemanticsMatcher {
        val underTag = hasAnyAncestor(hasTestTag(tag)) and hasClickAction()
        return if (body == null) underTag else underTag and hasText(body, substring = true)
    }

    @Composable
    private fun Localized(language: String, content: @Composable () -> Unit) {
        val context = LocalContext.current
        val configuration = remember(language, context) {
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        }
        val localized = remember(configuration, context) { context.createConfigurationContext(configuration) }
        val now = remember { mutableLongStateOf(NotificationSyntheticFixtures.now) }
        CompositionLocalProvider(
            LocalContext provides localized,
            LocalConfiguration provides localized.resources.configuration,
            LocalNow provides now,
        ) {
            ChogumiTheme { Column { content() } }
        }
    }

    @Composable
    private fun Row(tag: String, content: @Composable () -> Unit) {
        androidx.compose.foundation.layout.Box(Modifier.testTag(tag)) { content() }
    }

    private companion object {
        const val NOTIFICATION_DYNAMIC = "notification-dynamic"
        const val NOTIFICATION_READ = "notification-read"
        const val CONVERSATION_DYNAMIC = "conversation-dynamic"
        const val CONVERSATION_READ = "conversation-read"
        const val NOTIFICATION_SKELETON = "notification-skeleton"
        const val CONVERSATION_SKELETON = "conversation-skeleton"
    }
}
