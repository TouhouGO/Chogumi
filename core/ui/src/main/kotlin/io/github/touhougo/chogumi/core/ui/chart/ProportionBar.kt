package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 比例条的一段：宽度按 [weight] 分，[tip] 是点按气泡的字。 */
class ProportionSegment(val weight: Int, val color: Color, val tip: String)

/** 一段的内容与画布内位置，绘制和命中读取同一个矩形。 */
internal data class PlacedProportionSegment(val segment: ProportionSegment, val bounds: Rect)

/**
 * 正值段与间隔共享 [widthPx]：先留每段最小宽度与间隔，余量按权重分；预算不够时两者同比压缩。
 * 用 Double 累计避免权重总和溢出和窄段累积误差，最后一段右缘固定在画布末端。尺寸均为画布像素。
 */
internal fun placeProportionSegments(
    segments: List<ProportionSegment>,
    widthPx: Float,
    heightPx: Float,
    gapPx: Float,
    minWidthPx: Float,
): List<PlacedProportionSegment> {
    if (widthPx <= 0f || heightPx <= 0f) return emptyList()
    val present = segments.filter { it.weight > 0 }
    if (present.isEmpty()) return emptyList()
    val minimum = minWidthPx.toDouble()
    val gap = gapPx.toDouble()
    val minimumBudget = minimum * present.size + gap * (present.size - 1)
    val scale = (widthPx / minimumBudget).coerceAtMost(1.0)
    val reservedWidth = minimum * scale
    val reservedGap = gap * scale
    val remainder = (widthPx - minimumBudget).coerceAtLeast(0.0)
    val total = present.sumOf { it.weight.toDouble() }
    var x = 0.0
    return present.mapIndexed { index, segment ->
        val right = x + reservedWidth + remainder * segment.weight / total
        val bounds = Rect(
            x.toFloat().coerceAtMost(widthPx),
            0f,
            if (index == present.lastIndex) widthPx else right.toFloat().coerceAtMost(widthPx),
            heightPx,
        )
        x = right + reservedGap
        PlacedProportionSegment(segment, bounds)
    }
}

/**
 * 比例条：一个整体按顺序分段（收藏状态、篇幅档、评价三档……），预留段间 2dp、每段 4dp，余量按权重分。
 * 极窄时段宽与间隔一起压缩；非正值不画。整条外形取 shapes.small。几何是我的设计决定（not in M3）。
 */
@Composable
fun ProportionBar(segments: List<ProportionSegment>, description: String, height: Dp, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.small
    TapChart(description, modifier.fillMaxWidth().height(height)) {
        val gap = 2.dp.toPx()
        val minWidth = 4.dp.toPx()
        val placed = placeProportionSegments(segments, widthPx, heightPx, gap, minWidth)
        ChartLayout(
            marks = placed.map { (segment, bounds) -> ChartMark(bounds, segment.tip) },
            draw = {
                val outline = shape.createOutline(size, layoutDirection, this)
                val clip = Path().apply { addOutline(outline) }
                clipPath(clip) {
                    placed.forEach { (segment, bounds) -> drawRect(segment.color, bounds.topLeft, bounds.size) }
                }
            },
        )
    }
}
