package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.WatchSpan
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 泳道：一行一部看完的作品，一条浅色带从标第一集那天到标最后一集那天，带上的点是标过的那几天（点大小随那天的集数），
 * 行尾写跨了几天（[lengthLabels]，与 [spans] 一一对应）。横轴从最早的一天到最晚的一天，按月标刻度（[monthLabel]，最多 [SPAN_TICKS] 个）。
 * 整行都能点，气泡字是 [tips]。
 */
@Composable
fun SpanLanes(
    spans: List<WatchSpan>,
    tips: List<String>,
    lengthLabels: List<String>,
    monthLabel: (LocalDate) -> String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val band = MaterialTheme.colorScheme.secondaryContainer
    val dot = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val first = spans.minOf { it.start }
    val last = spans.maxOf { it.end }
    val ticks = monthTicks(first, last)
    val rowDp = textBand(axis, SPAN_ROW_HEIGHT)
    val axisDp = textBand(axis, SPAN_AXIS_HEIGHT)
    TapChart(description, modifier.fillMaxWidth().height(rowDp * spans.size + axisDp)) {
        val rowHeight = rowDp.toPx()
        val track = widthPx - max(SPAN_LENGTH_WIDTH.toPx(), lengthLabels.maxOfOrNull { textWidth(it, axis) } ?: 0f)
        val days = (last.toEpochDay() - first.toEpochDay() + 1).toFloat()
        fun xOf(date: LocalDate) = track * ((date.toEpochDay() - first.toEpochDay()) + 0.5f) / days
        val lanes = spans.size * rowHeight
        ChartLayout(
            marks = spans.mapIndexed { i, span ->
                val y = i * rowHeight
                ChartMark(
                    Rect(0f, y, widthPx, y + rowHeight),
                    tips[i],
                    anchor = Rect(xOf(span.start), y + rowHeight / 2 - SPAN_BAND.toPx() / 2, xOf(span.end), y + rowHeight / 2 + SPAN_BAND.toPx() / 2),
                )
            },
            draw = {
                val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                ticks.forEach { month ->
                    val x = track * (month.toEpochDay() - first.toEpochDay()) / days
                    drawLine(line, Offset(x, 0f), Offset(x, lanes), 1.dp.toPx(), pathEffect = dash)
                    label(monthLabel(month), x + 2.dp.toPx(), heightPx - 4.dp.toPx(), axis, muted, ha = 0f)
                }
                spans.forEachIndexed { i, span ->
                    val y = i * rowHeight + rowHeight / 2
                    val thickness = SPAN_BAND.toPx()
                    val left = xOf(span.start) - thickness / 2
                    val right = max(xOf(span.end) + thickness / 2, left + thickness)
                    drawRoundRect(band, Offset(left, y - thickness / 2), Size(right - left, thickness), CornerRadius(thickness / 2))
                    span.days.forEach { (date, episodes) ->
                        drawCircle(dot, min(SPAN_DOT_MAX.toPx(), (2.5f + 0.7f * episodes).dp.toPx()), Offset(xOf(date), y))
                    }
                    label(lengthLabels[i], widthPx, y + axis.fontSize.toPx() / 3, axis, muted, ha = 1f)
                }
            },
        )
    }
}

/** 泳道横轴的月份刻度：[first] 到 [last] 之间的每月一日，多于 [SPAN_TICKS] 个时隔几个月取一个。 */
internal fun monthTicks(first: LocalDate, last: LocalDate): List<LocalDate> {
    val months = generateSequence(first.withDayOfMonth(1).plusMonths(1)) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.toList()
    val step = ceil(months.size / SPAN_TICKS.toDouble()).toInt().coerceAtLeast(1)
    return months.filterIndexed { i, _ -> i % step == 0 }
}

/** 泳道一行高、带粗、点的最大半径、行尾天数宽、月份轴高、刻度最多几个（我的设计决定，not in M3）。 */
private val SPAN_ROW_HEIGHT = 26.dp
private val SPAN_BAND = 10.dp
private val SPAN_DOT_MAX = 7.dp
private val SPAN_LENGTH_WIDTH = 44.dp
private val SPAN_AXIS_HEIGHT = 18.dp
private const val SPAN_TICKS = 4
