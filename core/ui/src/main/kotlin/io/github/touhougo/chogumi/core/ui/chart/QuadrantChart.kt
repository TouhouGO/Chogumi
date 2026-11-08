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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** 象限图的一个点：[x] 横轴分、[y] 纵轴分（都在 1–10），[color] 是点色，[tip] 是气泡字。 */
class QuadrantPoint(val x: Double, val y: Double, val color: Color, val tip: String)

/** 象限图四角的字：右上、左上、右下、左下。 */
class QuadrantLabels(val topEnd: String, val topStart: String, val bottomEnd: String, val bottomStart: String)

/**
 * 分数象限：横轴、纵轴都是 1–10 的分，以 ([centerX], [centerY]) 画虚线十字，四角写 [labels]。
 * 四角的字在顶上和底下各占一条，点只画在两条之间。点略透，重叠处叠深。
 */
@Composable
fun QuadrantChart(
    points: List<QuadrantPoint>,
    centerX: Double,
    centerY: Double,
    labels: QuadrantLabels,
    description: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val axis = MaterialTheme.typography.labelSmall
    val corner = MaterialTheme.typography.labelMedium
    val muted = scheme.onSurfaceVariant
    val plane = scheme.surfaceContainerLow
    val cross = scheme.outline
    val xFrom = (floor(points.minOf { it.x }) - 0.5).coerceAtLeast(1.0)
    val xTo = (ceil(points.maxOf { it.x }) + 0.5).coerceAtMost(10.5)
    val yFrom = (points.minOf { it.y } - 1.0).coerceAtLeast(0.5)
    val yTo = 10.5
    val axisDp = textBand(axis, QUADRANT_AXIS_BAND)
    TapChart(description, modifier.fillMaxWidth().height(QUADRANT_HEIGHT)) {
        // 左边一列纵轴刻度按字宽留位，下面一条横轴刻度按行高留位，字号再大也不压着图
        val pad = max(QUADRANT_AXIS_BAND.toPx(), textWidth("10", axis) + 8.dp.toPx())
        val top = 0f
        val bottom = heightPx - axisDp.toPx()
        val radius = 5.dp.toPx()
        // 四角的字各占顶上和底下一条，点只落在两条之间，压不住字
        val cornerSize = corner.fontSize.toPx()
        val topBaseline = top + 6.dp.toPx() + cornerSize
        val bottomBaseline = bottom - 8.dp.toPx()
        val dotTop = topBaseline + 4.dp.toPx() + radius
        val dotBottom = bottomBaseline - cornerSize - 4.dp.toPx() - radius
        fun sx(v: Double) = pad + ((v - xFrom) / (xTo - xFrom)).toFloat() * (widthPx - pad - 6.dp.toPx())
        fun sy(v: Double) = dotBottom - ((v - yFrom) / (yTo - yFrom)).toFloat() * (dotBottom - dotTop)
        val dots = points.map { it to Offset(sx(it.x), sy(it.y)) }
        ChartLayout(
            marks = dots.mapIndexed { i, (_, at) -> ChartMark(Rect(at, 12.dp.toPx()), points[i].tip, anchor = Rect(at, radius)) },
            draw = {
                drawRoundRect(plane, Offset(pad, top), Size(widthPx - pad, bottom - top), CornerRadius(16.dp.toPx()))
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                val cx = sx(centerX)
                val cy = sy(centerY)
                drawLine(cross, Offset(cx, top + 4.dp.toPx()), Offset(cx, bottom), 1.dp.toPx(), pathEffect = dash)
                drawLine(cross, Offset(pad, cy), Offset(widthPx, cy), 1.dp.toPx(), pathEffect = dash)
                dots.forEach { (point, at) -> drawCircle(point.color, radius, at, alpha = QUADRANT_DOT_ALPHA) }
                val inset = 10.dp.toPx()
                label(labels.topEnd, widthPx - inset, topBaseline, corner, muted, ha = 1f)
                label(labels.topStart, pad + inset, topBaseline, corner, muted, ha = 0f)
                label(labels.bottomEnd, widthPx - inset, bottomBaseline, corner, muted, ha = 1f)
                label(labels.bottomStart, pad + inset, bottomBaseline, corner, muted, ha = 0f)
                (ceil(xFrom).toInt()..floor(xTo).toInt()).forEach { label("$it", sx(it.toDouble()), heightPx - 6.dp.toPx(), axis, muted) }
                (ceil(yFrom).toInt()..10).filter { it % 2 == 0 }.forEach {
                    label("$it", pad - 6.dp.toPx(), sy(it.toDouble()) + 4.dp.toPx(), axis, muted, ha = 1f)
                }
            },
        )
    }
}

/** 象限图坐标轴刻度带的最小宽 / 高（我的设计决定，not in M3）。 */
private val QUADRANT_AXIS_BAND = 24.dp

/** 象限图高（原型宽的 0.86，手机上约 256dp；我的设计决定，not in M3）。 */
private val QUADRANT_HEIGHT = 256.dp

/** 象限点略透：重叠的点叠深而不是互相盖住（我的设计决定，not in M3）。 */
private const val QUADRANT_DOT_ALPHA = 0.8f
