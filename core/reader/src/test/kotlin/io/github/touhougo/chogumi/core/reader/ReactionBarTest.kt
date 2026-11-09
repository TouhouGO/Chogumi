package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.reactionOf
import io.github.touhougo.chogumi.core.model.withMyReaction
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 贴贴一排与选择器（楼层、帖子正文、动态详情共用的那一套）：
 * - 选择器挂在「添加贴贴」上：按钮靠近屏幕底部、下方放不下时，菜单贴着按钮上沿，而不是被甩到窗口顶上；
 * - 「添加贴贴」只在自己还没贴时出：贴上（自己挑的或给别人的 +1）就收起，取消后再出现，出现的途中不被裁掉。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class ReactionBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val addLabel get() = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.floor_react_add)

    @Test
    fun 按钮靠近屏幕底部时选择器贴着按钮上沿弹出() {
        composeRule.setContent {
            ChogumiTheme {
                val picker = rememberReactionPickerState()
                // 锚点是一整块高楼层，「添加贴贴」在它底部、离屏幕底边不到菜单的高度
                Box(Modifier.fillMaxSize().reactionPickerAnchor(picker)) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        ReactionBar(reactions = emptyList(), palette = COMMON_REACTIONS, onReact = {}, onShowUsers = {}, onAdd = picker::openOn)
                        Spacer(Modifier.height(16.dp))
                    }
                    ReactionPicker(picker, COMMON_REACTIONS, emptyList(), onPick = {})
                }
            }
        }

        composeRule.onNodeWithContentDescription(addLabel).performClick()
        val chip = composeRule.onNodeWithContentDescription(addLabel).fetchSemanticsNode().screenBounds()
        // 54 是选择器第一行的「吐血」（bgm38），90 是最后一行
        val firstRow = composeRule.onNodeWithContentDescription("bgm38").fetchSemanticsNode().screenBounds()
        val lastRow = composeRule.onNodeWithContentDescription(reactionCode(COMMON_REACTIONS.last())).fetchSemanticsNode().screenBounds()
        val gap = chip.top - lastRow.bottom
        assertTrue("菜单应在按钮之上：按钮 $chip，菜单首行 $firstRow，末行 $lastRow", lastRow.bottom <= chip.top + 0.5f)
        // 间距只有菜单自己的上下留白（8dp 量级），远小于一行格子
        assertTrue("菜单应贴着按钮：间距 ${gap}px，按钮 $chip，菜单末行 $lastRow", gap <= 24.dp.value * density())
    }

    @Test
    fun 选择器里当前贴的那一格带选中语义() {
        composeRule.setContent {
            ChogumiTheme {
                val picker = rememberReactionPickerState()
                Box(Modifier.fillMaxSize().reactionPickerAnchor(picker)) {
                    ReactionBar(reactions = emptyList(), palette = COMMON_REACTIONS, onReact = {}, onShowUsers = {}, onAdd = picker::openOn)
                    ReactionPicker(
                        picker,
                        COMMON_REACTIONS,
                        listOf(reactionOf(value = COMMON_REACTIONS.first(), count = 1, selected = true)),
                        onPick = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(addLabel).performClick()

        composeRule.onNode(isSelected()).assertExists()
        composeRule.onAllNodes(isSelectable()).assertCountEquals(COMMON_REACTIONS.size)
    }

    @Test
    fun 贴贴chip是可开关的_读屏读出已选中与否() {
        showBar(listOf(reactionOf(value = 79, count = 1, selected = true), reactionOf(value = 54, count = 2, selected = false)))

        composeRule.onNode(isToggleable() and hasText("1")).assertIsOn()
        composeRule.onNode(isToggleable() and hasText("2")).assertIsOff()
    }

    @Test
    fun 自己贴上后添加贴贴收起_取消后再出现() {
        showBar(emptyList())
        composeRule.onNodeWithContentDescription(addLabel).performClick()
        composeRule.onNodeWithText("1").assertIsOn()
        composeRule.onNodeWithContentDescription(addLabel).assertDoesNotExist()

        composeRule.onNodeWithText("1").performClick()
        composeRule.onNodeWithContentDescription(addLabel).assertExists()
    }

    @Test
    fun 给别人的贴贴加一后添加贴贴收起() {
        showBar(listOf(reactionOf(value = 79, count = 2, selected = false)))
        composeRule.onNodeWithContentDescription(addLabel).assertExists()

        composeRule.onNodeWithText("2").performClick()
        composeRule.onNodeWithText("3").assertIsOn()
        composeRule.onNodeWithContentDescription(addLabel).assertDoesNotExist()
    }

    @Test
    fun 已经贴着时不出添加贴贴() {
        showBar(listOf(reactionOf(value = 79, count = 1, selected = true), reactionOf(value = 54, count = 4)))
        composeRule.onNodeWithContentDescription(addLabel).assertDoesNotExist()
    }

    @Test
    fun 取消贴贴后添加贴贴重新出现的途中不被裁掉() {
        showBar(listOf(reactionOf(value = 79, count = 1, selected = true)))
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("1").performClick()
        assertAddChipNeverClipped()
    }

    @Test
    fun 贴贴和添加按钮的触控区不低于48dp_版面高度不变() {
        showBar(listOf(reactionOf(value = 79, count = 1, selected = false)))
        val minPx = 48.dp.value * density()
        listOf(composeRule.onNodeWithText("1"), composeRule.onNodeWithContentDescription(addLabel)).forEach { node ->
            val semantics = node.fetchSemanticsNode()
            val touch = semantics.touchBoundsInRoot
            assertTrue("触控区 ${touch.width}x${touch.height}px 应至少 ${minPx}px", touch.width >= minPx - 0.5f && touch.height >= minPx - 0.5f)
            // 版面里的 chip 还是原来的高，没被撑成 48dp
            assertTrue("chip 在版面里高 ${semantics.size.height}px，不被撑成 48dp", semantics.size.height < minPx)
        }
    }

    @Test
    fun 长按贴贴看谁贴了_点一下仍是贴上() {
        val shown = mutableListOf<Int>()
        showBar(listOf(reactionOf(value = 54, count = 1), reactionOf(value = 79, count = 2)), onShowUsers = { shown += it })

        composeRule.onNodeWithText("2").performTouchInput { longClick() }
        composeRule.runOnIdle { assertEquals(listOf(79), shown) }
        composeRule.onNodeWithText("2").assertIsOff()

        composeRule.onNodeWithText("2").performClick()
        composeRule.onNodeWithText("3").assertIsOn()
        composeRule.runOnIdle { assertEquals("点一下不打开名单", listOf(79), shown) }
    }

    @Test
    fun 不能贴时点一下贴贴就是看谁贴了() {
        val shown = mutableListOf<Int>()
        composeRule.setContent {
            ChogumiTheme {
                ReactionBar(reactions = listOf(reactionOf(value = 79, count = 2)), palette = COMMON_REACTIONS, onReact = null, onShowUsers = { shown += it })
            }
        }

        composeRule.onNodeWithText("2").performClick()
        composeRule.runOnIdle { assertEquals(listOf(79), shown) }
    }

    /** 一排贴贴，可点可取消；「添加贴贴」直接贴上 79（选择器另有用例）。 */
    private fun showBar(initial: List<CommentReactionBo>, onShowUsers: (Int) -> Unit = {}) {
        composeRule.setContent {
            // 使用应用当前的动效方案，逐帧检查尺寸变化是否裁掉刚出现的按钮。
            ChogumiTheme {
                var reactions by remember { mutableStateOf(initial) }
                ReactionBar(
                    reactions = reactions,
                    palette = COMMON_REACTIONS,
                    onReact = { value -> reactions = reactions.withMyReaction(value) },
                    onShowUsers = onShowUsers,
                    onAdd = { reactions = reactions.withMyReaction(79) },
                    modifier = Modifier.testTag(BAR),
                )
            }
        }
    }

    /** 逐帧看：这一排的右缘始终不切进「添加贴贴」。 */
    private fun assertAddChipNeverClipped() {
        repeat(FRAMES) { frame ->
            composeRule.mainClock.advanceTimeByFrame()
            val bar = composeRule.onNodeWithTag(BAR).fetchSemanticsNode().screenBounds()
            val add = composeRule.onNodeWithContentDescription(addLabel).fetchSemanticsNode().screenBounds()
            assertTrue("第 $frame 帧：一排右缘 ${bar.right} 切进了「添加贴贴」（右缘 ${add.right}）", bar.right >= add.right - 0.5f)
        }
    }

    private fun density() = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density

    private fun SemanticsNode.screenBounds(): Rect = Rect(positionOnScreen, size.toSize())

    private fun androidx.compose.ui.unit.IntSize.toSize() = androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat())

    private fun reactionCode(value: Int): String = when (val display = reactionDisplayOf(value)) {
        is ReactionDisplay.Smiley -> display.code
        is ReactionDisplay.Unknown -> error("选择器里不该有未知贴贴 $value")
    }

    private companion object {
        const val BAR = "reaction_bar"
        const val FRAMES = 40
    }
}
