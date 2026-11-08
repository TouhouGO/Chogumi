package io.github.touhougo.chogumi.core.ui.chart

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** 泳道横轴的月份刻度：每月一日，多了隔几个月取一个。 */
class SpanLanesTicksTest {

    @Test
    fun `不多于上限时每月一日都是刻度`() {
        assertEquals(
            listOf(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1)),
            monthTicks(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 3, 5)),
        )
    }

    @Test
    fun `同一个月内没有刻度`() {
        assertEquals(emptyList(), monthTicks(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 28)))
    }

    @Test
    fun `月份多了隔几个月取一个，总数不超过上限`() {
        val ticks = monthTicks(LocalDate.of(2025, 1, 10), LocalDate.of(2026, 1, 10))
        assertEquals(listOf(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 5, 1), LocalDate.of(2025, 8, 1), LocalDate.of(2025, 11, 1)), ticks)
    }
}
