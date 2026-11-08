package io.github.touhougo.chogumi.feature.profile

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 一排大数字的排法（[planKpiRow]）：按顺序取第一个放得下的——等宽 → 按内容分宽 → 留白收窄 → 竖排。
 * 可用宽 300、格间距 8、留白 12 / 8：等宽时每格 (300 − 16) / 3 = 94。
 */
class KpiRowPlanTest {

    private fun plan(vararg contentWidths: Int) =
        planKpiRow(contentWidths.toList(), available = 300, gap = 8, widePadding = 12, narrowPadding = 8)

    @Test
    fun `每格都放得进等分的宽度时等宽`() {
        assertEquals(KpiRowPlan.Equal, plan(60, 70, 50))
    }

    @Test
    fun `有一格放不进等分宽度但总宽够时按内容分宽`() {
        assertEquals(KpiRowPlan.Flexible(narrow = false, weights = listOf(84f, 114f, 64f)), plan(60, 90, 40))
    }

    @Test
    fun `按内容分宽也放不下时先把留白收窄`() {
        assertEquals(KpiRowPlan.Flexible(narrow = true, weights = listOf(86f, 106f, 80f)), plan(70, 90, 64))
    }

    @Test
    fun `收窄留白也放不下时竖排`() {
        assertEquals(KpiRowPlan.Stacked, plan(120, 120, 120))
    }
}
