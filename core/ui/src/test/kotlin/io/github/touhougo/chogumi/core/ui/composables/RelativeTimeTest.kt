package io.github.touhougo.chogumi.core.ui.composables

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** 相对时间分档：每一档的两端，以及「昨天」「N 天前」按自然日而不是按 24 小时算。 */
class RelativeTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 10)
    private val noon = at(today, 12, 0)

    @Test
    fun `不到一分钟算刚刚，服务端时钟略快的未来时间也算`() {
        assertEquals(RelativeTime.JustNow, relativeTimeOf(noon, noon, zone))
        assertEquals(RelativeTime.JustNow, relativeTimeOf(noon - 59_999, noon, zone))
        assertEquals(RelativeTime.JustNow, relativeTimeOf(noon + 5_000, noon, zone))
    }

    @Test
    fun `满一分钟到不满一小时按分钟向下取整`() {
        assertEquals(RelativeTime.Minutes(1), relativeTimeOf(noon - MINUTE, noon, zone))
        assertEquals(RelativeTime.Minutes(1), relativeTimeOf(noon - 2 * MINUTE + 1, noon, zone))
        assertEquals(RelativeTime.Minutes(59), relativeTimeOf(noon - HOUR + 1, noon, zone))
    }

    @Test
    fun `满一小时到不满一天按小时向下取整，跨了零点也还是小时`() {
        assertEquals(RelativeTime.Hours(1), relativeTimeOf(noon - HOUR, noon, zone))
        assertEquals(RelativeTime.Hours(9), relativeTimeOf(noon - 9 * HOUR, noon, zone))
        val earlyMorning = at(today, 1, 0)
        assertEquals(RelativeTime.Hours(23), relativeTimeOf(earlyMorning - DAY + 1, earlyMorning, zone))
    }

    @Test
    fun `满一天后昨天是日历上的昨天`() {
        assertEquals(RelativeTime.Yesterday, relativeTimeOf(noon - DAY, noon, zone))
        // 23:30 看昨天 00:30：47 小时，还是昨天
        val lateNight = at(today, 23, 30)
        assertEquals(RelativeTime.Yesterday, relativeTimeOf(at(today.minusDays(1), 0, 30), lateNight, zone))
        // 00:30 看前天 23:30：25 小时，已是两天前
        val afterMidnight = at(today, 0, 30)
        assertEquals(RelativeTime.Days(2), relativeTimeOf(at(today.minusDays(2), 23, 30), afterMidnight, zone))
    }

    @Test
    fun `不满一周按自然日数，满一周写日子`() {
        assertEquals(RelativeTime.Days(3), relativeTimeOf(noon - 3 * DAY, noon, zone))
        // 差一毫秒满一周：时长还在这一档，自然日已是 7 天前
        assertEquals(RelativeTime.Days(7), relativeTimeOf(noon - WEEK + 1, noon, zone))
        assertEquals(RelativeTime.Date, relativeTimeOf(noon - WEEK, noon, zone))
        assertEquals(RelativeTime.Date, relativeTimeOf(noon - 400 * DAY, noon, zone))
    }

    @Test
    fun `夏令时回拨那天满二十四小时仍是同一天时写小时`() {
        // 美东 2026-11-01 有 25 个小时：当天 00:00 到 23:30 是 24.5 小时
        val newYork = ZoneId.of("America/New_York")
        val day = LocalDate.of(2026, 11, 1)
        val start = day.atStartOfDay(newYork).toInstant().toEpochMilli()
        val now = day.atTime(23, 30).atZone(newYork).toInstant().toEpochMilli()
        assertEquals(RelativeTime.Hours(24), relativeTimeOf(start, now, newYork))
    }

    private fun at(day: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(day.year, day.month, day.dayOfMonth, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY
    }
}
