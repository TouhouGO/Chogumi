package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.ui.composables.colors
import kotlin.math.roundToInt

/** 单位格的一组：[cells] 格，取 [type] 的收藏状态色；[tip] 是这一组每一格的气泡字。 */
class UnitGroup(val type: CollectionType, val cells: Int, val tip: String)

/**
 * 单位格：一格一个单位，按收藏状态上色，按 [groups] 的顺序从左上起逐行排，每行 [columns] 格。
 * 格子是正方形、一行填满给它的宽，高度随行数；10 列放 100 格就是百分格。
 * 放进有界高度时只画放得下的整行（[visibleUnitRows]），不把内容居中后顶出上沿，也不画到自己的测量范围外。
 * 格间 2dp、格角取 shapes.extraSmall，是我的设计决定（not in M3）。
 */
@Composable
fun UnitCells(groups: List<UnitGroup>, columns: Int, description: String, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    val colors = groups.map { it.type.colors().color }
    val total = groups.sumOf { it.cells }
    val rows = (total + columns - 1) / columns
    val sized = modifier.fillMaxWidth().layout { measurable, constraints ->
        val width = constraints.maxWidth
        val gap = UNIT_GAP.toPx()
        val cell = (width - gap * (columns - 1)) / columns
        val height = (rows * cell + (rows - 1).coerceAtLeast(0) * gap).roundToInt()
        val clamped = constraints.constrainHeight(height)
        val placeable = measurable.measure(Constraints.fixed(width, clamped))
        layout(width, clamped) { placeable.place(0, 0) }
    }
    TapChart(description, sized) {
        val gap = UNIT_GAP.toPx()
        val cell = (widthPx - gap * (columns - 1)) / columns
        val step = cell + gap
        val visibleRows = visibleUnitRows(heightPx, cell, gap)
        val placed = buildList {
            var at = 0
            groups.forEachIndexed { index, group ->
                repeat(group.cells) {
                    if (at / columns < visibleRows) add(index to Offset((at % columns) * step, (at / columns) * step))
                    at++
                }
            }
        }
        ChartLayout(
            marks = placed.map { (index, origin) ->
                ChartMark(Rect(origin, Size(cell, cell)), groups[index].tip)
            },
            draw = {
                val outline = shape.createOutline(Size(cell, cell), layoutDirection, this)
                placed.forEach { (index, origin) ->
                    translate(origin.x, origin.y) { drawOutline(outline, colors[index]) }
                }
            },
        )
    }
}

private val UNIT_GAP = 2.dp

/** 高 [heightPx] 的画布里放得下几整行（格边长 [cell]、行距 [gap]，像素）；取整留 1px 的误差。 */
internal fun visibleUnitRows(heightPx: Float, cell: Float, gap: Float): Int = ((heightPx + gap + 1f) / (cell + gap)).toInt()

/**
 * 百分比格取整（华夫格 10×10）：**最大余数法**，合计恰好 100。
 *
 * 整数部分先落地，余下的格子按小数部分从大到小补给「计数 > 0」的项——
 * 因此 `count > 0` 但占比不到 0.5% 的项可能分到 0 格（原型明说这是允许的）。
 * 全 0 输入返回全 0，由调用方决定怎么显示空态。
 */
fun percentCells(counts: List<Int>): List<Int> {
    val total = counts.sum()
    if (total <= 0) return List(counts.size) { 0 }
    val exact = counts.map { it * 100.0 / total }
    val cells = exact.map { kotlin.math.floor(it).toInt() }.toMutableList()
    var left = 100 - cells.sum()
    exact.withIndex()
        .sortedByDescending { (_, value) -> value - kotlin.math.floor(value) }
        .forEach { (index, _) ->
            if (left > 0 && counts[index] > 0) {
                cells[index]++
                left--
            }
        }
    return cells
}

/** 华夫格图例里的占比：分到格子是整数百分比；没有是 0；有数却不足一格是 null（写作「<1%」）。 */
fun shownPercentOf(cells: Int, count: Int): Int? = when {
    cells > 0 -> cells
    count > 0 -> null
    else -> 0
}
