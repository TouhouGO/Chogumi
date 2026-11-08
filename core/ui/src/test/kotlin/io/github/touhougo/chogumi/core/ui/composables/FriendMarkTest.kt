package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.LocalFriends
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 「好友」小牌只跟当前账号关注的人走，名单一变当场跟上；好友标识关掉时哪里都不画；人物格里小牌压在头像底边，不挤名字。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FriendMarkTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `关注的人画好友小牌_没关注的人与未知用户不画`() {
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalFriends provides Friends(setOf(FRIEND))) {
                    FriendMark(STRANGER)
                    FriendMark(null)
                    FriendMark(FRIEND)
                }
            }
        }

        composeRule.onNodeWithText(MARK).assertExists()
        assertEquals(1, composeRule.onAllNodesWithText(MARK).fetchSemanticsNodes().size)
    }

    @Test
    fun `加好友与取消好友后小牌当场出现与撤下`() {
        var friends by mutableStateOf(Friends.NONE)
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalFriends provides friends) { FriendMark(FRIEND) }
            }
        }
        composeRule.onNodeWithText(MARK).assertDoesNotExist()

        friends = Friends(setOf(FRIEND))
        composeRule.onNodeWithText(MARK).assertExists()

        friends = Friends.NONE
        composeRule.onNodeWithText(MARK).assertDoesNotExist()
    }

    @Test
    fun `好友标识关掉后名字旁与人物格都不画_再打开当场回来`() {
        var features by mutableStateOf(FEATURES.copy(friendMarks = false))
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalAppFeatures provides features, LocalFriends provides Friends(setOf(FRIEND))) {
                    FriendMark(FRIEND)
                    PersonGridItem(title = NAME, imageUrl = null, onClick = null, modifier = Modifier.width(120.dp), userId = FRIEND)
                }
            }
        }
        composeRule.onNodeWithText(MARK).assertDoesNotExist()

        features = FEATURES
        assertEquals(2, composeRule.onAllNodesWithText(MARK).fetchSemanticsNodes().size)
    }

    @Test
    fun `人物格里好友小牌压在头像底边正中_不占名字的宽度`() {
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalFriends provides Friends(setOf(FRIEND))) {
                    PersonGridItem(title = NAME, imageUrl = null, onClick = null, modifier = Modifier.width(120.dp), userId = FRIEND)
                }
            }
        }

        val avatar = composeRule.onNodeWithTag(PersonAvatarTag).getBoundsInRoot()
        val mark = composeRule.onNodeWithText(MARK).getBoundsInRoot()
        assertEquals(avatar.bottom.value, mark.bottom.value, 0.5f)
        assertEquals(((avatar.left + avatar.right) / 2).value, ((mark.left + mark.right) / 2).value, 0.5f)
    }

    private companion object {
        const val FRIEND = 7
        const val STRANGER = 8
        const val MARK = "好友"
        const val NAME = "冬马和纱"
        val FEATURES = AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true)
    }
}
