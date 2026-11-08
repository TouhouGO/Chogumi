package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** 十档柱的档数：1–10 分。 */
private const val TEN_BARS_LEVELS = 10

/**
 * 十档柱：1–10 分各多少（人 / 部），柱下是分数轴。最高的一根实色（primary），其余浅一档（secondaryContainer）；
 * 柱顶不写数，确切的数点按看（[tips] 与 [counts] 一一对应，整列连同柱下的分数都能点，矮柱也点得中）。
 * 生涯的「我的打分」与条目的「评分分布」都用它。柱子本身由 [CountBars] 画。
 */
@Composable
fun TenBars(counts: List<Int>, tips: List<String>, description: String, modifier: Modifier = Modifier) {
    require(counts.size == TEN_BARS_LEVELS && tips.size == TEN_BARS_LEVELS) { "十档柱要 10 个数与 10 条气泡字" }
    CountBars(counts, (1..TEN_BARS_LEVELS).map { "$it" }, tips, description, TEN_BARS_HEIGHT, modifier)
}

private val TEN_BARS_HEIGHT = 120.dp

/**
 * 计数柱：一组按顺序排的计数，每根柱下一个短标签（分数、星期）。最高的一根实色（primary），其余浅一档
 * （secondaryContainer）；柱顶不写数，确切的数点按看（整列连同标签都能点，矮柱也点得中）。
 * [TenBars] 与生涯「几点看」的一周七天都用它。
 *
 * 几何是我的设计决定（not in M3）：柱间 6dp、柱角取 shapes.extraSmall、标签行 20dp、
 * 0 的柱留 2dp 底线、有数的柱至少 4dp 高。
 */
@Composable
fun CountBars(counts: List<Int>, labels: List<String>, tips: List<String>, description: String, height: Dp, modifier: Modifier = Modifier) {
    require(labels.size == counts.size && tips.size == counts.size) { "每根柱一个标签、一条气泡字" }
    val shape = MaterialTheme.shapes.extraSmall
    val peakColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.secondaryContainer
    val axis = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    TapChart(description, modifier.fillMaxWidth().height(height)) {
        val gap = 6.dp.toPx()
        val barWidth = (widthPx - gap * (counts.size - 1)) / counts.size
        val base = heightPx - 20.dp.toPx()
        val tallest = base - 2.dp.toPx()
        val peakCount = counts.max()
        val peak = counts.indexOf(peakCount)
        val heights = counts.map { n ->
            if (n > 0) max(4.dp.toPx(), tallest * n / peakCount) else 2.dp.toPx()
        }
        fun leftOf(i: Int) = i * (barWidth + gap)
        ChartLayout(
            marks = counts.indices.map { i ->
                ChartMark(
                    Rect(leftOf(i), 0f, leftOf(i) + barWidth, heightPx),
                    tips[i],
                    anchor = Rect(leftOf(i), base - heights[i], leftOf(i) + barWidth, base),
                )
            },
            draw = {
                counts.indices.forEach { i ->
                    val outline = shape.createOutline(Size(barWidth, heights[i]), layoutDirection, this)
                    translate(leftOf(i), base - heights[i]) {
                        drawOutline(outline, if (i == peak && peakCount > 0) peakColor else restColor)
                    }
                    label(labels[i], leftOf(i) + barWidth / 2, heightPx - 4.dp.toPx(), axis, muted)
                }
            },
        )
    }
}
