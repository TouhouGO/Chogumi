package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** 树图的一块：面积 = [count]，深浅 = 第 [tone] 档（共 `toneCount` 档，没有档为 null）。 */
class TreemapTile(val label: String, val count: Int, val tone: Int?, val tip: String)

/**
 * 方块树图（squarify）：面积是 [TreemapTile.count]，深浅分 [toneCount] 档（ordinalTones），没有档的块取 surfaceContainerHighest。
 * 块够大才写名字与数，档色块上的字色取 inkOn。[tiles] 从大到小排好。
 */
@Composable
fun Treemap(tiles: List<TreemapTile>, toneCount: Int, description: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tones = ordinalTones(toneCount)
    val inks = tones.map(::inkOn)
    val nameLarge = MaterialTheme.typography.titleSmall
    val nameSmall = MaterialTheme.typography.labelMedium
    val countStyle = MaterialTheme.typography.labelSmall
    val shape = MaterialTheme.shapes.small
    TapChart(description, modifier.fillMaxWidth().height(TREEMAP_HEIGHT)) {
        val rects = squarify(tiles.map { it.count.toFloat() }, widthPx, heightPx)
        val gap = 1.dp.toPx()
        ChartLayout(
            rects.mapIndexed { i, tileRect ->
                val tile = tiles[i]
                ChartMark(tileRect, tile.tip) { r ->
                    val tone = tile.tone
                    val fill = if (tone == null) scheme.surfaceContainerHighest else tones[tone]
                    val ink = if (tone == null) scheme.onSurface else inks[tone]
                    val inner = Rect(r.left + gap, r.top + gap, r.right - gap, r.bottom - gap)
                    drawRoundRect(fill, inner.topLeft, inner.size, CornerRadius(shape.topStart.toPx(r.size, this)))
                    if (inner.width > 44.dp.toPx()) {
                        val left = inner.left + 10.dp.toPx()
                        val room = inner.width - 20.dp.toPx()
                        // 两行字的位置由字阶的行高推出来（大字号下行高跟着变），哪一行放不下就不画哪一行
                        val nameStyle = if (inner.width > 90.dp.toPx()) nameLarge else nameSmall
                        val descent = TEXT_DESCENT.toPx()
                        val nameBaseline = inner.top + TEXT_INSET.toPx() + nameStyle.lineHeight.toPx()
                        val countBaseline = nameBaseline + TEXT_INSET.toPx() + countStyle.lineHeight.toPx()
                        if (nameBaseline + descent <= inner.bottom) {
                            label(tile.label, left, nameBaseline, nameStyle, ink, ha = 0f, maxWidthPx = room)
                        }
                        if (countBaseline + descent <= inner.bottom) {
                            label("${tile.count}", left, countBaseline, countStyle, ink, ha = 0f, maxWidthPx = room)
                        }
                    }
                }
            },
        )
    }
}

private val TREEMAP_HEIGHT = 200.dp

/** 块内文字离块顶、两行之间的留白，以及基线下方给字的下伸部分留的高度（我的设计决定，not in M3）。 */
private val TEXT_INSET = 2.dp
private val TEXT_DESCENT = 4.dp

/**
 * 方块树图的排法（squarify：按行铺，每行取让最差长宽比最小的那几块）。[weights] 从大到小排好；返回与之一一对应的矩形，
 * 合起来正好铺满 [width] × [height]。
 */
internal fun squarify(weights: List<Float>, width: Float, height: Float): List<Rect> {
    val total = weights.sum()
    if (total <= 0f || weights.isEmpty()) return weights.map { Rect.Zero }
    val scale = width * height / total
    val areas = weights.map { it * scale }
    val out = ArrayList<Rect>(areas.size)
    var x = 0f
    var y = 0f
    var w = width
    var h = height
    var start = 0
    while (start < areas.size) {
        val short = min(w, h)
        var end = start + 1
        var best = worstRatio(areas.subList(start, end), short)
        while (end < areas.size) {
            val next = worstRatio(areas.subList(start, end + 1), short)
            if (next > best) break
            best = next
            end++
        }
        val row = areas.subList(start, end)
        val thick = row.sum() / short
        var offset = 0f
        row.forEach { area ->
            val length = area / thick
            out += if (w >= h) Rect(x, y + offset, x + thick, y + offset + length) else Rect(x + offset, y, x + offset + length, y + thick)
            offset += length
        }
        if (w >= h) {
            x += thick
            w -= thick
        } else {
            y += thick
            h -= thick
        }
        start = end
    }
    return out
}

private fun worstRatio(row: List<Float>, short: Float): Float {
    val sum = row.sum()
    return max(short * short * row.max() / (sum * sum), sum * sum / (short * short * row.min()))
}
