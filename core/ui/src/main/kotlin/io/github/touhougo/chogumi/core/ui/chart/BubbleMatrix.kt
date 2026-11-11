package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 气泡矩阵的一格：第 [row] 行、第 [column] 列，[size] 是这一格的数（气泡面积随它），[tip] 是气泡字。 */
class BubbleCell(val row: Int, val column: Int, val size: Int, val tip: String)

/**
 * 气泡矩阵：一行一个 [rows] 标签，一列一个 [columns] 标签，气泡面积随 [BubbleCell.size]；
 * 行越靠后颜色越深（[ordinalTones]）。列标签放不下时隔一个写一个，从最后一列起数。
 */
@Composable
fun BubbleMatrix(rows: List<String>, columns: List<String>, cells: List<BubbleCell>, description: String, modifier: Modifier = Modifier) {
    val axis = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val tones = ordinalTones(rows.size)
    val rowDp = textBand(axis, BUBBLE_ROW_HEIGHT)
    val axisDp = textBand(axis, BUBBLE_AXIS_HEIGHT)
    TapChart(description, modifier.fillMaxWidth().height(rowDp * rows.size + axisDp)) {
        // 行名按字宽留位（至少 52dp，最多占三分之一宽），放不下的才在句尾省略
        val labelWidth = max(BUBBLE_LABEL_WIDTH.toPx(), min((rows.maxOfOrNull { textWidth(it, axis) } ?: 0f) + 4.dp.toPx(), widthPx / 3))
        val rowHeight = rowDp.toPx()
        val columnWidth = (widthPx - labelWidth) / columns.size.coerceAtLeast(1)
        val most = cells.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val largest = min(columnWidth, rowHeight) / 2 - 4.dp.toPx()
        val bubbles = cells.map { cell ->
            val radius = 3.dp.toPx() + sqrt(cell.size.toFloat() / most) * max(0f, largest - 3.dp.toPx())
            Offset(labelWidth + cell.column * columnWidth + columnWidth / 2, cell.row * rowHeight + rowHeight / 2) to radius
        }
        val everyOther = columnWidth < (columns.maxOfOrNull { textWidth(it, axis) } ?: 0f) + 4.dp.toPx()
        ChartLayout(
            marks = bubbles.mapIndexed { i, (center, radius) ->
                ChartMark(
                    anchor = Rect(center, radius),
                    label = cells[i].tip,
                    hit = Rect(center.x - columnWidth / 2, center.y - rowHeight / 2, center.x + columnWidth / 2, center.y + rowHeight / 2),
                ) { drawCircle(tones[cells[i].row], radius, center) }
            },
            backdrop = {
                rows.forEachIndexed { row, name ->
                    val y = row * rowHeight + rowHeight / 2
                    label(name, 0f, y + axis.fontSize.toPx() / 3, axis, muted, ha = 0f, maxWidthPx = labelWidth)
                    drawLine(line, Offset(labelWidth, y), Offset(widthPx, y), 1.dp.toPx())
                }
                columns.forEachIndexed { column, name ->
                    if (!everyOther || (columns.size - 1 - column) % 2 == 0) {
                        label(name, labelWidth + column * columnWidth + columnWidth / 2, heightPx - 4.dp.toPx(), axis, muted)
                    }
                }
            },
        )
    }
}

/** 一行高、左侧行标签最小宽、列标签轴高（都不低于字阶的行高 / 字宽；我的设计决定，not in M3）。 */
private val BUBBLE_ROW_HEIGHT = 40.dp
private val BUBBLE_LABEL_WIDTH = 52.dp
private val BUBBLE_AXIS_HEIGHT = 18.dp
