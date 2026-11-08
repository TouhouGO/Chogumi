package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 树图的排法：面积与数成正比、铺满不重叠、不排成细长条。 */
class TreemapLayoutTest {

    @Test
    fun `树图每块面积与部数成正比，合起来正好铺满不重叠`() {
        val weights = listOf(31f, 22f, 18f, 9f, 6f, 4f, 3f, 2f, 1f)
        val width = 300f
        val height = 200f
        val rects = squarify(weights, width, height)

        assertEquals(weights.size, rects.size)
        val total = weights.sum()
        rects.forEachIndexed { i, r ->
            assertClose(width * height * weights[i] / total, r.width * r.height, "第 $i 块的面积")
            assertTrue(r.left >= -EPS && r.top >= -EPS && r.right <= width + EPS && r.bottom <= height + EPS, "第 $i 块在画布里：$r")
        }
        assertClose(width * height, rects.sumOf { (it.width * it.height).toDouble() }.toFloat(), "合起来的面积")
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            val overlap = rects[i].intersect(rects[j])
            assertTrue(overlap.width <= EPS || overlap.height <= EPS, "第 $i 与第 $j 块重叠：${rects[i]} ${rects[j]}")
        }
    }

    @Test
    fun `树图的块不会排成细长条`() {
        val rects = squarify(listOf(10f, 9f, 8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f), 300f, 200f)
        val worst = rects.maxOf { aspect(it) }
        assertTrue(worst < 4f, "最细长的一块长宽比 $worst")
    }

    @Test
    fun `只有一块时占满整块`() {
        assertEquals(listOf(Rect(0f, 0f, 300f, 200f)), squarify(listOf(5f), 300f, 200f))
    }

    private fun aspect(r: Rect): Float = max(r.width / r.height, r.height / r.width)

    private fun assertClose(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= expected * 1e-3f + EPS, "$what：期望 $expected，实际 $actual")

    private companion object {
        const val EPS = 0.01f
    }
}
