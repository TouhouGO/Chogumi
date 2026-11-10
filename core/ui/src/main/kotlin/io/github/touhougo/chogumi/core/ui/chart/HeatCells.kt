package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.min

/**
 * 热度格的一格：[level] 是深浅档（0 是没有，1–[HEAT_LEVELS] 由浅到深，分档由调用方的数据决定），[tip] 是点按的气泡字。
 * [pending] 的格子是还没到的（今天以后的日子）：只画轮廓，不能点。[label] 是写在格子里的短字（集数），格子放不下就不写；
 * [marked] 的格子下面挂一道 primary 短横（你看过这一集）。
 */
class HeatCell(
    val level: Int,
    val tip: String,
    val pending: Boolean = false,
    val label: String? = null,
    val marked: Boolean = false,
)

/** 热度格有数的格子分几档。 */
private const val HEAT_LEVELS = 4

/** 按占最大值的比例分档：0 是 0 档，其余向上取整到 1–[HEAT_LEVELS] 档（最大的一格总在最深一档）。 */
fun heatLevelOf(count: Int, peak: Int): Int =
    if (count <= 0 || peak <= 0) 0 else ceil(count.toDouble() * HEAT_LEVELS / peak).toInt().coerceIn(1, HEAT_LEVELS)

/**
 * 热度格：一行一行等宽的方格，行可以不一样长（一个月 28–31 天），每行左边一个短标签（月份、星期；都为空就不留标签列）。
 * 0 档取 surfaceContainerHighest，有数的取 [ordinalTones]（[HEAT_LEVELS] 档），格子里的字色取 [inkOn]，0 档同行标签。确切的数点按看。
 * 生涯的年轮（月 × 日）用它，条目的每集讨论热度也用它（格子里写集数，看过的集下面挂一道横）。
 *
 * 几何是我的设计决定（not in M3）：格子边长按宽度均分 [columns] 列，最大 [maxCell]；格间 2dp，圆角取边长的四分之一；
 * 有挂横的格子时每行下面多留 5dp，横高 3dp。
 */
@Composable
fun HeatCells(
    rows: List<List<HeatCell>>,
    rowLabels: List<String>,
    columns: Int,
    description: String,
    modifier: Modifier = Modifier,
    maxCell: Dp = HEAT_CELL_MAX,
) {
    require(rowLabels.size == rows.size) { "每行一个标签" }
    val progress = chartFractions()
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val tones = ordinalTones(HEAT_LEVELS)
    val inks = tones.map(::inkOn)
    val outline = MaterialTheme.colorScheme.outlineVariant
    val markColor = MaterialTheme.colorScheme.primary
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelSizes = remember(rowLabels, labelStyle) {
        rowLabels.map { measurer.measure(it, labelStyle, maxLines = 1, softWrap = false).size }
    }
    val hasMarks = rows.any { row -> row.any { it.marked } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val labelGap = with(density) { HEAT_LABEL_GAP.toPx() }
        val markBand = if (hasMarks) with(density) { HEAT_MARK_BAND.toPx() } else 0f
        // 标签占的行高不能超过它们之间隔的行高：大字号下放不下就隔着少写几个（先按全部标签的宽度估一行多高，留下的标签只会更少更窄，格子只会更大）
        val fullWidth = if (rowLabels.all { it.isEmpty() }) 0f else labelSizes.maxOf { it.width } + labelGap
        val estimatedPitch = min(with(density) { maxCell.toPx() }, (widthPx - fullWidth) / columns) + markBand
        val shown = visibleRowLabels(rowLabels, (labelSizes.maxOfOrNull { it.height } ?: 0).toFloat(), estimatedPitch)
        val labelWidth = if (shown.all { it.isEmpty() }) 0f else shown.indices.maxOf { if (shown[it].isEmpty()) 0 else labelSizes[it].width } + labelGap
        val cell = min(with(density) { maxCell.toPx() }, (widthPx - labelWidth) / columns)
        val pitch = cell + markBand
        val height = with(density) { (pitch * rows.size).toDp() }
        TapChart(description, Modifier.fillMaxWidth().height(height)) {
            val gap = HEAT_CELL_GAP.toPx()
            val side = cell - gap
            val corner = CornerRadius(side / 4)
            val stroke = Stroke(1.dp.toPx())
            val markHeight = HEAT_MARK_HEIGHT.toPx()
            fun rectOf(row: Int, col: Int) = Rect(Offset(labelWidth + col * cell + gap / 2, row * pitch + gap / 2), Size(side, side))
            ChartLayout(
                marks = rows.flatMapIndexed { r, cells ->
                    cells.mapIndexedNotNull { c, heat -> if (heat.pending) null else ChartMark(rectOf(r, c), heat.tip) }
                },
                draw = {
                    rows.forEachIndexed { r, cells ->
                        label(shown[r], 0f, r * pitch + cell / 2 + labelStyle.fontSize.toPx() / 3, labelStyle, labelColor, ha = 0f)
                        cells.forEachIndexed { c, heat ->
                            val rect = rectOf(r, c)
                            val level = heat.level.coerceIn(0, HEAT_LEVELS)
                            if (heat.pending) {
                                drawRoundRect(outline, rect.topLeft, rect.size, corner, style = stroke)
                            } else {
                                val growth = progress.chartFraction(r * columns + c)
                                val grown = Size(rect.width * growth, rect.height * growth)
                                drawRoundRect(if (level == 0) empty else tones[level - 1], rect.center - Offset(grown.width / 2, grown.height / 2), grown, corner)
                            }
                            heat.label?.takeIf { textWidth(it, labelStyle) <= side - 2.dp.toPx() }?.let { text ->
                                val ink = if (heat.pending || level == 0) labelColor else inks[level - 1]
                                label(text, rect.center.x, rect.center.y + labelStyle.fontSize.toPx() / 3, labelStyle, ink)
                            }
                            if (heat.marked) {
                                val markWidth = side * 0.6f
                                drawRoundRect(
                                    markColor,
                                    Offset(rect.center.x - markWidth / 2, rect.bottom + gap),
                                    Size(markWidth, markHeight),
                                    CornerRadius(markHeight / 2),
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * 行标签按行高疏化（唯一判定点）：[labelHeight] 是一个标签的高，[pitch] 是一行的高；标签画在行中线上，
 * 相邻两个写出来的标签中线至少隔一个标签高，隔不够的后一个不写（写出来会压在前一个上）。空标签原样留空。
 */
internal fun visibleRowLabels(labels: List<String>, labelHeight: Float, pitch: Float): List<String> {
    var nextFree = Float.NEGATIVE_INFINITY
    return labels.mapIndexed { row, label ->
        val centre = row * pitch
        if (label.isEmpty() || centre < nextFree) {
            ""
        } else {
            nextFree = centre + labelHeight
            label
        }
    }
}

private val HEAT_CELL_MAX = 14.dp
private val HEAT_CELL_GAP = 2.dp
private val HEAT_LABEL_GAP = 6.dp
private val HEAT_MARK_BAND = 5.dp
private val HEAT_MARK_HEIGHT = 3.dp
