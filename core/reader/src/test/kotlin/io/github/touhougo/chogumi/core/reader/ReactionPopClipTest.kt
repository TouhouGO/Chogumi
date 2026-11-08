package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.withMyReaction
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 贴上时 chip 弹一下是整颗放大：放大伸出这一排的那一圈照样画出来，不被这一排的边界切成一个框。
 * 楼层里的一排只有 chip（没有更高的「添加贴贴」），这一排和 chip 一样高，放大的上下沿都在一排之外。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReactionPopClipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 贴上时弹一下的放大画到这一排之外() {
        composeRule.setContent {
            ChogumiTheme {
                var reactions by remember { mutableStateOf(listOf(CommentReactionBo(value = 79, count = 1))) }
                Box(Modifier.background(BACKGROUND).padding(24.dp)) {
                    ReactionBar(
                        reactions = reactions,
                        palette = COMMON_REACTIONS,
                        onReact = { value -> reactions = reactions.withMyReaction(value) },
                        modifier = Modifier.testTag(BAR),
                    )
                }
            }
        }
        val bar = composeRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot
        val chip = composeRule.onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        val x = chip.center.x.toInt()
        // 一排上沿再往上 2px：chip 静止时这里是底色，放大到顶时（28dp × 0.15 / 2 ≈ 6px @xxhdpi）被 chip 盖住
        val y = bar.top.toInt() - 2
        assertTrue("静止时一排之外应是底色", pixel(x, y) == BACKGROUND)

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("1").performClick()
        val seen = List(FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            pixel(x, y)
        }
        assertTrue("弹一下的放大应画出一排的上沿（每帧该点颜色：$seen）", seen.any { it != BACKGROUND })
    }

    @Test
    fun 贴贴被别处改成已贴时不弹() {
        var reactions by mutableStateOf(listOf(CommentReactionBo(value = 79, count = 1)))
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.background(BACKGROUND).padding(24.dp)) {
                    ReactionBar(reactions = reactions, palette = COMMON_REACTIONS, onReact = {}, modifier = Modifier.testTag(BAR))
                }
            }
        }
        val bar = composeRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot
        val chip = composeRule.onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        val x = chip.center.x.toInt()
        val y = bar.top.toInt() - 2

        composeRule.mainClock.autoAdvance = false
        // 刷新或另一台设备让这条变成「我贴过」：没有点按，不弹
        composeRule.runOnIdle { reactions = reactions.withMyReaction(79) }
        val seen = List(FRAMES) {
            composeRule.mainClock.advanceTimeByFrame()
            pixel(x, y)
        }
        assertTrue("没有点按就不弹，一排上沿之外始终是底色（每帧该点颜色：$seen）", seen.all { it == BACKGROUND })
    }

    private fun pixel(x: Int, y: Int): Color = composeRule.onRoot().captureToImage().toPixelMap()[x, y]

    private companion object {
        const val BAR = "reaction_bar"
        const val FRAMES = 30
        val BACKGROUND = Color.White
    }
}
