package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 格子尺：点格子到那一格；能到的最后一格之后拖不过去；读屏直接设值；甩动途中传回来的值慢一拍也不把尺子往回拽；
 * 外面改了值尺子滑过去、途中不乱报。
 */
@RunWith(AndroidJUnit4::class)
class TileRulerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var value by mutableIntStateOf(5)
    private val reported = mutableListOf<Int>()

    /**
     * [lagged]：报出的值晚一帧才传回尺子——真机上一帧里先走动画、后重组，甩动途中传回来的值总比尺子正中慢一拍。
     */
    private fun setRuler(last: Int = 30, limit: Int = last, lagged: Boolean = false) {
        composeRule.setContent {
            ChogumiTheme {
                var pending by remember { mutableStateOf<Int?>(null) }
                // 每个报出来的值等下一帧再传回去
                LaunchedEffect(pending) {
                    val next = pending ?: return@LaunchedEffect
                    withFrameNanos {}
                    value = next
                }
                TileRuler(
                    value = value,
                    last = last,
                    labelOf = Int::toString,
                    onValueChange = {
                        reported += it
                        if (lagged) pending = it else value = it
                    },
                    description = "ruler",
                    limit = limit,
                    modifier = Modifier.testTag(RulerTag),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private val ruler get() = composeRule.onNodeWithTag(RulerTag)

    /** 一格连格距的像素（与尺子里同样按格、距各自取整）。 */
    private val stepPx: Float
        get() = with(composeRule.density) { (GridCell.EpisodeTile.minWidth.roundToPx() + GridCell.EpisodeTile.spacing.roundToPx()).toFloat() }

    /** 格子一行的中线离尺子顶边多远：框比格子高出的那一截加半格。 */
    private val cellCenterY: Float
        get() = with(composeRule.density) { (6.dp + GridCell.EpisodeTile.minWidth / 2).toPx() }

    @Test
    fun 点右边第二格_到那一格() {
        setRuler()
        ruler.performTouchInput { click(Offset(centerX + 2 * stepPx, cellCenterY)) }
        composeRule.waitForIdle()
        assertEquals(7, value)
    }

    @Test
    fun 能到的最后一格之后拖不过去() {
        setRuler(last = 30, limit = 8)
        ruler.performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 300) }
        composeRule.waitForIdle()
        assertEquals(8, value)
        assertTrue("越过的格从没报出去过：$reported", reported.all { it <= 8 })
    }

    @Test
    fun 读屏直接设值_不超过能到的那一格() {
        setRuler(limit = 12)
        ruler.performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        composeRule.waitForIdle()
        assertEquals(12, value)
        ruler.performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        composeRule.waitForIdle()
        assertEquals(3, value)
    }

    @Test
    fun 甩一下_传回来的值慢一拍_惯性照样带着走_报出的值一路往前() {
        setRuler(last = 300, lagged = true)
        var draggedCells = 0f
        ruler.performTouchInput {
            draggedCells = (right - 10f - centerX) / stepPx
            swipeLeft(startX = right - 10f, endX = centerX, durationMillis = 50)
        }
        composeRule.waitForIdle()
        assertEquals("报出的值不往回跳：$reported", reported.sorted(), reported)
        assertEquals(reported.last(), value)
        // 晚到的值若把尺子往回拽，甩动当场被打断，只走出手指拖过的那几格
        assertTrue("松手后惯性带着走：拖了 $draggedCells 格，停在 $value", value - 5 > draggedCells * 3)
    }

    @Test
    fun 外面改了值_尺子滑过去_途中不报途经的格() {
        setRuler()
        composeRule.runOnIdle { value = 12 }
        composeRule.waitForIdle()
        assertTrue("滑过去途中不报：$reported", reported.isEmpty())
        // 正中那一格已经是 12：点它报出的就是 12
        ruler.performTouchInput { click(Offset(centerX, cellCenterY)) }
        composeRule.waitForIdle()
        assertEquals(listOf(12), reported)
    }

    private companion object {
        const val RulerTag = "ruler"
    }
}
