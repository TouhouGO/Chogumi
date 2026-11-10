package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

/** 收藏状态图点哪一段就出哪一段的提示：命中区跟着画出来的段（分段条）或图例行（环形）走。 */
class StatsSegmentsHitTest {

    private fun tapped(bounds: List<Rect>, point: Offset): Int? {
        val marks = bounds.mapIndexed { i, rect -> ChartMark(rect, "$i") }
        return markAt(marks, point, slop = 0f)?.label?.toInt()
    }

    @Test
    fun `分段条点最右一段出最后一个状态`() {
        val bounds = stripSegmentBounds(listOf(0.4f, 0.3f, 0.2f, 0.1f), widthPx = 300f, heightPx = 20f)

        assertEquals(3, tapped(bounds, Offset(285f, 10f)))
    }

    @Test
    fun `分段条点第一段仍出第一个状态`() {
        val bounds = stripSegmentBounds(listOf(0.4f, 0.3f, 0.2f, 0.1f), widthPx = 300f, heightPx = 20f)

        assertEquals(0, tapped(bounds, Offset(10f, 10f)))
        assertEquals(1, tapped(bounds, Offset(150f, 10f)))
    }

    @Test
    fun `环形点图例第三行出第三个状态`() {
        val bounds = ringLegendBounds(4, leftPx = 142f, widthPx = 360f, rowPx = 28f)

        assertEquals(2, tapped(bounds, Offset(200f, 2 * 28f + 14f)))
    }
}
