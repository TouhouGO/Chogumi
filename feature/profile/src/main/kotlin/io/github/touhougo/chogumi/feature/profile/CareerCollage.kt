package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 拼图里一张封面的框：左上角与宽高，与卡片同一个坐标（左上为原点，单位由调用方定，通常是 dp）。 */
internal data class CollageCell(val x: Float, val y: Float, val width: Float, val height: Float)

/** 一张拼图：[full] 时封面铺满整张卡，否则只占一部分，余下的地方露出底图。 */
internal data class Collage(val cells: List<CollageCell>, val full: Boolean)

/**
 * 生涯卡封面拼图的排法（唯一实现）。封面一律 3:4，拼图里没有空洞。
 *
 * - 封面够铺满（[count] ≥ [collageCapacity]）：整张卡排成网格，第一张占 2×2；最后一行可能伸出卡片下沿，由卡片裁掉。
 * - 不够：把 [count] 张分成 1–4 行、每行张数尽量平均，同一行的封面同高、正好排满同一宽度（张数少的行更高）。
 *   排出来比卡宽就整体按卡宽缩小，下方留给底图；比卡窄就贴右、占满高度，左边留给底图。在所有分法里取封面面积最大的。
 *
 * 封面宽高比取 [ChogumiDimens.CoverRatio]（与 SubjectPoster 同一比例）。
 * 网格每格的目标宽 [COLLAGE_CELL_TARGET]、至少 [COLLAGE_MIN_COLUMNS] 列，是我的设计决定（not in M3）。
 */
internal fun collageOf(count: Int, width: Float, height: Float, gap: Float): Collage {
    val grid = CollageGrid.of(width, height, gap)
    if (count >= grid.capacity) return Collage(grid.cells(), full = true)
    if (count <= 0) return Collage(emptyList(), full = false)
    val best = (1..min(MAX_COLLAGE_ROWS, count)).map { rows -> RowSplit.of(count, rows, width, height, gap) }.maxBy { it.area }
    return Collage(best.cells(width, gap), full = false)
}

/** 这张卡铺满要几张封面：拼图最多用这么多张（再多也排不下）。 */
internal fun collageCapacity(width: Float, height: Float, gap: Float): Int = CollageGrid.of(width, height, gap).capacity

/** 铺满时的网格：[columns] 列、[rows] 行（最后一行可能伸出卡片），每格 [cellWidth] × [cellHeight]。 */
private class CollageGrid(val columns: Int, val rows: Int, val cellWidth: Float, val cellHeight: Float, val gap: Float) {
    /** 第一张占 2×2（四格算一张）。 */
    val capacity: Int get() = columns * rows - 3

    fun cells(): List<CollageCell> {
        val hero = CollageCell(0f, 0f, cellWidth * 2 + gap, cellHeight * 2 + gap)
        val rest = (0 until rows).flatMap { row ->
            (0 until columns).filterNot { row < 2 && it < 2 }.map { column ->
                CollageCell(column * (cellWidth + gap), row * (cellHeight + gap), cellWidth, cellHeight)
            }
        }
        return listOf(hero) + rest
    }

    companion object {
        fun of(width: Float, height: Float, gap: Float): CollageGrid {
            val columns = max(COLLAGE_MIN_COLUMNS, (width / COLLAGE_CELL_TARGET).roundToInt())
            val cellWidth = (width - gap * (columns - 1)) / columns
            val cellHeight = cellWidth / ChogumiDimens.CoverRatio
            val rows = max(2, ceil((height + gap) / (cellHeight + gap)).toInt())
            return CollageGrid(columns, rows, cellWidth, cellHeight, gap)
        }
    }
}

/**
 * 不够铺满时的一种分法：[perRow] 是每行几张；按卡高排时拼图宽 [naturalWidth]，比卡宽时整体乘 [scale] 缩到卡宽。
 */
private class RowSplit(val perRow: List<Int>, val naturalWidth: Float, val scale: Float, val area: Float) {
    fun cells(width: Float, gap: Float): List<CollageCell> {
        val shownWidth = min(naturalWidth, width)
        val left = width - shownWidth
        var top = 0f
        return perRow.flatMap { k ->
            val cellWidth = (naturalWidth - gap * (k - 1)) / k * scale
            val cellHeight = cellWidth / ChogumiDimens.CoverRatio
            val row = (0 until k).map { i -> CollageCell(left + i * (cellWidth + gap * scale), top, cellWidth, cellHeight) }
            top += cellHeight + gap * scale
            row
        }
    }

    companion object {
        /**
         * 每行 k 张、拼图宽 w 时行高是 (w − 间隙 × (k − 1)) / k ÷ 3:4；各行高加行间隙等于卡高，解出 w。
         */
        fun of(count: Int, rows: Int, width: Float, height: Float, gap: Float): RowSplit {
            val perRow = List(rows) { i -> count / rows + if (i < count % rows) 1 else 0 }
            val slope = perRow.sumOf { k -> 1.0 / k / ChogumiDimens.CoverRatio }
            val rowGaps = perRow.sumOf { k -> gap * (k - 1).toDouble() / k / ChogumiDimens.CoverRatio }
            val naturalWidth = ((height - gap * (rows - 1) + rowGaps) / slope).toFloat()
            val scale = if (naturalWidth > width) width / naturalWidth else 1f
            val area = perRow.sumOf { k ->
                val cellWidth = (naturalWidth - gap * (k - 1)) / k * scale
                (k * cellWidth * cellWidth / ChogumiDimens.CoverRatio).toDouble()
            }.toFloat()
            return RowSplit(perRow, naturalWidth, scale, area)
        }
    }
}

/** 铺满时每格的目标宽与最少列数（我的设计决定，not in M3）。 */
private const val COLLAGE_CELL_TARGET = 78f
private const val COLLAGE_MIN_COLUMNS = 4

/** 不够铺满时最多分几行（再多每张就太小了）。 */
private const val MAX_COLLAGE_ROWS = 4
