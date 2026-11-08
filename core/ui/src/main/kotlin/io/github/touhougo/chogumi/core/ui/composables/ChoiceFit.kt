package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

/** 文字版的排法（像素）。[padding] 是按钮内左右留白；[weightOf] 给第 i 个可见格（「更多」排在最后）的宽度权重。 */
internal sealed interface ChoiceFit {
    val padding: Int

    fun weightOf(index: Int): Float

    /** 等宽。 */
    data class Equal(override val padding: Int) : ChoiceFit {
        override fun weightOf(index: Int) = 1f
    }

    /** 按每格所需宽度分（[weights] 与可见项一一对应）。 */
    data class Flexible(override val padding: Int, val weights: List<Float>) : ChoiceFit {
        override fun weightOf(index: Int) = weights.getOrElse(index) { 1f }
    }

    /**
     * 只排 [shownIndices] 项，其余收进组尾「更多」；[weights] 与这些项加「更多」格一一对应。
     * [stacked] 表示文字项与「更多」在同一条横排里连物理宽度都不够，渲染层把文字项和「更多」分成两排。
     */
    data class Overflow(
        override val padding: Int,
        val shownIndices: List<Int>,
        val weights: List<Float>,
        val stacked: Boolean,
    ) : ChoiceFit {
        override fun weightOf(index: Int) = weights.getOrElse(index) { 1f }
    }
}

/**
 * 文字连通按钮组的排法——**唯一判定点**（纯函数，单测见 ChoiceFitTest）。按顺序取第一个放得下的：
 * 1. 等宽，留白 [widePadding]：最宽的文字也放得进一格；
 * 2. 按所需宽度分，留白 [widePadding]；
 * 3. 按所需宽度分，留白 [narrowPadding]；
 * 4. 组尾收进「更多」（宽 [moreButton]）：优先保留当前选中项；没有选中项时保留第一项。
 *    其余放得下的项按原顺序留在组里，放不下的从「更多」进入。即使选中项加「更多」连物理宽度都不够，
 *    也不会降到零项；[ChoiceFit.Overflow.stacked] 让渲染层改用可访问的两排布局。
 * 每格所需宽度 = 文字 + 两侧留白，且不小于 [minButton]（M3 连通按钮组最小宽度）。[gap] 是格间距。
 */
internal fun planTextChoices(
    labelWidths: List<Int>,
    available: Int,
    gap: Int,
    widePadding: Int,
    narrowPadding: Int,
    minButton: Int,
    moreButton: Int,
    selectedIndex: Int? = null,
): ChoiceFit {
    val count = labelWidths.size
    if (count == 0) return ChoiceFit.Equal(widePadding)
    val gaps = gap * (count - 1)
    fun needs(padding: Int) = labelWidths.map { maxOf(it + 2 * padding, minButton) }
    val wide = needs(widePadding)
    if (wide.max() <= (available - gaps) / count) return ChoiceFit.Equal(widePadding)
    if (wide.sum() + gaps <= available) return ChoiceFit.Flexible(widePadding, wide.map(Int::toFloat))
    val narrow = needs(narrowPadding)
    if (narrow.sum() + gaps <= available) return ChoiceFit.Flexible(narrowPadding, narrow.map(Int::toFloat))
    val more = maxOf(moreButton, minButton)
    val required = selectedIndex?.takeIf { it in 0 until count } ?: 0
    var shownIndices = listOf(required)
    fun fits(indices: List<Int>) = indices.sumOf { narrow[it] } + more + gap * indices.size <= available

    for (index in 0 until count) {
        if (index == required) continue
        val candidate = (shownIndices + index).sorted()
        if (fits(candidate)) shownIndices = candidate
    }
    val stacked = !fits(shownIndices)
    return ChoiceFit.Overflow(
        padding = narrowPadding,
        shownIndices = shownIndices,
        weights = shownIndices.map { narrow[it].toFloat() } + more.toFloat(),
        stacked = stacked,
    )
}
