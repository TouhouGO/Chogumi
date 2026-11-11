package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 分数尺上的一个点，三种角色：
 * - [own] 为 true 是「你」这一方的均分：实心 primary，字写在尺上方；
 * - 给了 [color] 的是一组人（某个收藏状态）的均分：实心、取这一组的颜色，字也是这个颜色，写在尺下方；
 * - 其余是对照（大众、全站）：空心 outline，字写在尺下方。
 * 「你」与其他角色的字分在尺的两侧；尺下方的字彼此让位，最多排两行。拥挤时省略放不下的字，完整文字仍可点按和读屏获取。
 */
class ScoreMark(val label: String, val score: Double, val own: Boolean, val color: Color = Color.Unspecified, val tip: String = label)

/** 尺下方的字最多排几行。 */
private const val RULER_LABEL_ROWS = 2

/**
 * 分数尺：一条 1–10 的尺，标出几个均分落在哪（生涯的「我的均分对大众」、条目的「各收藏状态的均分和你的分」）。
 * 几何是我的设计决定（not in M3）：尺在 32dp、两端各留 20dp 写「1」「10」，整数处一道刻度；没有分组的点时总高 64dp，
 * 有分组的点时尺下方留两行字高。
 */
@Composable
fun ScoreRuler(marks: List<ScoreMark>, description: String, modifier: Modifier = Modifier) {
    val progress = chartFractions()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val ownColor = MaterialTheme.colorScheme.primary
    val referenceColor = MaterialTheme.colorScheme.outline
    val halo = MaterialTheme.colorScheme.surfaceContainer
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val scale = MaterialTheme.typography.labelSmall
    val ownStyle = MaterialTheme.typography.labelMediumEmphasized
    val referenceStyle = MaterialTheme.typography.labelMedium
    val grouped = marks.any { !it.own && it.color.isSpecified }
    val height = if (grouped) {
        with(LocalDensity.current) { RULER_AXIS_Y + RULER_BELOW_GAP + referenceStyle.lineHeight.toDp() * RULER_LABEL_ROWS }
    } else {
        RULER_HEIGHT
    }
    TapChart(description, modifier.fillMaxWidth().height(height)) {
        val axisY = RULER_AXIS_Y.toPx()
        val left = 20.dp.toPx()
        val right = widthPx - 20.dp.toPx()
        fun xOf(score: Double) = left + ((score.coerceIn(1.0, 10.0) - 1) / 9).toFloat() * (right - left)
        val below = marks.filter { !it.own }
        val belowLeft = rulerLabelLefts(
            centers = below.map { xOf(it.score) },
            widths = below.map { textWidth(it.label, referenceStyle) },
            canvasWidth = widthPx,
            gap = 8.dp.toPx(),
        )
        val lineHeight = referenceStyle.lineHeight.toPx()
        val firstBaseline = axisY + RULER_BELOW_GAP.toPx() + lineHeight * 0.75f
        // 整把尺是一个标记：点哪里都给出全部均分
        ChartLayout(
            listOf(
                ChartMark(Rect(0f, 0f, widthPx, heightPx), marks.joinToString("\n") { it.tip }, multiline = true) {
                    drawLine(axisColor, Offset(left, axisY), Offset(right, axisY), 2.dp.toPx(), StrokeCap.Round)
                    (1..10).forEach { score ->
                        val x = xOf(score.toDouble())
                        drawLine(axisColor, Offset(x, axisY - 4.dp.toPx()), Offset(x, axisY + 4.dp.toPx()), 1.dp.toPx())
                    }
                    label("1", 0f, axisY + 4.dp.toPx(), scale, muted, ha = 0f)
                    label("10", widthPx, axisY + 4.dp.toPx(), scale, muted, ha = 1f)
                    below.forEachIndexed { i, mark ->
                        val center = Offset(xOf(1.0 + (mark.score - 1.0) * progress.chartFraction(i)), axisY)
                        drawCircle(halo, 8.dp.toPx(), center)
                        if (mark.color.isSpecified) {
                            drawCircle(mark.color, 6.dp.toPx(), center)
                        } else {
                            drawCircle(referenceColor, 5.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                        }
                        belowLeft[i]?.let { (row, labelLeft) ->
                            label(
                                mark.label,
                                labelLeft,
                                firstBaseline + row * lineHeight,
                                referenceStyle,
                                if (mark.color.isSpecified) mark.color else muted,
                                ha = 0f,
                            )
                        }
                    }
                    marks.filter { it.own }.forEachIndexed { i, mark ->
                        val center = Offset(xOf(1.0 + (mark.score - 1.0) * progress.chartFraction(i)), axisY)
                        drawCircle(halo, 8.dp.toPx(), center)
                        drawCircle(ownColor, 6.dp.toPx(), center)
                        label(mark.label, center.x, axisY - 14.dp.toPx(), ownStyle, ink)
                    }
                },
            ),
        )
    }
}

/**
 * 尺下方各条字的位置与可见性（行, 左边 x；null 为不画）：按点的位置从左到右排，先居中、贴边时挪回画布，
 * 本行隔不开 [gap] 就换下一行；两行都挤时找能完整接在末尾的一行，没有余量就省略。比画布宽的字也省略。
 * 结果与 [centers] 一一对应；完整文字留在图表气泡中。
 */
internal fun rulerLabelLefts(centers: List<Float>, widths: List<Float>, canvasWidth: Float, gap: Float): List<Pair<Int, Float>?> {
    val rowEnds = FloatArray(RULER_LABEL_ROWS) { Float.NEGATIVE_INFINITY }
    val placed = arrayOfNulls<Pair<Int, Float>>(centers.size)
    centers.indices.sortedBy { centers[it] }.forEach { i ->
        val width = widths[i]
        if (width > canvasWidth) return@forEach
        val natural = (centers[i] - width / 2).coerceIn(0f, max(0f, canvasWidth - width))
        val row = rowEnds.indices.firstOrNull { natural >= rowEnds[it] + gap }
        val spot = if (row != null) {
            row to natural
        } else {
            val emptiest = rowEnds.indices.minBy { rowEnds[it] }
            val left = rowEnds[emptiest] + gap
            if (left + width > canvasWidth) return@forEach
            emptiest to left
        }
        rowEnds[spot.first] = spot.second + width
        placed[i] = spot
    }
    return placed.toList()
}

private val RULER_HEIGHT = 64.dp
private val RULER_AXIS_Y = 32.dp

/** 尺与尺下第一行字之间的距离。 */
private val RULER_BELOW_GAP = 10.dp
