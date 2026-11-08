package io.github.touhougo.chogumi.core.ui.chart

import org.junit.Assert.assertEquals
import org.junit.Test

class HeatRowLabelsTest {

    private val months = (1..12).map { if (it % 2 == 1) "${it}月" else "" }

    @Test
    fun `标签没有超过行距时原样都写`() {
        assertEquals(months, visibleRowLabels(months, labelHeight = 20f, pitch = 11f))
    }

    @Test
    fun `大字号下标签比两行还高_隔着写以免压在一起`() {
        // 行高 27、标签高 55：1 月之后 3 月（隔 54）压得上，5 月才隔得开
        val shown = visibleRowLabels(months, labelHeight = 55f, pitch = 27f)
        assertEquals(listOf("1月", "5月", "9月"), shown.filter { it.isNotEmpty() })
        assertEquals(months.size, shown.size)
    }

    @Test
    fun `写出来的任意两个标签中线至少隔一个标签高`() {
        listOf(8f, 14f, 27f, 40f).forEach { pitch ->
            val kept = visibleRowLabels(months, labelHeight = 36f, pitch = pitch).withIndex().filter { it.value.isNotEmpty() }.map { it.index * pitch }
            kept.zipWithNext().forEach { (a, b) -> assertEquals("pitch=$pitch: ${b - a} 小于标签高 36", true, b - a >= 36f) }
        }
    }

    @Test
    fun `没有标签时什么都不写`() {
        assertEquals(listOf("", ""), visibleRowLabels(listOf("", ""), labelHeight = 20f, pitch = 10f))
    }
}
