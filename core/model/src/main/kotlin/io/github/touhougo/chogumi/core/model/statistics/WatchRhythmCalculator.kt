package io.github.touhougo.chogumi.core.model.statistics

import io.github.touhougo.chogumi.core.foundation.BANGUMI_ZONE
import io.github.touhougo.chogumi.core.model.BATCH_MARK_MIN
import io.github.touhougo.chogumi.core.model.CHASE_TIERS
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeMarks
import io.github.touhougo.chogumi.core.model.LAG_TIER_LIMITS
import io.github.touhougo.chogumi.core.model.NIGHT_HOURS
import io.github.touhougo.chogumi.core.model.SPAN_LANES
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.model.WatchDay
import io.github.touhougo.chogumi.core.model.WatchRhythm
import io.github.touhougo.chogumi.core.model.WatchSpan
import io.github.touhougo.chogumi.core.model.YearRing
import io.github.touhougo.chogumi.core.model.displayNameOf
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * [collections] 里各部作品的逐集记录看出来的节奏（口径见 [WatchRhythm]）；一集记录都没有时为 null。
 * 日子与钟点按 [EpisodeMarks.zone] 算，离首播几天按业务时区（首播日是业务日期）算。
 */
internal fun watchRhythmOf(collections: List<UserCollectionBo>, marks: EpisodeMarks): WatchRhythm? {
    val events = mutableListOf<MarkEvent>()
    val spans = mutableListOf<Pair<Long, WatchSpan>>()
    val hours = IntArray(24)
    val weekdays = IntArray(7)
    val lagTiers = IntArray(LAG_TIER_LIMITS.size)

    for (collection in collections) {
        val subjectMarks = marks.bySubject[collection.subjectId].orEmpty().filter { it.markedAt > 0 }
        if (subjectMarks.isEmpty()) continue
        val title = collection.subject?.let { displayNameOf(it) }
        val series = (collection.subject?.eps ?: 0) > 1
        val batches = subjectMarks.groupingBy { it.markedAt / SECOND_MILLIS }.eachCount().filterValues { it >= BATCH_MARK_MIN }.keys
        var anyLive = false
        for (mark in subjectMarks) {
            val at = Instant.ofEpochMilli(mark.markedAt).atZone(marks.zone)
            events += MarkEvent(mark.markedAt, at.toLocalDate(), title)
            if (mark.markedAt / SECOND_MILLIS in batches) continue
            anyLive = true
            hours[at.hour]++
            weekdays[at.dayOfWeek.value - 1]++
            val airdate = mark.airdate
            if (series && airdate != null) {
                val sinceAir = (mark.markedAt - airdate.atStartOfDay(BANGUMI_ZONE).toInstant().toEpochMilli()).coerceAtLeast(0) / DAY_MILLIS.toDouble()
                lagTiers[LAG_TIER_LIMITS.indexOfFirst { sinceAir < it }]++
            }
        }
        if (collection.type == CollectionType.DONE && series && title != null && subjectMarks.size >= 2 && anyLive) {
            val perDay = subjectMarks.groupingBy { Instant.ofEpochMilli(it.markedAt).atZone(marks.zone).toLocalDate() }.eachCount()
            spans += subjectMarks.maxOf { it.markedAt } to WatchSpan(
                subjectId = collection.subjectId,
                title = title,
                start = perDay.keys.min(),
                end = perDay.keys.max(),
                days = perDay.toSortedMap(),
            )
        }
    }
    if (events.isEmpty()) return null

    val lengths = spans.map { it.second.length }.sorted()
    val live = hours.sum()
    return WatchRhythm(
        rings = ringsOf(events),
        hours = hours.toList(),
        weekdays = weekdays.toList(),
        nightShare = if (live == 0) null else percentOf(NIGHT_HOURS.sumOf { hours[it] }, live),
        lagTiers = lagTiers.toList(),
        chaseShare = lagTiers.sum().takeIf { it > 0 }?.let { percentOf(lagTiers.take(CHASE_TIERS).sum(), it) },
        spans = spans.sortedByDescending { it.first }.take(SPAN_LANES).map { it.second },
        medianSpanDays = lengths.getOrNull(lengths.size / 2),
    )
}

/** 一集记录落在哪天、是哪部（没有条目资料时标题为 null，只计集数）。 */
private class MarkEvent(val markedAt: Long, val date: LocalDate, val title: String?)

/** 按年分的年轮；一天里的作品按这天第一次标的先后排。 */
private fun ringsOf(events: List<MarkEvent>): List<YearRing> {
    val days = events.sortedBy { it.markedAt }.groupBy { it.date }.mapValues { (_, dayEvents) ->
        WatchDay(episodes = dayEvents.size, titles = dayEvents.mapNotNull { it.title }.distinct())
    }
    return days.entries.groupBy { it.key.year }.toSortedMap().map { (year, entries) ->
        val sorted = entries.sortedBy { it.key }
        YearRing(
            year = year,
            days = sorted.associate { it.key to it.value },
            episodes = sorted.sumOf { it.value.episodes },
            activeDays = sorted.size,
            longestStreak = longestStreakOf(sorted.map { it.key }),
            busiestDay = sorted.maxBy { it.value.episodes }.key,
        )
    }
}

/** 升序日子里最长连着几天。 */
private fun longestStreakOf(dates: List<LocalDate>): Int {
    var best = 0
    var current = 0
    var previous: LocalDate? = null
    for (date in dates) {
        current = if (previous != null && previous.plusDays(1) == date) current + 1 else 1
        best = maxOf(best, current)
        previous = date
    }
    return best
}

private const val SECOND_MILLIS = 1_000L
/** 一天的毫秒数（统计各处共用）。 */
internal val DAY_MILLIS = TimeUnit.DAYS.toMillis(1)
