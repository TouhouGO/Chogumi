package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/** 图表上点中哪一块：落在块里取它，落在两块之间的缝里取近的那块，离得太远算点空白。 */
class ChartMarkHitTest {

    private val left = ChartMark(Rect(0f, 0f, 10f, 10f), "左")
    private val right = ChartMark(Rect(12f, 0f, 22f, 10f), "右")
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
    fun `叠在一起时取后画的那块`() {
        val over = ChartMark(Rect(0f, 0f, 22f, 10f), "上")
        assertSame(over, markAt(marks + over, Offset(5f, 5f), slop = 8f))
    }
}
