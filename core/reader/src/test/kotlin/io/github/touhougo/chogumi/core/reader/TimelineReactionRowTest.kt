package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineReactionTarget
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 动态详情面板的贴贴一行与楼层同一套：已有的每一种贴贴都画出来、点了就贴上它 / 取消，「添加贴贴」弹出选择器；
 * 不能贴的动态只读。不画只认一个表情的「贴贴 N」按钮。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TimelineReactionRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val picked = mutableListOf<Int?>()

    private val addLabel get() = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.floor_react_add)

    private fun show(item: TimelineItemBo) {
        composeRule.setContent {
            val gate = rememberLoginGate(isLoggedIn = true, onLogin = {})
            TimelineReactionRow(item = item, reactions = item.reactions, onReact = gate.guard { _: TimelineItemBo, value: Int? -> picked += value }, onReply = {})
        }
    }

    @Test
    fun 已有的每种贴贴都画出来且点了贴上它() {
        show(item(CommentReactionBo(value = 79, count = 7), CommentReactionBo(value = 104, count = 1)))

        composeRule.onNodeWithText("1").assertExists()
        composeRule.onNodeWithText("7").performClick()
        composeRule.runOnIdle { assertEquals(listOf<Int?>(79), picked) }
    }

    @Test
    fun 点自己贴着的那个是取消() {
        show(item(CommentReactionBo(value = 79, count = 8, selected = true)))

        composeRule.onNodeWithText("8").performClick()
        composeRule.runOnIdle { assertEquals(listOf<Int?>(null), picked) }
    }

    @Test
    fun 添加贴贴弹出选择器点表情贴上它() {
        show(item())

        composeRule.onNodeWithContentDescription(addLabel).performClick()
        // 54 是选择器里的「吐血」（bgm38）
        composeRule.onNodeWithContentDescription("bgm38").performClick()
        composeRule.runOnIdle { assertEquals(listOf<Int?>(54), picked) }
    }

    @Test
    fun 不能贴的动态只读列出已有的贴贴() {
        show(item(CommentReactionBo(value = 104, count = 1), target = null))

        composeRule.onNodeWithContentDescription(addLabel).assertDoesNotExist()
        composeRule.onNodeWithText("1").performClick()
        composeRule.runOnIdle { assertEquals(emptyList<Int?>(), picked) }
    }

    private fun item(vararg reactions: CommentReactionBo, target: TimelineReactionTarget? = TimelineReactionTarget.Status) = TimelineItemBo(
        id = 42,
        uid = 9,
        user = null,
        cat = 5,
        type = 1,
        createdAt = 0,
        text = "",
        reactions = reactions.toList(),
        reactionTarget = target,
    )
}
