package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 图表上点中哪一块，按画出来的那一块判：落在块上取它，几块画得叠着取中心最近的；落在块外、几块都把这一处算给自己时
 * 取画出来离手指最近的那块；落在缝里取近的那块，离得太远算点空白。
 */
class ChartMarkHitTest {

    private val left = ChartMark(Rect(0f, 0f, 10f, 10f), "左") {}
    private val right = ChartMark(Rect(12f, 0f, 22f, 10f), "右") {}
    private val marks = listOf(left, right)

    @Test
    fun `落在块里取那一块`() {
        assertSame(right, markAt(marks, Offset(15f, 5f), slop = 8f))
    }

    @Test
    fun `落在缝里取离得近的那块`() {
        assertSame(left, markAt(marks, Offset(10.5f, 5f), slop = 8f))
        assertSame(right, markAt(marks, Offset(11.5f, 5f), slop = 8f))
    }

    @Test
    fun `离所有块都超过容差时算点空白`() {
        assertNull(markAt(marks, Offset(40f, 5f), slop = 8f))
        assertNull(markAt(marks, Offset(5f, 30f), slop = 8f))
    }

    @Test
    fun `两块画在同一处时取后画的那块`() {
        val over = ChartMark(Rect(0f, 0f, 10f, 10f), "上") {}
        assertSame(over, markAt(marks + over, Offset(5f, 5f), slop = 8f))
    }

    @Test
    fun `几块画得叠着时_取中心离手指最近的那块`() {
        // 半径 5 的点每隔 3 一个：每个点的方块盖着左右邻点的中心
        val dots = listOf(10f, 13f, 16f).mapIndexed { i, x -> ChartMark(Rect(Offset(x, 5f), 5f), "$i") {} }
        assertEquals("0", markAt(dots, Offset(10f, 5f), slop = 8f)?.label)
        assertEquals("1", markAt(dots, Offset(13.4f, 5f), slop = 8f)?.label)
        assertEquals("2", markAt(dots, Offset(20f, 5f), slop = 8f)?.label)
    }

    /** 三根 2 宽的矮柱排在 x = 2、5、8，各自把左右 4 以内的整列算给自己：相邻几列互相叠着。 */
    private val bars = listOf(2f, 5f, 8f).mapIndexed { i, center ->
        ChartMark(anchor = Rect(center - 1f, 40f, center + 1f, 50f), label = "$i", hit = Rect(center - 4f, 0f, center + 4f, 50f)) {}
    }

    @Test
    fun `几块要的范围叠着时_点在一块上取这一块_不取后声明的`() {
        assertEquals("0", markAt(bars, Offset(2f, 45f), slop = 8f)?.label)
        assertEquals("1", markAt(bars, Offset(5f, 45f), slop = 8f)?.label)
    }

    @Test
    fun `几块要的范围叠着时_点在块上方的空白取画出来最近的那块`() {
        assertEquals("0", markAt(bars, Offset(2f, 10f), slop = 8f)?.label)
        assertEquals("1", markAt(bars, Offset(5.4f, 10f), slop = 8f)?.label)
        assertEquals("2", markAt(bars, Offset(11f, 10f), slop = 8f)?.label)
    }

    @Test
    fun `只有一块要这一处时_离得再远也取它`() {
        val column = ChartMark(anchor = Rect(0f, 40f, 10f, 50f), label = "柱", hit = Rect(0f, 0f, 10f, 60f)) {}
        val far = ChartMark(Rect(12f, 0f, 22f, 4f), "远") {}
        assertSame(column, markAt(listOf(column, far), Offset(9f, 2f), slop = 8f))
    }
}
