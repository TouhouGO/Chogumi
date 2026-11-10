package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.AirPhase
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.model.statistics.LifelineCard
import io.github.touhougo.chogumi.core.model.statistics.PaceUnit
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.chartFractions
import io.github.touhougo.chogumi.core.ui.chart.chartFraction
import io.github.touhougo.chogumi.core.ui.chart.ChartLayout
import io.github.touhougo.chogumi.core.ui.chart.ChartMark
import io.github.touhougo.chogumi.core.ui.chart.TapChart
import io.github.touhougo.chogumi.core.ui.chart.keepSeparated
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.formatScore
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max

/*
 * 一生时间线（统计 tab「这部作品的一生」）：横轴三段——放送前（压缩）、放送中（展开，每集一根柱，柱高与深浅是讨论数）、
 * 完结后（压缩，到今天）；每段底色上写这一段打分的人的均分，续作的封面立在完结后那段，轴的右端是今天和最近每天几人收藏。
 * 还没播完的作品没有完结后那段，放送中一直画到今天。只在这里用，放在本模块。
 * 几何是我的设计决定（not in M3）：三段宽 14% / 52% / 34%（没播完时 14% / 86%；放送前放不下段名与均分时加宽，从放送中那段里让），
 * 段间 4dp，段角 12dp；柱区高 56dp，柱宽至多 10dp。
 */

private const val BEFORE_END = 0.14f
private const val AIRING_END = 0.66f
private val BAND_GAP = 4.dp
private val BAND_PADDING = 8.dp
private val BAR_INSET = 12.dp
private val BAR_PLOT = 56.dp
private val BAR_MAX_WIDTH = 10.dp
private val SEQUEL_WIDTH = 24.dp
private val SEQUEL_GAP = 4.dp

/** 时间线各处的纵向位置（dp），随字号变：段名一行、均分一行、峰值一行、柱区、轴下一行字。 */
private class LifelineRows(val headerBottom: Dp, val base: Dp, val height: Dp)

@Composable
private fun lifelineRows(): LifelineRows = with(LocalDensity.current) {
    val small = MaterialTheme.typography.labelSmall.lineHeight.toDp()
    val mean = MaterialTheme.typography.titleMedium.lineHeight.toDp()
    val headerBottom = BAND_PADDING + small + mean
    val base = headerBottom + 4.dp + small + BAR_PLOT
    LifelineRows(headerBottom, base, base + 6.dp + small)
}

@StringRes
private fun AirPhase.labelRes(): Int = when (this) {
    AirPhase.BEFORE -> R.string.stats_phase_before
    AirPhase.AIRING -> R.string.stats_phase_airing
    AirPhase.AFTER -> R.string.stats_phase_after
}

@StringRes
private fun PaceUnit.labelRes(): Int = when (this) {
    PaceUnit.DAY -> R.string.stats_pace_day
    PaceUnit.WEEK -> R.string.stats_pace_week
    PaceUnit.MONTH -> R.string.stats_pace_month
}

@Composable
internal fun LifeTimeline(card: LifelineCard, modifier: Modifier = Modifier) {
    val progress = chartFractions()
    val rows = lifelineRows()
    val phases = (if (card.ended) AirPhase.entries else AirPhase.entries - AirPhase.AFTER)
        .map { phase -> stringResource(phase.labelRes()) to card.phases.firstOrNull { it.group == phase }?.mean?.let(::formatScore) }
    val tips = card.episodes.map { pluralStringResource(R.plurals.stats_episode_tip, it.comments, formatEpisodeSort(it.sort), it.comments) }
    val first = card.episodes.first().airdate
    val startLabel = stringResource(R.string.detail_meta_year_month, first.year, first.monthValue)
    val todayLabel = stringResource(R.string.stats_today)
    val paceLabel = card.pace?.let { stringResource(it.unit.labelRes(), it.count) }
    val quiet = MaterialTheme.colorScheme.surfaceContainerHigh
    val loud = MaterialTheme.colorScheme.surfaceContainerHighest
    val bar = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.labelSmall
    val meanStyle = MaterialTheme.typography.titleMediumEmphasized
    BoxWithConstraints(modifier.fillMaxWidth()) {
        TapChart(stringResource(R.string.stats_lifeline_description), Modifier.fillMaxWidth().height(rows.height)) {
            val gap = BAND_GAP.toPx()
            val pad = BAND_PADDING.toPx()
            // 放送前那段至少放得下自己的段名与均分（窄屏、大字号时 14% 不够）
            val (beforeName, beforeMean) = phases.first()
            val beforeContent = max(textWidth(beforeName, small), beforeMean?.let { textWidth(it, meanStyle) } ?: 0f) + pad * 2
            val beforeEnd = max(widthPx * BEFORE_END, beforeContent + gap / 2)
            val bounds = buildList {
                add(0f to beforeEnd - gap / 2)
                if (card.ended) {
                    add(beforeEnd + gap / 2 to widthPx * AIRING_END - gap / 2)
                    add(widthPx * AIRING_END + gap / 2 to widthPx)
                } else {
                    add(beforeEnd + gap / 2 to widthPx)
                }
            }
            val base = rows.base.toPx()
            val smallLine = small.lineHeight.toPx()
            val headerBottom = rows.headerBottom.toPx()
            // 放送中那段里的柱：按播出日排开（同一天播的几集按顺序均分）
            val (airingStart, airingEnd) = bounds[1]
            val inner0 = airingStart + BAR_INSET.toPx()
            val inner1 = airingEnd - BAR_INSET.toPx()
            val days = ChronoUnit.DAYS.between(first, card.episodes.last().airdate).toFloat()
            val count = card.episodes.size
            val barWidth = ((inner1 - inner0) / count - 2.dp.toPx()).coerceIn(2.dp.toPx(), BAR_MAX_WIDTH.toPx())
            val peak = max(1, card.episodes.maxOf { it.comments })
            val plot = BAR_PLOT.toPx()
            val bars = card.episodes.mapIndexed { i, episode ->
                val at = if (days > 0) ChronoUnit.DAYS.between(first, episode.airdate) / days else i.toFloat() / max(1, count - 1)
                val center = inner0 + at * (inner1 - inner0)
                val height = max(4.dp.toPx(), plot * episode.comments / peak)
                Rect(center - barWidth / 2, base - height, center + barWidth / 2, base)
            }
            val peakIndex = card.episodes.indexOfFirst { it.comments == peak }
            ChartLayout(
                marks = bars.mapIndexed { i, rect ->
                    val half = max(rect.width / 2, 4.dp.toPx())
                    ChartMark(Rect(rect.center.x - half, headerBottom, rect.center.x + half, base), tips[i], anchor = rect)
                },
                draw = {
                    bounds.forEachIndexed { i, (left, right) ->
                        drawRoundRect(if (i == 1) loud else quiet, Offset(left, 0f), Size(right - left, base), CornerRadius(12.dp.toPx()))
                        val (name, mean) = phases[i]
                        val room = right - left - pad * 2
                        label(name, left + pad, pad + smallLine * 0.75f, small, muted, ha = 0f, maxWidthPx = room)
                        if (mean != null) {
                            label(mean, left + pad, pad + smallLine + meanStyle.lineHeight.toPx() * 0.75f, meanStyle, ink, ha = 0f, maxWidthPx = room)
                        }
                    }
                    bars.forEachIndexed { i, rect ->
                        val alpha = 0.35f + 0.65f * card.episodes[i].comments / peak
                        val growth = progress.chartFraction(i)
                        drawRoundRect(bar.copy(alpha = alpha), Offset(rect.left, rect.bottom - rect.height * growth), Size(rect.width, rect.height * growth), CornerRadius(3.dp.toPx()))
                    }
                    bars.getOrNull(peakIndex)?.let { rect ->
                        label("$peak", rect.center.x, rect.top - 4.dp.toPx(), small, ink)
                    }
                    // 轴：放送中那段的起点写开播年月，右端是今天
                    drawRect(axis, Offset(0f, base), Size(widthPx, 2.dp.toPx()))
                    val axisBaseline = base + 6.dp.toPx() + smallLine * 0.75f
                    label(startLabel, airingStart + pad, axisBaseline, small, muted, ha = 0f)
                    val todayX = widthPx - 6.dp.toPx()
                    drawCircle(bar.copy(alpha = 0.25f), 9.dp.toPx(), Offset(todayX, base + 1.dp.toPx()))
                    drawCircle(bar, 4.5.dp.toPx(), Offset(todayX, base + 1.dp.toPx()))
                    // 轴上的字按重要性留：开播年月 > 今天 > 每天几人；窄屏、大字号时互相压住的就丢掉后者
                    val startSpan = airingStart + pad..airingStart + pad + textWidth(startLabel, small)
                    val todayWidth = textWidth(todayLabel, small)
                    val paceSpan = paceLabel?.let { widthPx - textWidth(it, small)..widthPx }
                    val paceWidth = paceSpan?.let { widthPx - it.start + 4.dp.toPx() } ?: 0f
                    val separation = 2.dp.toPx()
                    val withPace = paceSpan?.let {
                        val todaySpan = widthPx - paceWidth - todayWidth..widthPx - paceWidth
                        keepSeparated(listOf(startSpan, todaySpan, it), separation) == listOf(true, true, true)
                    } == true
                    val shownPaceWidth = if (withPace) paceWidth else 0f
                    val todaySpan = widthPx - shownPaceWidth - todayWidth..widthPx - shownPaceWidth
                    if (withPace) label(paceLabel.orEmpty(), widthPx, axisBaseline, small, muted, ha = 1f)
                    if (keepSeparated(listOf(startSpan, todaySpan), separation)[1]) {
                        label(todayLabel, widthPx - shownPaceWidth, axisBaseline, small, bar, ha = 1f)
                    }
                },
            )
        }
        if (card.ended && card.sequels.isNotEmpty()) {
            // 续作立在完结后那段的底部，放得下几张画几张
            val afterStart = maxWidth * AIRING_END + BAND_GAP / 2 + BAND_PADDING
            val room = maxWidth - afterStart - BAND_PADDING
            val fits = floor((room + SEQUEL_GAP) / (SEQUEL_WIDTH + SEQUEL_GAP)).toInt().coerceAtLeast(0)
            Row(
                Modifier.padding(start = afterStart, top = rows.base - SEQUEL_WIDTH / ChogumiDimens.CoverRatio - 4.dp).clearAndSetSemantics {},
                horizontalArrangement = Arrangement.spacedBy(SEQUEL_GAP),
            ) {
                card.sequels.take(fits).forEach { sequel ->
                    SubjectPoster(sequel.images?.posterUrl(), Modifier.width(SEQUEL_WIDTH), shape = MaterialTheme.shapes.extraSmall)
                }
            }
        }
    }
}

/** 一生时间线没到时的占位：同高。 */
@Composable
internal fun LifeTimelineSkeleton(modifier: Modifier = Modifier) {
    SkeletonBox(modifier.fillMaxWidth().height(lifelineRows().height).clearAndSetSemantics {}, MaterialTheme.shapes.medium)
}
