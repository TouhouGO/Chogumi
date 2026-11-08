package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.displayZone
import io.github.touhougo.chogumi.core.model.WatchRhythm
import io.github.touhougo.chogumi.core.model.YearRing
import io.github.touhougo.chogumi.core.model.ringLevelOf
import io.github.touhougo.chogumi.core.model.statistics.percentOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.ClockDial
import io.github.touhougo.chogumi.core.ui.chart.CountBars
import io.github.touhougo.chogumi.core.ui.chart.HeatCell
import io.github.touhougo.chogumi.core.ui.chart.HeatCells
import io.github.touhougo.chogumi.core.ui.chart.ProportionBar
import io.github.touhougo.chogumi.core.ui.chart.ProportionSegment
import io.github.touhougo.chogumi.core.ui.chart.SpanLanes
import io.github.touhougo.chogumi.core.ui.chart.ordinalTones
import io.github.touhougo.chogumi.core.ui.composables.LocalNow
import io.github.touhougo.chogumi.core.ui.composables.formatMonthDay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

// ---- 年轮、几点看、追得多紧、一部看多久（本人的逐集记录） ----

@Composable
internal fun RhythmSections(rhythm: WatchRhythm) {
    val locale = LocalConfiguration.current.locales[0]
    val episodeUnit = stringResource(R.string.career_unit_episode)
    if (rhythm.rings.isNotEmpty()) RingSection(rhythm.rings, locale)
    rhythm.nightShare?.let { ClockSection(rhythm, it, locale) }
    rhythm.chaseShare?.let { ChaseSection(rhythm.lagTiers, it, episodeUnit) }
    if (rhythm.spans.isNotEmpty()) SpanSection(rhythm, locale)
}


/** 年轮：一年一页，左右滑换年，最新的一年在最右、打开就是它。 */
@Composable
private fun RingSection(rings: List<YearRing>, locale: Locale) {
    val pager = rememberPagerState(initialPage = rings.lastIndex) { rings.size }
    val ring = rings[pager.currentPage.coerceIn(rings.indices)]
    val title = stringResource(R.string.career_sec_ring)
    val answer = highlighted(pluralStringResource(R.plurals.career_ans_ring, ring.episodes, ring.year, ring.episodes), "${ring.episodes}")
    StatSection(title, answer) {
        HorizontalPager(pager, verticalAlignment = Alignment.Top, pageSpacing = 16.dp) { page ->
            YearRingPage(rings[page], locale, "$title ${rings[page].year}")
        }
    }
}


@Composable
private fun YearRingPage(ring: YearRing, locale: Locale, description: String) {
    val today = Instant.ofEpochMilli(LocalNow.current.value).atZone(displayZone()).toLocalDate()
    val rows = Month.entries.map { month ->
        val first = LocalDate.of(ring.year, month, 1)
        (0 until first.lengthOfMonth()).map { offset ->
            val date = first.plusDays(offset.toLong())
            val day = ring.days[date]
            val tip = if (day == null) stringResource(R.string.career_tip_day_none, formatMonthDay(date, locale))
            else pluralStringResource(R.plurals.career_tip_day, day.episodes, formatMonthDay(date, locale), day.episodes, namesOf(day.titles))
            HeatCell(ringLevelOf(day?.episodes ?: 0), tip, pending = date.isAfter(today))
        }
    }
    Column {
        HeatCells(
            rows = rows,
            // 一行只有一格高，窄屏上放不下十二个月名：只写单数月
            rowLabels = Month.entries.map { if (it.value % 2 == 1) it.getDisplayName(TextStyle.SHORT, locale) else "" },
            columns = RING_COLUMNS,
            description = description,
        )
        val dayUnit = stringResource(R.string.career_unit_day)
        KpiRow(
            listOf(
                KpiText(formatCount(ring.longestStreak), dayUnit, stringResource(R.string.career_ring_streak)),
                KpiText(formatCount(ring.days.getValue(ring.busiestDay).episodes), stringResource(R.string.career_unit_episode), stringResource(R.string.career_ring_busiest)),
                KpiText(formatCount(ring.activeDays), dayUnit, stringResource(R.string.career_ring_days)),
            ),
            Modifier.padding(top = 12.dp),
        )
    }
}


/** 年轮一行最多几天。 */
private const val RING_COLUMNS = 31


@Composable
private fun ClockSection(rhythm: WatchRhythm, nightShare: Int, locale: Locale) {
    val title = stringResource(R.string.career_sec_clock)
    val answer = highlighted(stringResource(R.string.career_ans_clock, nightShare), "$nightShare%")
    val peak = rhythm.hours.indices.toList().peakBy { rhythm.hours[it] } ?: return
    StatSection(title, answer) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ClockDial(
                hours = rhythm.hours,
                tips = rhythm.hours.mapIndexed { h, n -> pluralStringResource(R.plurals.career_tip_hour, n, h, (h + 1) % 24, n) },
                peakLabel = stringResource(R.string.career_clock_peak, peak),
                description = "$title $answer",
            )
            // 中文的 NARROW 七天都是「星」，标签和气泡都用 SHORT（周一、Mon）
            val dayNames = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, locale) }
            CountBars(
                counts = rhythm.weekdays,
                labels = dayNames,
                tips = dayNames.mapIndexed { i, day -> pluralStringResource(R.plurals.career_tip_weekday, rhythm.weekdays[i], day, rhythm.weekdays[i]) },
                description = title,
                height = WEEK_BARS_HEIGHT,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}


private val WEEK_BARS_HEIGHT = 56.dp


@Composable
private fun ChaseSection(lagTiers: List<Int>, share: Int, unit: String) {
    val title = stringResource(R.string.career_sec_chase)
    val answer = highlighted(stringResource(R.string.career_ans_chase, share), "$share%")
    val labels = ChaseTierLabels.map { stringResource(it) }
    // 追得越紧颜色越深
    val tones = ordinalTones(lagTiers.size).reversed()
    val total = lagTiers.sum()
    StatSection(title, answer) {
        Column {
            ProportionBar(
                segments = lagTiers.mapIndexed { i, count ->
                    ProportionSegment(count, tones[i], stringResource(R.string.career_tip_share, labels[i], count, unit, percentOf(count, total)))
                },
                description = "$title $answer",
                height = LENGTH_BAR_HEIGHT,
            )
            SwatchLegend(labels.indices.filter { lagTiers[it] > 0 }.map { tones[it] to labels[it] })
        }
    }
}


/** 追得多紧五档的名字，顺序同 LAG_TIER_LIMITS。 */
private val ChaseTierLabels = listOf(
    R.string.career_chase_same_day,
    R.string.career_chase_week,
    R.string.career_chase_month,
    R.string.career_chase_year,
    R.string.career_chase_later,
)


@Composable
private fun SpanSection(rhythm: WatchRhythm, locale: Locale) {
    val median = rhythm.medianSpanDays ?: return
    val title = stringResource(R.string.career_sec_span)
    val answer = highlighted(pluralStringResource(R.plurals.career_ans_span, median, median), "$median")
    StatSection(title, answer) {
        SpanLanes(
            spans = rhythm.spans,
            tips = rhythm.spans.map { span ->
                pluralStringResource(R.plurals.career_tip_span, span.length, span.title, formatMonthDay(span.start, locale), formatMonthDay(span.end, locale), span.length)
            },
            lengthLabels = rhythm.spans.map { stringResource(R.string.career_span_days, it.length) },
            monthLabel = { month -> if (month.monthValue == 1) "${month.year}" else month.month.getDisplayName(TextStyle.SHORT, locale) },
            description = "$title $answer",
        )
    }
}
