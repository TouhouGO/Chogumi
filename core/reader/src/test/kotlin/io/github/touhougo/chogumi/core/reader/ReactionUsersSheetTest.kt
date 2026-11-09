package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.ReactionUserBo
import io.github.touhougo.chogumi.core.model.SignedInAccount
import io.github.touhougo.chogumi.core.ui.common.LocalSignedInAccount
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 谁贴了的面板：每个表情一个 chip（带人数），停在长按的那个表情上，下面是贴了它的人；我贴的那格是「我」、排最前；
 * 换 chip 换名单，点一个人去他的主页。楼层页的面板按楼 id 在这一页的楼层里找贴贴，楼层没回来时不画。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ReactionUsersSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val reactions = listOf(
        CommentReactionBo(79, listOf(ReactionUserBo(2, "u2", "甲"), ReactionUserBo(3, "u3", "")), selected = true),
        CommentReactionBo(54, listOf(ReactionUserBo(4, "u4", "丁")), selected = false),
    )

    private val opened = mutableListOf<String>()

    @Test
    fun 停在长按的表情上_每个表情一个chip带人数_我排最前() {
        showSheet(value = 79)

        composeRule.onNodeWithText("3").assertIsSelected()
        composeRule.onNodeWithText("1").assertExists()
        val me = person("我")
        val first = person("甲")
        assertTrue("「我」排在最前：我 $me，甲 $first", me.top < first.top || (me.top == first.top && me.left < first.left))
        // 没有昵称的人显示用户名
        composeRule.onNodeWithText("u3").assertExists()
        composeRule.onNodeWithText("丁").assertDoesNotExist()
    }

    @Test
    fun 换一个表情是另一份名单() {
        showSheet(value = 79)

        composeRule.onNodeWithText("1").performClick()

        composeRule.onNodeWithText("丁").assertExists()
        composeRule.onNodeWithText("甲").assertDoesNotExist()
        composeRule.onNodeWithText("我").assertDoesNotExist()
    }

    @Test
    fun 点一个人去他的主页_点我去自己的主页() {
        showSheet(value = 79)

        composeRule.onNodeWithText("甲").performClick()
        composeRule.onNodeWithText("我").performClick()

        composeRule.runOnIdle { assertEquals(listOf("u2", "me"), opened) }
    }

    @Test
    fun 停着的表情已经没人贴了就停在第一个() {
        showSheet(value = 140)

        composeRule.onNodeWithText("3").assertIsSelected()
        composeRule.onNodeWithText("甲").assertExists()
    }

    @Test
    fun 楼层页的面板按楼id找贴贴_楼层没回来时不画_回来后画出() {
        var threads by mutableStateOf(emptyList<CommentThreadBo>())
        val state = ReactionUsersState(7 to 54)
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    CommentReactionUsers(state, threads, onUserClick = { opened += it })
                }
            }
        }
        composeRule.onNodeWithText("丁").assertDoesNotExist()

        composeRule.runOnIdle { threads = listOf(CommentThreadBo(anchor = floor(id = 7), replies = emptyList())) }

        composeRule.onNodeWithText("丁").assertExists()
        composeRule.onNodeWithText("1").assertIsSelected()
    }

    private fun showSheet(value: Int) {
        composeRule.setContent {
            var shown by remember { mutableStateOf(value) }
            ChogumiTheme {
                CompositionLocalProvider(LocalSignedInAccount provides SignedInAccount(userId = 42, username = "me")) {
                    PageSheetTestHost {
                        ReactionUsersSheet(reactions, shown, onSelect = { shown = it }, onDismiss = {}, onUserClick = { opened += it })
                    }
                }
            }
        }
    }

    private fun person(name: String) = composeRule.onNode(hasText(name) and hasClickAction()).fetchSemanticsNode().boundsInRoot

    private fun floor(id: Int) = CommentItemBo(
        id = id,
        mainId = 1,
        relatedId = 0,
        creatorId = 2,
        creatorNickname = "甲",
        creatorUsername = "u2",
        creatorAvatar = null,
        content = "正文",
        createdAt = 0L,
        state = CommentState.NORMAL,
        reactions = reactions,
    )
}
