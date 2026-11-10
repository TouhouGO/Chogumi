package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.statistics.ControversyTier
import io.github.touhougo.chogumi.core.model.statistics.RatingControversy
import io.github.touhougo.chogumi.core.res.R
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 主色到第三色在 Oklab 中插值，是应用设计决定（not in M3），不是 M3 的分档色。 */
fun controversyColor(primary: Color, tertiary: Color, fraction: Float): Color {
    val a = primary.convert(ColorSpaces.Oklab)
    val b = tertiary.convert(ColorSpaces.Oklab)
    val f = fraction.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f,
        a.blue + (b.blue - a.blue) * f, 1f, ColorSpaces.Oklab).convert(ColorSpaces.Srgb)
}

/** 可视化的一点，提示由调用方按数据维度生成；值不在图里重算。 */
/** 图表尺寸、行距、轴边距与错开由应用的统计视觉编排决定（not in M3）；字阶和角色色来自主题。 */
data class PlotPoint(val label: String, val value: Double, val tip: String, val color: Color = Color.Unspecified, val displayValue: String? = null)

/** 横条或以均分为中心的差值条；尺寸随实际行数与字阶，均分基线两侧按差值生长。 */
@Composable
fun StatsRows(points: List<PlotPoint>, reference: Double? = null, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelSmall
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    val tertiary = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    val progress = chartFractions()
    val rowHeight = 24.dp
    TapChart(points.joinToString { it.tip }, modifier.fillMaxWidth().height(rowHeight * points.size.coerceAtLeast(1))) {
        val row = heightPx / points.size.coerceAtLeast(1)
        val labelWidth = min(widthPx * 0.35f, max(28.dp.toPx(), points.maxOfOrNull { textWidth(it.label, style) } ?: 0f))
        val valueWidth = 42.dp.toPx()
        val left = labelWidth + 8.dp.toPx()
        val right = widthPx - valueWidth - 8.dp.toPx()
        val mid = (left + right) / 2
        val span = max(0.5, points.maxOfOrNull { kotlin.math.abs(it.value - (reference ?: 0.0)) } ?: 1.0)
        ChartLayout(points.mapIndexed { i, p -> ChartMark(Rect(0f, i * row, widthPx, (i + 1) * row), p.tip) }) {
            points.forEachIndexed { i, p ->
                val y = i * row + row / 2
                label(p.label, 0f, y + style.lineHeight.toPx() / 3, style, muted, ha = 0f, maxWidthPx = labelWidth)
                val dv = p.value - (reference ?: 0.0)
                val from = if (reference == null) left else mid
                val to = from + (dv / span).toFloat() * (if (reference == null) right - left else (right - left) / 2) * progress.chartFraction(i)
                if (reference != null) drawLine(outline, Offset(mid, y - 9.dp.toPx()), Offset(mid, y + 9.dp.toPx()), 1.dp.toPx())
                val color = if (p.color != Color.Unspecified) p.color else if (reference != null && dv < 0) tertiary else if (i == 0) primary else secondary
                drawRoundRect(color, Offset(min(from, to), y - 5.dp.toPx()), Size(kotlin.math.abs(to - from), 10.dp.toPx()), CornerRadius(5.dp.toPx()))
                val value = p.displayValue ?: if (reference == null) p.value.toInt().toString() else String.format(Locale.ROOT, "%+.2f", dv)
                label(value, widthPx, y + style.lineHeight.toPx() / 3, style, muted, ha = 1f)
            }
        }
    }
}

/** 从左到右逐段画折线；点位置与线的坐标同源。 */
@Composable
fun StatsLine(points: List<PlotPoint>, reference: Double? = null, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val progress = chartFractions()
    TapChart(points.joinToString { it.tip }, modifier.fillMaxWidth().height(136.dp)) {
        val values = points.map { it.value } + listOfNotNull(reference)
        val lo = (values.minOrNull() ?: 0.0) - 0.25
        val hi = (values.maxOrNull() ?: 1.0) + 0.25
        val bottom = heightPx - 24.dp.toPx()
        fun x(i: Int) = 16.dp.toPx() + i * (widthPx - 32.dp.toPx()) / (points.size - 1).coerceAtLeast(1)
        fun y(v: Double) = 16.dp.toPx() + ((hi - v) / (hi - lo)).toFloat() * (bottom - 16.dp.toPx())
        val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.value)) else lineTo(x(i), y(p.value)) } }
        ChartLayout(points.mapIndexed { i, p -> ChartMark(Rect(x(i) - 16.dp.toPx(), 0f, x(i) + 16.dp.toPx(), heightPx), p.tip,
            Rect(x(i) - 5.dp.toPx(), y(p.value) - 5.dp.toPx(), x(i) + 5.dp.toPx(), y(p.value) + 5.dp.toPx())) }) {
            reference?.let {
                drawLine(muted, Offset(0f, y(it)), Offset(widthPx, y(it)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                label(String.format(Locale.ROOT, "%.1f", it), widthPx, y(it) - 5.dp.toPx(), style, muted, ha = 1f)
            }
            drawPath(revealedPath(path, progress.chartFraction(0)), primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            points.forEachIndexed { i, p ->
                val center = Offset(x(i), y(p.value))
                drawCircle(if (p.color == Color.Unspecified) primary else p.color, 4.5.dp.toPx() * progress.chartFraction(i), center)
                label(p.label, x(i), heightPx - 4.dp.toPx(), style, muted, maxWidthPx = widthPx / points.size.coerceAtLeast(1))
            }
        }
    }
}

/** 相同点列的色带，以全站均分为中心显出正负差值。 */
@Composable
fun StatsStrip(points: List<PlotPoint>, reference: Double, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val ink = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.labelSmall
    val effects = chartOpacity()
    TapChart(points.joinToString { it.tip }, modifier.fillMaxWidth().height(72.dp)) {
        val gap = 3.dp.toPx()
        val width = (widthPx - gap * (points.size - 1)) / points.size.coerceAtLeast(1)
        val span = max(0.3, points.maxOfOrNull { kotlin.math.abs(it.value - reference) } ?: 1.0)
        ChartLayout(points.mapIndexed { i, p -> ChartMark(Rect(i * (width + gap), 0f, i * (width + gap) + width, heightPx), p.tip) }) {
            points.forEachIndexed { i, p ->
                val f = (kotlin.math.abs(p.value - reference) / span).toFloat()
                val color = controversyColor(surface, if (p.value >= reference) primary else tertiary, 0.18f + f * 0.7f)
                val left = i * (width + gap)
                drawRoundRect(color.copy(alpha = effects), Offset(left, 0f), Size(width, heightPx), CornerRadius(6.dp.toPx()))
                label(String.format(Locale.ROOT, "%.1f", p.value), left + width / 2, 30.dp.toPx(), style, ink, maxWidthPx = width)
                label(p.label, left + width / 2, 53.dp.toPx(), style, ink, maxWidthPx = width)
            }
        }
    }
}

/** 点阵用最大余数法把分布分配到100点；每点约1%，确切人数由气泡提供。 */
@Composable
fun RatingDots(counts: List<Int>, tips: List<String>, modifier: Modifier = Modifier) {
    val total = counts.sumOf { it.toLong() }
    val raw = counts.map { it * 100.0 / total }
    val cells = raw.map { it.toInt() }.toMutableList()
    raw.indices.sortedByDescending { raw[it] - cells[it] }.take(100 - cells.sum()).forEach { cells[it]++ }
    val dots = counts.indices.reversed().flatMap { i -> List(cells[i]) { i } }
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val progress = chartFractions()
    val labelStyle = MaterialTheme.typography.labelSmall
    val dotTips = cells.mapIndexed { score, share -> stringResource(R.string.stats_dots_tip, score + 1, share) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val gridHeight = maxWidth / 20 * 5 + 24.dp
    TapChart(tips.joinToString(), Modifier.fillMaxWidth().height(gridHeight)) {
        val step = widthPx / 20
        val marks = dots.mapIndexed { i, score -> ChartMark(Rect((i % 20) * step, (i / 20) * step, (i % 20 + 1) * step, (i / 20 + 1) * step), dotTips[score]) }
        ChartLayout(marks) {
            dots.forEachIndexed { i, score ->
                drawCircle(controversyColor(surface, primary, 0.12f + (score / 9f).toDouble().let { it * it }.toFloat() * 0.88f),
                    (step - 4.dp.toPx()) / 2 * progress.chartFraction(i), Offset((i % 20 + 0.5f) * step, (i / 20 + 0.5f) * step))
            }
            label("10", 0f, heightPx - 4.dp.toPx(), labelStyle, primary, ha = 0f)
            label("1", widthPx, heightPx - 4.dp.toPx(), labelStyle, primary, ha = 1f)
        }
    }
    }
}

/** 五档光谱与表盘共用落针位置；所有争议数值来自 RatingControversy。 */
@Composable
fun ControversyGauge(value: RatingControversy, tip: String, dial: Boolean, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val progress = chartFractions().chartFraction(0)
    val lowEnd = ControversyTier.UNANIMOUS.label()
    val highEnd = ControversyTier.POLARIZED.label()
    TapChart(tip, modifier.fillMaxWidth().height(if (dial) 160.dp else 50.dp)) {
        ChartLayout(listOf(ChartMark(Rect(0f, 0f, widthPx, heightPx), tip))) {
            if (dial) {
                val radius = min(widthPx / 2 - 16.dp.toPx(), 130.dp.toPx())
                val center = Offset(widthPx / 2, heightPx - 16.dp.toPx())
                (0..4).forEach { i -> drawArc(if (i <= value.tier.ordinal) controversyColor(primary, tertiary, i / 4f) else empty,
                    180f + i * 36f + 1f, 34f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = Stroke(14.dp.toPx(), cap = StrokeCap.Round)) }
                val angle = PI * (1 - value.gaugePosition * progress)
                drawLine(ink, center, Offset(center.x + cos(angle).toFloat() * (radius - 14.dp.toPx()), center.y - sin(angle).toFloat() * (radius - 14.dp.toPx())), 4.dp.toPx(), StrokeCap.Round)
                drawCircle(ink, 8.dp.toPx(), center)
                label(String.format(Locale.ROOT, "%.2f", value.sigma), center.x, center.y - radius * 0.35f, style, ink)
            } else {
                val gap = 2.dp.toPx()
                val w = (widthPx - gap * 4) / 5
                (0..4).forEach { i -> drawRoundRect(if (i <= value.tier.ordinal) controversyColor(primary, tertiary, i / 4f) else empty,
                    Offset(i * (w + gap), 8.dp.toPx()), Size(w, 8.dp.toPx()), CornerRadius(4.dp.toPx())) }
                val x = (value.gaugePosition * progress).toFloat() * widthPx
                drawLine(ink, Offset(x, 0f), Offset(x, 24.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
                label(lowEnd, 0f, heightPx - 4.dp.toPx(), style, muted, ha = 0f)
                label(highEnd, widthPx, heightPx - 4.dp.toPx(), style, muted, ha = 1f)
            }
        }
    }
}

/** 三段占比或收藏状态，段宽和针位置都是计算模型给出的数据。 */
@Composable
fun StatsSegments(points: List<PlotPoint>, ring: Boolean, modifier: Modifier = Modifier) {
    val sum = points.sumOf { it.value }.coerceAtLeast(1.0)
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val progress = chartFractions()
    TapChart(points.joinToString { it.tip }, modifier.fillMaxWidth().height(if (ring) 150.dp else 90.dp)) {
        val rects = points.mapIndexed { i, _ -> Rect(0f, i * heightPx / points.size, widthPx, (i + 1) * heightPx / points.size) }
        ChartLayout(points.mapIndexed { i, p -> ChartMark(rects[i], p.tip) }) {
            var offset = 0f
            points.forEachIndexed { i, p ->
                val share = (p.value / sum).toFloat()
                val color = if (p.color != Color.Unspecified) p.color else controversyColor(primary, tertiary, i.toFloat() / (points.size - 1).coerceAtLeast(1))
                if (ring) {
                    val radius = 54.dp.toPx()
                    drawArc(color, -90 + offset * 360, max(0f, share * 360 - 2) * progress.chartFraction(i), false,
                        Offset(70.dp.toPx() - radius, 70.dp.toPx() - radius), Size(radius * 2, radius * 2), style = Stroke(18.dp.toPx()))
                    label(p.label, 150.dp.toPx(), (20 + i * 28).dp.toPx(), style, muted, ha = 0f, maxWidthPx = widthPx - 150.dp.toPx())
                } else {
                    drawRoundRect(color, Offset(offset * widthPx, 0f), Size(max(0f, share * widthPx - 2.dp.toPx()) * progress.chartFraction(i), 20.dp.toPx()), CornerRadius(3.dp.toPx()))
                    val column = i % 2
                    val row = i / 2
                    label(p.label, column * widthPx / 2 + 16.dp.toPx(), (48 + row * 24).dp.toPx(), style, muted,
                        ha = 0f, maxWidthPx = widthPx / 2 - 16.dp.toPx())
                    drawRoundRect(color, Offset(column * widthPx / 2, (39 + row * 24).dp.toPx()), Size(10.dp.toPx(), 10.dp.toPx()), CornerRadius(3.dp.toPx()))
                }
                offset += share
            }
        }
    }
}

/** 实际分布对照均值相同、σ=0.96的参考正态曲线；参考线和真实线共享尺度。 */
@Composable
fun ControversyRidge(counts: List<Int>, value: RatingControversy, tip: String, modifier: Modifier = Modifier) {
    val color = controversyColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, value.tier.ordinal / 4f)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val referenceLabel = stringResource(R.string.stats_ridge_reference)
    val progress = chartFractions().chartFraction(0)
    TapChart(tip, modifier.fillMaxWidth().height(128.dp)) {
        val reference = List(91) { i -> val v = 1 + i / 10.0; exp(-((v - value.mean) * (v - value.mean)) / (2 * 0.96 * 0.96)) / (0.96 * sqrt(2 * PI)) }
        val actual = counts.map { it.toDouble() / value.count }
        val peak = max(actual.max(), reference.max())
        val base = heightPx - 20.dp.toPx()
        fun x(v: Double) = 6.dp.toPx() + ((v - 1) / 9).toFloat() * (widthPx - 12.dp.toPx())
        fun y(v: Double) = base - (v / peak).toFloat() * (base - 8.dp.toPx())
        val path = Path().apply {
            actual.forEachIndexed { i, v ->
                if (i == 0) moveTo(x(1.0), y(v)) else {
                    val p0 = actual[(i - 2).coerceAtLeast(0)]
                    val p1 = actual[i - 1]
                    val p3 = actual[(i + 1).coerceAtMost(9)]
                    cubicTo(x(i.toDouble()) + (x(i + 1.0) - x((i - 1.0).coerceAtLeast(1.0))) / 6,
                        min(base, y(p1) + (y(v) - y(p0)) / 6), x(i + 1.0) - (x((i + 2.0).coerceAtMost(10.0)) - x(i.toDouble())) / 6,
                        min(base, y(v) - (y(p3) - y(p1)) / 6), x(i + 1.0), y(v))
                }
            }
        }
        val ref = Path().apply { reference.forEachIndexed { i, v -> if (i == 0) moveTo(x(1.0), y(v)) else lineTo(x(1 + i / 10.0), y(v)) } }
        ChartLayout(listOf(ChartMark(Rect(0f, 0f, widthPx, heightPx), tip))) {
            val left = x((value.mean - value.sigma).coerceAtLeast(1.0))
            val right = x((value.mean + value.sigma).coerceAtMost(10.0))
            val center = x(value.mean)
            drawRoundRect(color.copy(alpha = 0.08f), Offset(center + (left - center) * progress, 8.dp.toPx()),
                Size((right - left) * progress, base - 8.dp.toPx()), CornerRadius(8.dp.toPx()))
            val fill = Path().apply { addPath(revealedPath(path, progress)); lineTo(x(1.0) + (x(10.0) - x(1.0)) * progress, base); lineTo(x(1.0), base); close() }
            drawPath(fill, color.copy(alpha = 0.14f * progress))
            drawPath(ref, muted, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
            drawPath(revealedPath(path, progress), color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            (1..10).forEach { label(it.toString(), x(it.toDouble()), heightPx - 4.dp.toPx(), style, muted) }
            label(referenceLabel, 6.dp.toPx(), 16.dp.toPx(), style, muted, ha = 0f)
        }
    }
}

/** 按路径真实长度逐段显出，不用整体拉伸来假装画线。 */
private fun revealedPath(path: Path, progress: Float): Path = Path().also { output ->
    val measure = PathMeasure()
    measure.setPath(path, false)
    measure.getSegment(0f, measure.length * progress, output, true)
}

/** 同一横轴上的各组真实评分分布，每条线共用最大占比，不重新计算组均分。 */
@Composable
fun StatsDistributions(counts: List<List<Int>>, labels: List<String>, tips: List<String>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val progress = chartFractions()
    TapChart(tips.joinToString(), modifier.fillMaxWidth().height(150.dp)) {
        val shares = counts.map { group -> val sum = group.sumOf { it.toLong() }.coerceAtLeast(1); group.map { it.toDouble() / sum } }
        val peak = shares.flatten().maxOrNull()?.coerceAtLeast(0.01) ?: 1.0
        val base = heightPx - 42.dp.toPx()
        fun x(i: Int) = 6.dp.toPx() + i * (widthPx - 12.dp.toPx()) / 9
        val paths = shares.map { group -> Path().apply { group.forEachIndexed { i, v -> val y = base - (v / peak).toFloat() * (base - 8.dp.toPx()); if (i == 0) moveTo(x(i), y) else lineTo(x(i), y) } } }
        ChartLayout(tips.mapIndexed { i, tip -> ChartMark(Rect(i * widthPx / tips.size, 0f, (i + 1) * widthPx / tips.size, heightPx), tip) }) {
            paths.forEachIndexed { i, path ->
                val color = controversyColor(primary, tertiary, i.toFloat() / (paths.size - 1).coerceAtLeast(1))
                drawPath(revealedPath(path, progress.chartFraction(i)), color, style = Stroke(2.5.dp.toPx()))
                label(labels[i], (i + 0.5f) * widthPx / paths.size, heightPx - 4.dp.toPx(), style, color, maxWidthPx = widthPx / paths.size)
            }
            (1..10).forEach { label(it.toString(), x(it - 1), base + 18.dp.toPx(), style, muted) }
        }
    }
}

/** 标签词云以实际字宽逐行预算，每个词点按显示确切标注人数。 */
@Composable
fun StatsTagCloud(points: List<PlotPoint>, modifier: Modifier = Modifier) {
    val emphasized = MaterialTheme.typography.titleLarge
    val medium = MaterialTheme.typography.titleMedium
    val small = MaterialTheme.typography.bodyMedium
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val effects = chartOpacity()
    TapChart(points.joinToString { it.tip }, modifier.fillMaxWidth().height(180.dp)) {
        val peak = points.maxOfOrNull { it.value }?.coerceAtLeast(1.0) ?: 1.0
        val order = points.withIndex().sortedWith(compareBy({ it.index % 2 }, { it.index })).map { it.value }
        val placements = mutableListOf<Triple<PlotPoint, Rect, TextStyle>>()
        var x = 0f
        var y = 0f
        var rowHeight = 0f
        order.forEach { point ->
            val fraction = point.value / peak
            val style = if (fraction > 0.6) emphasized else if (fraction > 0.35) medium else small
            val w = min(widthPx, textWidth(point.label, style))
            val h = style.lineHeight.toPx()
            if (x > 0f && x + w > widthPx) { x = 0f; y += rowHeight + 4.dp.toPx(); rowHeight = 0f }
            if (y + h <= heightPx) {
                placements += Triple(point, Rect(x, y, x + w, y + h), style)
                x += w + 12.dp.toPx()
                rowHeight = max(rowHeight, h)
            }
        }
        ChartLayout(placements.map { ChartMark(it.second, it.first.tip) }) {
            placements.forEach { (point, rect, style) ->
                label(point.label, rect.left, rect.top + style.lineHeight.toPx() * 0.8f, style,
                    controversyColor(muted, primary, (0.35 + sqrt(point.value / peak) * 0.65).toFloat()).copy(alpha = effects),
                    ha = 0f, maxWidthPx = rect.width)
            }
        }
    }
}

/** 两派按1–4、5–7、8–10分计数，两端大占比、中间较轻，条从各自起点错开生长。 */
@Composable
fun ControversyCamps(value: RatingControversy, tips: List<String>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val middle = MaterialTheme.colorScheme.surfaceContainerHighest
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val big = MaterialTheme.typography.headlineMediumEmphasized
    val small = MaterialTheme.typography.labelSmall
    val progress = chartFractions()
    TapChart(tips.joinToString(), modifier.fillMaxWidth().height(92.dp)) {
        val shares = value.camps.map { it.toDouble() / value.count }
        val centers = listOf(0f, widthPx / 2, widthPx)
        val labels = listOf("1–4", "5–7", "8–10")
        ChartLayout(tips.mapIndexed { i, tip -> ChartMark(Rect(i * widthPx / 3, 0f, (i + 1) * widthPx / 3, heightPx), tip) }) {
            val colors = listOf(tertiary, middle, primary)
            shares.forEachIndexed { i, share ->
                val text = if (share > 0 && share < 0.01) "<1%" else String.format(Locale.ROOT, "%.0f%%", share * 100)
                label(text, centers[i], if (i == 1) 45.dp.toPx() else 34.dp.toPx(), if (i == 1) small else big,
                    if (i == 1) muted else colors[i], ha = if (i == 0) 0f else if (i == 2) 1f else 0.5f)
                label(labels[i], centers[i], 59.dp.toPx(), small, muted, ha = if (i == 0) 0f else if (i == 2) 1f else 0.5f)
            }
            var left = 0f
            shares.forEachIndexed { i, share ->
                val length = (share * widthPx).toFloat()
                val growth = progress.chartFraction(i)
                val from = when (i) { 0 -> left + length * (1 - growth); 1 -> left + length * (1 - growth) / 2; else -> left }
                drawRoundRect(colors[i], Offset(from, 70.dp.toPx()), Size(max(0f, length - 3.dp.toPx()) * growth, 16.dp.toPx()), CornerRadius(3.dp.toPx()))
                left += length
            }
        }
    }
}

/** 争议度分档的名字（统计页的文字、光谱两端的标注同读这一份）。 */
@Composable
fun ControversyTier.label(): String = stringResource(when (this) {
    ControversyTier.UNANIMOUS -> R.string.stats_tier_unanimous
    ControversyTier.CONCENTRATED -> R.string.stats_tier_concentrated
    ControversyTier.SOME_DISAGREEMENT -> R.string.stats_tier_some_disagreement
    ControversyTier.MIXED -> R.string.stats_tier_mixed
    ControversyTier.POLARIZED -> R.string.stats_tier_polarized
})
