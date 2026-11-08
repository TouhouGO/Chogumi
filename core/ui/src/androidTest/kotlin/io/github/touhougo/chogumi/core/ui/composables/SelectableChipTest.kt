package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 共用的可选中 chip：勾选图标随选中出现，宽度随选中过渡，且未选中的外观与裸 FilterChip 一致。 */
@RunWith(AndroidJUnit4::class)
class SelectableChipTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var selected by mutableStateOf(false)

    private fun setChip() {
        composeRule.setContent {
            ChogumiTheme {
                SelectableChip(
                    label = "书籍",
                    selected = selected,
                    modifier = Modifier.testTag(CHIP),
                    onClick = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun chipWidth() = composeRule.onNodeWithTag(CHIP).getUnclippedBoundsInRoot().width.value

    private fun checkCount() =
        composeRule.onAllNodesWithTag(SelectableChipCheckTag, useUnmergedTree = true)

    @Test
    fun 选中的chip带勾选图标_未选中的不带() {
        setChip()
        checkCount().assertCountEquals(0)

        composeRule.runOnIdle { selected = true }
        composeRule.waitForIdle()
        checkCount().assertCountEquals(1)

        composeRule.runOnIdle { selected = false }
        composeRule.waitForIdle()
        checkCount().assertCountEquals(0)
    }

    @Test
    fun 没有任何图标的未选中chip宽度与裸FilterChip一致() {
        composeRule.setContent {
            ChogumiTheme {
                Column {
                    SelectableChip(label = "书籍", selected = false, modifier = Modifier.testTag(CHIP), onClick = {})
                    FilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text("书籍", style = MaterialTheme.typography.labelLarge) },
                        modifier = Modifier.testTag(BARE),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val bare = composeRule.onNodeWithTag(BARE).getUnclippedBoundsInRoot().width.value
        assertEquals("共用 chip 不能因为预留图标槽而在未选中时变宽", bare, chipWidth(), 0.5f)
    }

    @Test
    fun 选中时宽度逐帧过渡_不在一帧内跳到终值() {
        setChip()
        val start = chipWidth()

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { selected = true }
        // 每帧采样，不靠轮询某个瞬间：动画开始后的前几帧宽度必须从起点向终点渐变。
        val frames = (1..12).map {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            chipWidth()
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        val end = chipWidth()

        assertTrue("选中后应比未选中更宽（多出勾选图标）：$start -> $end", end > start + 10f)
        assertTrue(
            "宽度必须有过渡帧（严格介于起点与终点之间），不能一帧跳到终值：start=$start frames=$frames end=$end",
            frames.any { it > start + 1f && it < end - 1f },
        )
    }

    private companion object {
        const val CHIP = "chip_under_test"
        const val BARE = "bare_chip"
    }
}
