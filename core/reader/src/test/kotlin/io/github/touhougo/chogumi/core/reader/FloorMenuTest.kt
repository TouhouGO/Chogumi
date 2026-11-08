package io.github.touhougo.chogumi.core.reader

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.model.ReportTarget
import io.github.touhougo.chogumi.core.model.ReportType
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.reactionsOf
import io.github.touhougo.chogumi.core.ui.common.LocalFriends
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 楼层长按菜单：贴贴两行全显，下面两组——这层楼的内容（复制、网页、报告），对人（别人的楼：加为好友或解除好友、绝交；自己的楼：编辑、删除）。
 * 评论区没有的能力不出（作品吐槽没有网页楼层地址、没有报告类型）；绝交与解除好友交给页面（页面先弹确认）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w448dp-h900dp")
class FloorMenuTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val topic = CommentSurface.Topic(TopicType.GROUP, 7)
    private val actions = mutableListOf<ModerationAction>()

    @Test
    fun 别人的楼_内容一组加对人一组_报告的是这层楼() {
        showFloor(topic, item(isOwn = false))
        longPress()
        listOf("复制", "网页", "报告", "加为好友", "绝交").forEach { composeRule.onNodeWithText(it).assertExists(it) }
        listOf("编辑", "删除").forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
        composeRule.onAllNodes(isSelectable()).assertCountEquals(COMMON_REACTIONS.size)
        val content = listOf("复制", "网页", "报告").map(::menuBounds)
        val person = listOf("加为好友", "绝交").map(::menuBounds)
        val groupGap = person.first().top - content.last().bottom
        val rowGaps = content.zipWithNext { before, after -> after.top - before.bottom } +
            person.zipWithNext { before, after -> after.top - before.bottom }
        assertTrue("内容组与对人组之间的空隙大于组内行距", rowGaps.all { groupGap > it })

        composeRule.onNodeWithText("报告").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(ModerationAction.Report(ReportTarget(ReportType.GROUP_REPLY, 100), "甲")), actions)
        }
        composeRule.onNodeWithText("复制").assertDoesNotExist()
    }

    @Test
    fun 自己的楼_对人一组换成编辑删除_没有报告() {
        showFloor(topic, item(isOwn = true))
        longPress()
        listOf("复制", "网页", "编辑", "删除").forEach { composeRule.onNodeWithText(it).assertExists(it) }
        listOf("报告", "加为好友", "绝交").forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test
    fun 作品吐槽_没有网页与报告_贴贴8个排两行() {
        val surface = CommentSurface.SubjectComments(1)
        showFloor(surface, item(isOwn = false))
        longPress()
        listOf("复制", "加为好友", "绝交").forEach { composeRule.onNodeWithText(it).assertExists(it) }
        listOf("网页", "报告").forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
        val cells = composeRule.onAllNodes(isSelectable()).fetchSemanticsNodes()
        assertEquals(reactionsOf(surface).size, cells.size)
        assertEquals("贴贴应排两行", 2, cells.map { it.positionOnScreen.y }.distinct().size)
    }

    @Test
    fun 贴贴12个排两行_一行6格() {
        showFloor(topic, item(isOwn = false))
        longPress()
        val rows = composeRule.onAllNodes(isSelectable()).fetchSemanticsNodes().groupBy { it.positionOnScreen.y }
        assertEquals(listOf(6, 6), rows.values.map { it.size })
    }

    @Test
    fun 别人的楼在好友名单里_对人一组是解除好友_点了交给页面先确认() {
        showFloor(topic, item(isOwn = false), friends = Friends(setOf(2)))
        longPress()
        composeRule.onNodeWithText("加为好友").assertDoesNotExist()
        composeRule.onNodeWithText("解除好友").performClick()
        composeRule.runOnIdle { assertEquals(listOf(ModerationAction.Unfriend("u2")), actions) }
        composeRule.onNodeWithText("解除好友").assertDoesNotExist()
    }

    @Test
    fun 绝交交给页面_带上确认框里的称呼() {
        showFloor(topic, item(isOwn = false))
        longPress()
        composeRule.onNodeWithText("绝交").performClick()
        composeRule.runOnIdle { assertEquals(listOf(ModerationAction.Block("u2", "甲")), actions) }
        composeRule.onNodeWithText("绝交").assertDoesNotExist()
    }

    @Test
    fun 复制的是不带引用的正文() {
        showFloor(topic, item(isOwn = false, content = "[quote][b]乙[/b] 说: 原话[/quote]\n[b]回[/b]嘴"))
        longPress()
        composeRule.onNodeWithText("复制").performClick()
        composeRule.runOnIdle {
            val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            assertEquals("回嘴", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    /** [friends] 是当前账号的好友名单（默认谁也没关注）：菜单里加为好友 / 解除好友按它二选一。 */
    private fun showFloor(surface: CommentSurface, comment: CommentItemBo, friends: Friends = Friends.NONE) {
        composeRule.setContent {
            ChogumiTheme {
                val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
                CompositionLocalProvider(LocalFriends provides friends) {
                    Box(Modifier.width(448.dp).testTag("floor")) {
                        CommentThreadItem(
                            thread = CommentThreadBo(anchor = comment, replies = emptyList()),
                            floorNumber = "#2",
                            onAvatarClick = {},
                            surface = surface,
                            onReact = gate.guard { _: Int, _: Int? -> },
                            onEdit = gate.guard { _: EditTarget -> },
                            onDelete = gate.guard { _: Int -> },
                            onModerate = gate.guard { action: ModerationAction -> actions += action },
                        )
                    }
                }
            }
        }
    }

    private fun longPress() {
        composeRule.onNodeWithTag("floor").performTouchInput { longClick(center) }
        composeRule.waitForIdle()
    }

    private fun menuBounds(label: String) = composeRule.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot

    private fun item(isOwn: Boolean, content: String = "正文") = CommentItemBo(
        id = 100,
        mainId = 7,
        relatedId = 0,
        creatorId = 2,
        creatorNickname = "甲",
        creatorUsername = "u2",
        creatorAvatar = null,
        content = content,
        createdAt = 0L,
        state = CommentState.NORMAL,
        isOwn = isOwn,
    )
}
