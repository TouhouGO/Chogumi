package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.model.appFeatures
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.LocalFriends
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 用户卡片里的关注状态按钮：状态读当前账号的好友名单，加好友与解除好友经页面的写操作通道发出；
 * 自己的卡片、没接通道的页面、专注模式都没有这一行。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class UserCardFriendButtonTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val performed = mutableListOf<ModerationAction>()

    @Test
    fun 不在好友名单里时是加为好友_点了经写操作通道加好友() {
        showCard(friends = Friends.NONE)
        composeRule.onNodeWithText(ALREADY_FRIEND).assertDoesNotExist()
        composeRule.onNodeWithText(ADD_FRIEND).performClick()
        composeRule.runOnIdle { assertEquals(listOf<ModerationAction>(ModerationAction.AddFriend("bob")), performed) }
    }

    @Test
    fun 在好友名单里时是已是好友_点了请求解除好友的确认() {
        showCard(friends = Friends(setOf(USER_ID)))
        composeRule.onNodeWithText(ADD_FRIEND).assertDoesNotExist()
        composeRule.onNodeWithText(ALREADY_FRIEND).performClick()
        composeRule.runOnIdle { assertEquals(listOf<ModerationAction>(ModerationAction.Unfriend("bob")), performed) }
    }

    @Test
    fun 自己的卡片没有关注状态按钮() {
        showCard(friends = Friends.NONE, isOwn = true)
        assertNoFriendButton()
    }

    @Test
    fun 页面没接写操作通道时没有关注状态按钮() {
        showCard(friends = Friends(setOf(USER_ID)), gated = false)
        assertNoFriendButton()
    }

    @Test
    fun 专注模式下没有关注状态按钮() {
        showCard(friends = Friends(setOf(USER_ID)), mode = ExperienceMode.FOCUS)
        assertNoFriendButton()
    }

    private fun assertNoFriendButton() {
        composeRule.onNodeWithText(ADD_FRIEND).assertDoesNotExist()
        composeRule.onNodeWithText(ALREADY_FRIEND).assertDoesNotExist()
    }

    private fun showCard(friends: Friends, isOwn: Boolean = false, gated: Boolean = true, mode: ExperienceMode = ExperienceMode.FULL) {
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(
                    LocalFriends provides friends,
                    LocalAppFeatures provides appFeatures(mode, showPublicRatings = true, showCoverScores = true, showFriendMarks = true),
                ) {
                    PageSheetTestHost {
                        val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                        UserCardSheet(
                            nickname = "Bob",
                            avatarUrl = null,
                            sign = "",
                            username = "bob",
                            userId = USER_ID,
                            isOwn = isOwn,
                            onDismiss = {},
                            onOpenProfile = null,
                            onModerate = if (gated) gate.guard { action: ModerationAction -> performed += action } else null,
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val USER_ID = 7
        const val ADD_FRIEND = "加为好友"
        const val ALREADY_FRIEND = "已是好友"
    }
}
