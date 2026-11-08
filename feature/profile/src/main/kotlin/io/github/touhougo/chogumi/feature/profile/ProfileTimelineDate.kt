package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.foundation.displayZone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class TimelineDayLabelKind {
    TODAY,
    YESTERDAY,
    DATE,
}

/** 同一天（[day]，显示口径的时区里）的动态，保持原顺序。 */
internal data class TimelineDayGroup<T>(
    val day: LocalDate,
    val items: List<T>,
)

private fun dayOf(timestampMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()

/** 相邻且同一天的动态归成一组；动态是谁在某一刻做的事，日子按看的人所在的时区算（[displayZone]）。 */
internal fun <T> groupTimelineByDay(
    items: List<T>,
    timestamp: (T) -> Long,
    zone: ZoneId = displayZone(),
): List<TimelineDayGroup<T>> {
    val result = mutableListOf<TimelineDayGroup<T>>()
    var day: LocalDate? = null
    var dayItems = mutableListOf<T>()
    items.forEach { item ->
        val itemDay = dayOf(timestamp(item), zone)
        if (itemDay != day) {
            day?.let { result += TimelineDayGroup(it, dayItems) }
            day = itemDay
            dayItems = mutableListOf()
        }
        dayItems += item
    }
    day?.let { result += TimelineDayGroup(it, dayItems) }
    return result
}

internal fun timelineDayLabelKind(
    timestampMillis: Long,
    nowMillis: Long,
    zone: ZoneId = displayZone(),
): TimelineDayLabelKind {
    val day = dayOf(timestampMillis, zone)
    val today = dayOf(nowMillis, zone)
    return when (day) {
        today -> TimelineDayLabelKind.TODAY
        today.minusDays(1) -> TimelineDayLabelKind.YESTERDAY
        else -> TimelineDayLabelKind.DATE
    }
}
