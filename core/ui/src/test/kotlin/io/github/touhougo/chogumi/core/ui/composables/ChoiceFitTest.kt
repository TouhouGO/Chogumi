package io.github.touhougo.chogumi.core.ui.composables

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字连通按钮组的排法（[planTextChoices]）：按「等宽 → 按宽度分 → 收紧留白 → 收进更多」取第一个放得下的。
 * 单位按 1dp = 1px 写，数取自实测场景：380dp 屏、两侧 16 → 可用 348，格间距 2，留白 16 / 8，最小格宽 48。
 */
class ChoiceFitTest {

    private fun plan(widths: List<Int>, available: Int = 348, selectedIndex: Int? = null) = planTextChoices(
        labelWidths = widths,
        available = available,
        gap = 2,
        widePadding = 16,
        narrowPadding = 8,
        minButton = 48,
        moreButton = 36,
        selectedIndex = selectedIndex,
    )

    @Test
    fun `五个两字排序在 380dp 上等宽放得下`() {
        // 「排名」约 31：等宽一格 (348 - 8) / 5 = 68 ≥ 31 + 32
        assertEquals(ChoiceFit.Equal(16), plan(List(5) { 31 }))
    }

    @Test
    fun `有一项更长时按文字宽度分，长的那格更宽`() {
        // 「标注数」46 与三个两字，280 宽：等宽一格 (280 - 6) / 4 = 68 < 46 + 32 = 78；按宽度分合计 267 + 6 = 273 ≤ 280
        val fit = plan(listOf(31, 46, 31, 31), available = 280)
        assertTrue(fit is ChoiceFit.Flexible && fit.padding == 16)
        fit as ChoiceFit.Flexible
        assertTrue(fit.weightOf(1) > fit.weightOf(0))
    }

    @Test
    fun `留白 16 放不下时收紧到 8`() {
        // 五项 40：16 时需 5 × 72 + 8 = 368 > 348；8 时 5 × 56 + 8 = 288
        val fit = plan(List(5) { 40 })
        assertEquals(8, fit.padding)
        assertTrue(fit is ChoiceFit.Flexible)
    }

    @Test
    fun `连收紧都放不下时组尾收进更多，且前面的项加更多格放得下`() {
        // 五项 70：8 时需 5 × 86 + 8 = 438 > 348
        val fit = plan(List(5) { 70 })
        fit as ChoiceFit.Overflow
        assertEquals(listOf(0, 1, 2), fit.shownIndices)
        assertEquals(false, fit.stacked)
        // 前 3 项 3 × 86 + 更多 48 + 3 个间距 = 312 ≤ 348；4 项就是 400
        assertEquals(listOf(86f, 86f, 86f, 48f), fit.weights)
    }

    @Test
    fun `窄到一项加更多都放不下时仍保留一项文字再放更多`() {
        // 两项各需 220；第一项加更多和间距共 270，比可用的 250 还宽。此时不能再把第一项也藏掉；
        // 渲染层需要按物理宽度改用可访问的叠排，纯排法仍必须先保留一项文字。
        val fit = plan(listOf(220, 220), available = 250)
        fit as ChoiceFit.Overflow
        assertTrue("至少保留一项文字，不能只剩「更多」：shown=${fit.shownIndices}", fit.shownIndices.isNotEmpty())
        assertTrue("文字项与更多连横排都放不下时必须走两排回退", fit.stacked)
        assertEquals(2, fit.weights.size)
        assertEquals(48f, fit.weights.last())
    }

    @Test
    fun `任意位置的当前项在溢出时都留在组里`() {
        repeat(5) { selectedIndex ->
            val fit = plan(List(5) { 70 }, selectedIndex = selectedIndex)
            fit as ChoiceFit.Overflow
            assertTrue(
                "索引 $selectedIndex 的当前项不能被「更多」代替：${fit.shownIndices}",
                selectedIndex in fit.shownIndices,
            )
        }
    }

    @Test
    fun `每格不小于 48`() {
        val fit = plan(listOf(4, 200), available = 300)
        fit as ChoiceFit.Flexible
        assertEquals(48f, fit.weightOf(0))
    }

    @Test
    fun `没有可见项时不出错`() {
        assertEquals(ChoiceFit.Equal(16), plan(emptyList()))
    }
}
