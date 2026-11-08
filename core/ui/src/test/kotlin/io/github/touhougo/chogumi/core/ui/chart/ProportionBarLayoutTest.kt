package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 比例条的全部正值段与间隔共享一份宽度预算，零值不占位置。 */
class ProportionBarLayoutTest {

    @Test
    fun `极端权重的尾段仍在画布内且每段可见`() {
        val placed = place(listOf(9_999, 1, 1, 1, 1), width = 300f)

        assertBudget(placed, width = 300f)
        assertEquals(5, placed.size)
        assertEquals(300f, placed.last().bounds.right)
        placed.forEach { assertTrue(it.bounds.width >= 4f, "小段应保留可行的最小宽度：${it.bounds}") }
    }

    @Test
    fun `单段填满宽度且零值与负值不占间隔`() {
        val segments = segments(listOf(0, 7, -1, 0))
        val placed = placeProportionSegments(segments, 3f, 44f, 2f, 4f)

        assertEquals(1, placed.size)
        assertSame(segments[1], placed.single().segment)
        assertEquals(Rect(0f, 0f, 3f, 44f), placed.single().bounds)
    }

    @Test
    fun `全零或没有正值时不产生绘制与命中段`() {
        assertTrue(place(emptyList(), 300f).isEmpty())
        assertTrue(place(listOf(0, 0, 0), 300f).isEmpty())
        assertTrue(place(listOf(-1, 0, -2), 300f).isEmpty())
    }

    @Test
    fun `没有可用宽度或高度时不产生标记`() {
        assertTrue(place(listOf(1, 2), 0f).isEmpty())
        assertTrue(place(listOf(1, 2), -1f).isEmpty())
        assertTrue(placeProportionSegments(segments(listOf(1, 2)), 300f, 0f, 2f, 4f).isEmpty())
    }

    @Test
    fun `窄于最小总预算时段宽与间隔一起压缩`() {
        val placed = place(listOf(9_999, 1, 1, 1, 1), width = 14f)

        assertBudget(placed, width = 14f)
        assertEquals(
            listOf(
                Rect(0f, 0f, 2f, 44f),
                Rect(3f, 0f, 5f, 44f),
                Rect(6f, 0f, 8f, 44f),
                Rect(9f, 0f, 11f, 44f),
                Rect(12f, 0f, 14f, 44f),
            ),
            placed.map { it.bounds },
        )
    }

    @Test
    fun `宽度连原间隔都放不下时正值段仍有宽度`() {
        val placed = place(listOf(9_999, 1, 1, 1, 1), width = 1f)

        assertEquals(5, placed.size)
        assertBudget(placed, width = 1f)
        placed.forEach { assertEquals(1f / 7f, it.bounds.width, 0.000001f) }
        assertEquals(1f, placed.last().bounds.right)
    }

    @Test
    fun `等权重保留等宽段与可行间隔`() {
        val placed = place(listOf(1, 1, 1, 1, 1), width = 300f)

        assertBudget(placed, width = 300f)
        placed.forEach { assertEquals(58.4f, it.bounds.width, 0.0001f) }
        placed.zipWithNext().forEach { (a, b) -> assertEquals(2f, b.bounds.left - a.bounds.right, 0.0001f) }
    }

    @Test
    fun `余量按权重分配且标签沿输入顺序关联`() {
        val segments = segments(listOf(0, 1, 0, 3))
        val placed = placeProportionSegments(segments, 20f, 44f, 2f, 4f)

        assertEquals(listOf(Rect(0f, 0f, 6.5f, 44f), Rect(8.5f, 0f, 20f, 44f)), placed.map { it.bounds })
        assertSame(segments[1], placed[0].segment)
        assertSame(segments[3], placed[1].segment)
    }

    @Test
    fun `权重总和超过整数上限时仍按真实权重分配`() {
        val placed = place(listOf(Int.MAX_VALUE, Int.MAX_VALUE, 1), width = 300f)

        assertBudget(placed, width = 300f)
        assertEquals(146f, placed[0].bounds.width, 0.0001f)
        assertEquals(146f, placed[1].bounds.width, 0.0001f)
        assertEquals(4f, placed[2].bounds.width, 0.0001f)
    }

    @Test
    fun `浮点宽度与长尾在不同位置时整条都守预算`() {
        val weights = listOf(
            listOf(9_999, 1, 1, 1, 1),
            listOf(1, 9_999, 1, 1, 1),
            listOf(1, 1, 1, 1, 9_999),
            listOf(0, Int.MAX_VALUE, 0, 1, Int.MAX_VALUE),
        )
        listOf(0.001f, 1f, 8f, 20f, 28f, 29.1f, 300f, 380f, 448f, 800f).forEach { width ->
            weights.forEach { values ->
                val placed = place(values, width)
                assertEquals(values.count { it > 0 }, placed.size)
                assertBudget(placed, width)
                assertEquals(width, placed.last().bounds.right)
            }
        }
    }

    @Test
    fun `大量段在极窄画布内不交叉也不丢正值段`() {
        val placed = place(List(100) { 1 }, width = 0.01f)

        assertEquals(100, placed.size)
        assertBudget(placed, width = 0.01f)
        assertEquals(0.01f, placed.last().bounds.right)
    }

    private fun place(weights: List<Int>, width: Float) =
        placeProportionSegments(segments(weights), width, 44f, 2f, 4f)

    private fun segments(weights: List<Int>) = weights.mapIndexed { index, weight ->
        ProportionSegment(weight, Color.Black, "第 $index 段")
    }

    private fun assertBudget(placed: List<PlacedProportionSegment>, width: Float) {
        placed.forEach { (_, bounds) ->
            assertTrue(bounds.left >= 0f && bounds.right <= width, "$bounds 应在 0 到 $width 之间")
            assertTrue(bounds.width > 0f && bounds.height == 44f, "正值段应可见：$bounds")
        }
        placed.zipWithNext().forEach { (a, b) ->
            assertTrue(a.bounds.right <= b.bounds.left, "相邻段不能相交：${a.bounds}、${b.bounds}")
        }
    }
}
