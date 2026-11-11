package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max

/** 点条的一点：[x] 是在轴上的位置（0–1），[color] 是点色，[title] 是作品名，[tip] 是气泡字。 */
class StripDot(val x: Float, val color: Color, val title: String, val tip: String)

/** 点条的一个刻度：[at] 是在轴上的位置（0–1）。 */
class StripTick(val at: Float, val label: String)

/**
 * 点条：同一箱的点（[bins] 一项一箱，箱内的点同一个 x）叠成一摞，[centered] 时以中线为准上下交替叠（分差），
 * 否则从底向上叠（收藏人数）。[marker] 是一条竖线（零分差、中位数），[ticks] 写在轴下。
 * 高度随最高的一摞，但不超过 [DOT_MAX_ROWS] 行：摞不下时一个点代表几部（[dotScaleOf]），左上角写比例，
 * 点色取这几部里最多的那种，气泡字由 [groupTip] 写（几部、哪几部的名字），比例由 [scaleLabel] 写。点径 8dp、叠距 9dp 取自原型（我的设计决定，not in M3）。
 */
@Composable
fun DotStrip(
    bins: List<List<StripDot>>,
    ticks: List<StripTick>,
    marker: Float?,
    centered: Boolean,
    groupTip: @Composable (count: Int, titles: List<String>) -> String,
    scaleLabel: @Composable (scale: Int) -> String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val axis = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val markerColor = if (centered) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
    val scale = dotScaleOf(bins.maxOfOrNull { it.size } ?: 0, centered)
    val stacks = if (scale == 1) {
        bins
    } else {
        bins.map { bin ->
            // 同色的排在一起再按比例合并，合出来的点尽量是一种颜色
            bin.groupBy { it.color }.values.flatten().chunked(scale).map { group ->
                StripDot(
                    x = group.first().x,
                    color = group.groupingBy { it.color }.eachCount().maxBy { it.value }.key,
                    title = "",
                    tip = groupTip(group.size, group.map { it.title }),
                )
            }
        }
    }
    val key = if (scale == 1) null else scaleLabel(scale)
    val tallest = stacks.maxOfOrNull { it.size } ?: 0
    // 有比例时顶上多留一行写它，不压着最高那摞
    val keyRow = if (key == null) 0.dp else textBand(axis, DOT_STEP)
    val axisDp = textBand(axis, DOT_AXIS_HEIGHT)
    val height = DOT_STEP * max(stackRowsOf(tallest, centered), MIN_STACK_ROWS) + axisDp + DOT_STEP + keyRow
    TapChart(description, modifier.fillMaxWidth().height(height)) {
        val inset = DOT_INSET.toPx()
        val step = DOT_STEP.toPx()
        val radius = DOT_RADIUS.toPx()
        val base = heightPx - axisDp.toPx()
        val middle = base / 2
        fun xOf(at: Float) = inset + (widthPx - 2 * inset) * at
        val placed = stacks.flatMap { bin ->
            bin.mapIndexed { k, dot ->
                val y = if (centered) {
                    middle - (if (k % 2 == 1) 1 else -1) * ceil(k / 2f) * step
                } else {
                    base - radius - 2.dp.toPx() - k * step
                }
                dot to Offset(xOf(dot.x), y)
            }
        }
        ChartLayout(
            marks = placed.map { (dot, at) ->
                ChartMark(anchor = Rect(at, radius), label = dot.tip, hit = Rect(at, step / 2)) { drawCircle(dot.color, radius, at) }
            },
            backdrop = {
                if (marker != null) {
                    drawLine(markerColor, Offset(xOf(marker), 4.dp.toPx()), Offset(xOf(marker), base), (if (centered) 1f else 1.5f).dp.toPx())
                }
                ticks.forEach { label(it.label, xOf(it.at), heightPx - 2.dp.toPx(), axis, muted) }
            },
            foreground = {
                if (key != null) {
                    val keyY = keyRow.toPx() / 2
                    drawCircle(muted, radius, Offset(radius, keyY))
                    label(key, radius * 2 + 4.dp.toPx(), keyY + axis.fontSize.toPx() / 3, axis, muted, ha = 0f)
                }
            },
        )
    }
}

private val DOT_RADIUS = 4.dp
private val DOT_STEP = 9.dp
private val DOT_INSET = 14.dp
private val DOT_AXIS_HEIGHT = 18.dp
private const val MIN_STACK_ROWS = 5

/** 点条一摞最多几行：数再多，点条也只有这么高（我的设计决定，not in M3）。 */
internal const val DOT_MAX_ROWS = 24

/** 一摞 [dots] 个点占几行：[centered] 时以中线为准上下交替叠，上下留一样多。 */
internal fun stackRowsOf(dots: Int, centered: Boolean): Int = if (centered) (dots + 1) / 2 * 2 + 1 else dots

/** 点条一个点代表几个：1、2、5、10、20、50…… 里最小的、让最高那摞（[tallest] 个）不超过 [DOT_MAX_ROWS] 行的那个。 */
internal fun dotScaleOf(tallest: Int, centered: Boolean): Int =
    generateSequence(1) { if (it.toString().first() == '2') it / 2 * 5 else it * 2 }
        .first { stackRowsOf((tallest + it - 1) / it, centered) <= DOT_MAX_ROWS }
