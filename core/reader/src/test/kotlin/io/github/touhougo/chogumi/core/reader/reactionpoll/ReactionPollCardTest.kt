package io.github.touhougo.chogumi.core.reader.reactionpoll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.reactionOf
import io.github.touhougo.chogumi.core.model.withMyReaction
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 投票卡：没投过只列选项、点了就是贴对应表情；投过直接是结果，点我投的那项撤票回到选项；
 * 投票、改投、撤票、切换看结果，卡片的宽高都不变。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w360dp-h640dp")
class ReactionPollCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val poll = ReactionPoll(
        question = "游戏开始自选可操作角色性别你是选♂还是♀？",
        options = listOf(
            ReactionPoll.Option(54, "bgm38", "甲", votable = true),
            ReactionPoll.Option(140, "bgm124", "一个长到要折行的选项，看看百分比出现时会不会把文字挤到下一行去", votable = true),
        ),
    )

    /** 投票走真的「换掉我的贴贴」规则，卡片看到的是贴贴变化之后的计票，与楼层里一样。 */
    private fun setCard(
        initial: List<CommentReactionBo>,
        sent: MutableList<Int?> = mutableListOf(),
        fontScale: Float = 1f,
        poll: ReactionPoll = this.poll,
        shown: MutableList<Int> = mutableListOf(),
    ) {
        composeRule.setContent {
            var reactions by remember { mutableStateOf(initial) }
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Box(Modifier.width(328.dp).testTag(CARD)) {
                        ReactionPollCard(poll, poll.tallyOf(reactions), onVote = { value ->
                            sent += value
                            reactions = reactions.withMyReaction(value)
                        }, onShowUsers = { shown += it })
                    }
                }
            }
        }
    }

    private val longLabel get() = poll.options[1].label

    private fun cardBounds(): DpRect = composeRule.onNodeWithTag(CARD).getUnclippedBoundsInRoot()

    @Test
    fun 没投过只列选项点一项就投它并显示结果() {
        val sent = mutableListOf<Int?>()
        setCard(listOf(reactionOf(value = 140, count = 3)), sent)
        composeRule.onNodeWithText("75%").assertDoesNotExist()

        composeRule.onNodeWithText("甲").performClick()

        assertEquals(listOf<Int?>(54), sent)
        composeRule.onNodeWithText("25%").assertExists()
        composeRule.onNodeWithText("75%").assertExists()
        composeRule.onNodeWithText("共 4 票").assertExists()
    }

    @Test
    fun 投过直接是结果点我投的那项撤票回到选项() {
        val sent = mutableListOf<Int?>()
        setCard(listOf(reactionOf(value = 54, count = 1, selected = true), reactionOf(value = 140, count = 1)), sent)
        composeRule.onAllNodesWithText("50%", useUnmergedTree = true).assertCountEquals(2)

        composeRule.onNodeWithText("甲").performClick()

        assertEquals(listOf<Int?>(null), sent)
        composeRule.onNodeWithText("100%").assertDoesNotExist()
    }

    @Test
    fun 点别的项是改投() {
        val sent = mutableListOf<Int?>()
        setCard(listOf(reactionOf(value = 54, count = 1, selected = true)), sent)

        composeRule.onNodeWithText("甲").assertIsSelected()
        composeRule.onNode(hasText(longLabel)).performClick()

        assertEquals(listOf<Int?>(140), sent)
        composeRule.onNode(hasText(longLabel)).assertIsSelected()
        composeRule.onNodeWithText("100%").assertExists()
    }

    @Test
    fun 这里投不了的项照常列出票数但点了不投() {
        val sent = mutableListOf<Int?>()
        val withLocked = poll.copy(options = poll.options + ReactionPoll.Option(null, null, "不知道", votable = false))
        setCard(listOf(reactionOf(value = 54, count = 1, selected = true)), sent, poll = withLocked)

        composeRule.onNodeWithText("不知道").assertHasNoClickAction().performClick()

        assertEquals(emptyList<Int?>(), sent)
        composeRule.onNodeWithText("甲").assertIsSelected()
        composeRule.onAllNodesWithText("0%", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun 不投票也能切到结果再切回() {
        setCard(listOf(reactionOf(value = 140, count = 3)))

        composeRule.onNodeWithContentDescription("看结果").performClick()
        composeRule.onNodeWithText("100%").assertExists()

        composeRule.onNodeWithContentDescription("收起结果").performClick()
        composeRule.onNodeWithText("100%").assertDoesNotExist()
    }

    @Test
    fun 长按有票的选项看谁投的_没票的选项没有长按() {
        val sent = mutableListOf<Int?>()
        val shown = mutableListOf<Int>()
        setCard(listOf(reactionOf(value = 140, count = 3)), sent, shown = shown)

        composeRule.onNode(hasText(longLabel)).performTouchInput { longClick() }
        composeRule.onNodeWithText("甲").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))

        composeRule.runOnIdle {
            assertEquals(listOf(140), shown)
            assertEquals("长按不投票", emptyList<Int?>(), sent)
        }
    }

    @Test
    fun 投票撤票与切换结果时卡片宽高不变() = assertStableSize(fontScale = 1f)

    @Test
    fun 两倍字号下投票撤票与切换结果时卡片宽高也不变() = assertStableSize(fontScale = 2f)

    private fun assertStableSize(fontScale: Float) {
        setCard(listOf(reactionOf(value = 140, count = 3)), fontScale = fontScale)
        val options = cardBounds()
        composeRule.onNodeWithContentDescription("看结果").performClick()
        assertEquals("看结果时尺寸变了", options, cardBounds())
        composeRule.onNodeWithContentDescription("收起结果").performClick()
        composeRule.onNodeWithText("甲").performClick()
        assertEquals("投票后尺寸变了", options, cardBounds())
        composeRule.onNode(hasText(longLabel)).performClick()
        assertEquals("改投后尺寸变了", options, cardBounds())
        composeRule.onNode(hasText(longLabel)).performClick()
        assertEquals("撤票后尺寸变了", options, cardBounds())
    }

    private companion object {
        const val CARD = "card"
    }
}
