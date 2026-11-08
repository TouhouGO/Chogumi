package io.github.touhougo.chogumi.feature.profile

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileTimelineDateTest {

    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 11)

    @Test
    fun `动态按显示时区的自然日分组并保持组内顺序`() {
        val groups = groupTimelineByDay(
            listOf(
                Timed("new", millis(today, hour = 9)),
                Timed("old", millis(today, hour = 0)),
                Timed("yesterday", millis(today.minusDays(1), hour = 23)),
            ),
            timestamp = { it.createdAt },
            zone = zone,
        )

        assertEquals(listOf(today, today.minusDays(1)), groups.map { it.day })
        assertEquals(listOf("new", "old"), groups[0].items.map { it.id })
        assertEquals(listOf("yesterday"), groups[1].items.map { it.id })
    }

    @Test
    fun `日期标签区分今天昨天和更早日期`() {
        val now = millis(today, hour = 12)

        assertEquals(TimelineDayLabelKind.TODAY, timelineDayLabelKind(millis(today, hour = 0), now, zone))
        assertEquals(TimelineDayLabelKind.YESTERDAY, timelineDayLabelKind(millis(today.minusDays(1), hour = 23), now, zone))
        assertEquals(TimelineDayLabelKind.DATE, timelineDayLabelKind(millis(today.minusDays(2), hour = 23), now, zone))
    }

    private fun millis(day: LocalDate, hour: Int): Long = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private data class Timed(val id: String, val createdAt: Long)
}
