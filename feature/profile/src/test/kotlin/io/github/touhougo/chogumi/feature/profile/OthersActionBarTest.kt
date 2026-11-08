package io.github.touhougo.chogumi.feature.profile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 他人主页操作行的关注状态按钮只画读到的关系：好友名单还没读到时是占位，不先画一个可点的「加为好友」。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w448dp-h996dp")
class OthersActionBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun show(ops: SocialOpsState) {
        composeRule.setContent {
            ChogumiTheme {
                OthersActionBar(user = null, ops = ops, onAddFriend = {}, onUnfriendRequest = {}, onUnblock = {}, onMessage = {})
            }
        }
    }

    @Test
    fun `关系还没读到_不画加为好友也不画已是好友`() {
        show(SocialOpsState())
        composeRule.onAllNodesWithText("加为好友").assertCountEquals(0)
        composeRule.onAllNodesWithText("已是好友").assertCountEquals(0)
        composeRule.onNodeWithText("短消息").assertExists()
    }

    @Test
    fun `读到不是好友_画加为好友`() {
        show(SocialOpsState(isFriend = false))
        composeRule.onNodeWithText("加为好友").assertExists()
    }

    @Test
    fun `读到已是好友_画已是好友`() {
        show(SocialOpsState(isFriend = true))
        composeRule.onNodeWithText("已是好友").assertExists()
    }
}
