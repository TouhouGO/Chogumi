package io.github.touhougo.chogumi.core.ui.chart

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 图表气泡的点按契约：气泡开在被点那块上方，常常正盖着它上面一行的格子；点那一格要换成那一格的气泡，
 * 不能被气泡挡住。气泡停 1.5 秒后收起。
 *
 * 点按走系统输入（UiAutomation 注入），而不是 Compose 测试的 performTouchInput：只有系统输入会先经过
 * 气泡所在的窗口，才看得出气泡窗口有没有把手指拦下。系统输入也会先经过状态栏：图放在屏幕正中，
 * 不让被点的格子落在状态栏底下（落在那里的点按归状态栏，到不了图）。
 */
@RunWith(AndroidJUnit4::class)
class TapChartTipTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var view: View

    @Test
    fun `气泡盖着的格子点得到_换成那一格的气泡并停足一点五秒`() {
        composeRule.setContent {
            view = LocalView.current
            ChogumiTheme {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    UnitCells(GROUPS, columns = COLUMNS, description = DESCRIPTION)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        // 先点第 3 行第 5 格（「在看」），它的气泡开在上方，盖住第 2 行第 5 格（「看过」）；再点被盖住的那一格
        tapCell(row = 2, column = 4)
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        composeRule.onNodeWithText(DOING_TIP).assertIsDisplayed()

        tapCell(row = 1, column = 4)
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        composeRule.onNodeWithText(DONE_TIP).assertIsDisplayed()
        composeRule.onAllNodesWithText(DOING_TIP).assertCountEquals(0)

        composeRule.mainClock.advanceTimeBy(STILL_SHOWN_MILLIS - SETTLE_MILLIS)
        composeRule.onNodeWithText(DONE_TIP).assertIsDisplayed()

        composeRule.mainClock.advanceTimeBy(GONE_MILLIS - STILL_SHOWN_MILLIS)
        composeRule.onAllNodesWithText(DONE_TIP).assertCountEquals(0)
    }

    /** 点第 [row] 行第 [column] 格（从 0 数）的中心：格子几何与 [UnitCells] 相同（格间 2dp、正方形、填满宽）。 */
    private fun tapCell(row: Int, column: Int) {
        val bounds = composeRule.onNodeWithContentDescription(DESCRIPTION).fetchSemanticsNode().boundsInWindow
        val gap = 2 * view.resources.displayMetrics.density
        val cell = (bounds.width - gap * (COLUMNS - 1)) / COLUMNS
        val inWindow = Offset(bounds.left + column * (cell + gap) + cell / 2, bounds.top + row * (cell + gap) + cell / 2)
        val origin = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
        tapScreen(origin[0] + inWindow.x, origin[1] + inWindow.y)
        composeRule.waitForIdle()
    }

    private fun tapScreen(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to downTime, MotionEvent.ACTION_UP to downTime + PRESS_MILLIS).forEach { (action, time) ->
            val event = MotionEvent.obtain(downTime, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            automation.injectInputEvent(event, true)
            event.recycle()
        }
    }

    private companion object {
        const val COLUMNS = 20
        const val DESCRIPTION = "开始看的人看完了吗"
        const val DONE_TIP = "看过 40"
        const val DOING_TIP = "在看 20"

        /** 第 3 行整行「在看」，上下两行各是别的组：点偏一行，第一个气泡就不对。 */
        val GROUPS = listOf(
            UnitGroup(CollectionType.DONE, 40, DONE_TIP),
            UnitGroup(CollectionType.DOING, 20, DOING_TIP),
            UnitGroup(CollectionType.ON_HOLD, 20, "搁置 20"),
            UnitGroup(CollectionType.DROPPED, 20, "抛弃 20"),
        )
        const val PRESS_MILLIS = 40L
        const val SETTLE_MILLIS = 400L
        const val STILL_SHOWN_MILLIS = 1_200L
        const val GONE_MILLIS = 2_200L
    }
}
