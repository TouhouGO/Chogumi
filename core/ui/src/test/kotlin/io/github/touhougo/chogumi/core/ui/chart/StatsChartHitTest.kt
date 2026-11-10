package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

/** 统计图点哪一块就出哪一块的提示：命中区跟着画出来的几何走（分段条的段、环形的图例行、折线的点）。 */
class StatsChartHitTest {

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

    /** 300 宽画布、两侧各留 16 的折线上排 [count] 个点的横坐标，与 `StatsLine` 同一种排法。 */
    private fun lineXs(count: Int, width: Float = 300f) = List(count) { i -> 16f + i * (width - 32f) / (count - 1) }

    @Test
    fun `密集折线点第一个点出第一集`() {
        val xs = lineXs(24)
        val bounds = lineHitBounds(xs, widthPx = 300f, heightPx = 136f)

        assertEquals(0, tapped(bounds, Offset(xs[0], 60f)))
        assertEquals(11, tapped(bounds, Offset(xs[11], 60f)))
    }

    @Test
    fun `稀疏折线点在两点之间偏右出右边那一点`() {
        val xs = lineXs(3)
        val bounds = lineHitBounds(xs, widthPx = 300f, heightPx = 136f)

        assertEquals(1, tapped(bounds, Offset((xs[0] + xs[1]) / 2 + 1f, 60f)))
        assertEquals(2, tapped(bounds, Offset(299f, 60f)))
    }
}
