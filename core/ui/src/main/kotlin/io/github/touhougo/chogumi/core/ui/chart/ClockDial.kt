package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * 24 小时钟面：一小时一根放射柱，长度随集数，零点在正上方、顺时针走；最多的一小时 primary，其余 secondaryContainer
 * （与 TenBars 同一对颜色）。圈外标 0、6、12、18 点，圈心写最常看的那个钟点 [peakLabel]。[tips] 一小时一条。
 */
@Composable
fun ClockDial(hours: List<Int>, tips: List<String>, peakLabel: String, description: String, modifier: Modifier = Modifier) {
    require(hours.size == 24 && tips.size == 24) { "钟面要 24 个数与 24 条气泡字" }
    val peakColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.secondaryContainer
    val ring = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val center = MaterialTheme.typography.headlineSmall
    val onSurface = MaterialTheme.colorScheme.onSurface
    val labelBand = textBand(axis, CLOCK_LABEL_BAND)
    TapChart(description, modifier.fillMaxWidth().height(CLOCK_SIZE)) {
        val size = min(widthPx, heightPx)
        val c = Offset(widthPx / 2, heightPx / 2)
        val outer = size / 2 - labelBand.toPx()
        val inner = outer * CLOCK_HOLE
        val most = hours.max().coerceAtLeast(1)
        val peak = hours.indexOf(hours.max())
        val gap = CLOCK_GAP_DEGREES
        fun at(radius: Float, degrees: Float) = Offset(
            c.x + radius * kotlin.math.cos(Math.toRadians(degrees.toDouble())).toFloat(),
            c.y + radius * kotlin.math.sin(Math.toRadians(degrees.toDouble())).toFloat(),
        )
        val wedges = hours.mapIndexed { h, n ->
            val start = h * 15f - 90f + gap / 2
            val reach = if (n > 0) inner + (outer - inner) * n / most else inner + 2.dp.toPx()
            Triple(start, reach, Path().apply {
                arcTo(Rect(c, reach), start, 15f - gap, forceMoveTo = true)
                arcTo(Rect(c, inner), start + 15f - gap, -(15f - gap), forceMoveTo = false)
                close()
            })
        }
        val middle = (inner + outer) / 2
        val side = (2 * Math.PI * middle / 24).toFloat()
        ChartLayout(
            // 放射柱不是矩形：气泡指着柱的外端，算作点中它的是柱所在那一圈上的一个方块
            marks = wedges.mapIndexed { h, (start, reach, path) ->
                val mid = start + (15f - gap) / 2
                ChartMark(anchor = Rect(at(reach, mid), 1f), label = tips[h], hit = Rect(at(middle, mid), side / 2)) {
                    drawPath(path, if (h == peak && hours[h] > 0) peakColor else restColor)
                }
            },
            backdrop = {
                drawCircle(ring, outer, c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))))
            },
            foreground = {
                listOf(0, 6, 12, 18).forEach { h ->
                    val p = at(outer + labelBand.toPx() / 2, h * 15f - 90f)
                    label("$h", p.x, p.y + axis.fontSize.toPx() / 3, axis, muted)
                }
                label(peakLabel, c.x, c.y + center.fontSize.toPx() / 3, center, onSurface)
            },
        )
    }
}

/** 钟面边长、圈外刻度字的一圈宽、圈心空洞占半径的比例、相邻两小时之间的缝（我的设计决定，not in M3）。 */
private val CLOCK_SIZE = 240.dp
private val CLOCK_LABEL_BAND = 18.dp
private const val CLOCK_HOLE = 0.4f
private const val CLOCK_GAP_DEGREES = 1.5f
