package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/**
 * 图里放一行 [style] 文字的带（刻度轴、行距）有多高：不低于 [min]，也不低于这个字阶的行高。
 * 图的字阶随系统字号变大，而带的高度若写死 dp，大字号下字会压住上一行；所有图表的这类高度只走这里。
 */
@Composable
internal fun textBand(style: TextStyle, min: Dp): Dp = with(LocalDensity.current) { maxOf(min, style.lineHeight.toDp()) }

/**
 * 同一条轴上的一串标签谁留谁丢的**唯一判定**：[spans] 是各标签占的横向区间，按重要性从高到低排好；
 * 依次看，和已经留下的任何一个相距不足 [gap] 就丢掉这一个。返回与 [spans] 一一对应的「留不留」。
 * 大字号、窄屏时标签变宽，互相压住的文字不如少写一个。
 */
fun keepSeparated(spans: List<ClosedFloatingPointRange<Float>>, gap: Float): List<Boolean> {
    val kept = mutableListOf<ClosedFloatingPointRange<Float>>()
    return spans.map { span ->
        val clear = kept.none { it.start - gap < span.endInclusive && span.start - gap < it.endInclusive }
        if (clear) kept += span
        clear
    }
}
