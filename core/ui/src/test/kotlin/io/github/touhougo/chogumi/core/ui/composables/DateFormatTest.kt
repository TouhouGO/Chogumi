package io.github.touhougo.chogumi.core.ui.composables

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/** 日期文案：「月、日、星期」中英各一（跨月、跨年），以及只要日子的「年-月-日」。 */
class DateFormatTest {

    @Test
    fun `中文月日带星期，数字与汉字之间留空格`() {
        assertEquals("9 月 30 日 周三", formatMonthDay(LocalDate.of(2026, 9, 30), Locale.SIMPLIFIED_CHINESE))
        assertEquals("10 月 1 日 周四", formatMonthDay(LocalDate.of(2026, 10, 1), Locale.SIMPLIFIED_CHINESE))
        assertEquals("12 月 31 日 周四", formatMonthDay(LocalDate.of(2026, 12, 31), Locale.SIMPLIFIED_CHINESE))
        assertEquals("1 月 1 日 周五", formatMonthDay(LocalDate.of(2027, 1, 1), Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `英文月日带星期缩写`() {
        assertEquals("Sep 30, Wed", formatMonthDay(LocalDate.of(2026, 9, 30), Locale.US))
        assertEquals("Oct 1, Thu", formatMonthDay(LocalDate.of(2026, 10, 1), Locale.US))
        assertEquals("Dec 31, Thu", formatMonthDay(LocalDate.of(2026, 12, 31), Locale.US))
        assertEquals("Jan 1, Fri", formatMonthDay(LocalDate.of(2027, 1, 1), Locale.US))
    }

    @Test
    fun `没有对应资源的系统语言按中文写日期_星期也是中文`() {
        assertEquals("9 月 30 日 周三", formatMonthDay(LocalDate.of(2026, 9, 30), Locale.JAPAN))
        assertEquals("2024 年 3 月 2 日", formatPastDate(LocalDate.of(2024, 3, 2), LocalDate.of(2026, 10, 20), Locale.FRANCE))
    }

    @Test
    fun `年月日按本机时区取日子，未知时间是空串`() {
        val noon = LocalDate.of(2025, 11, 27).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+14:00"))
            assertEquals("2025-11-28", formatIsoDate(noon))
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-10:00"))
            assertEquals("2025-11-27", formatIsoDate(noon))
        } finally {
            TimeZone.setDefault(originalZone)
        }
        assertEquals("", formatIsoDate(0L))
        assertEquals("", formatIsoDate(-1L))
    }

    @Test
    fun `过去的日子今年只写月日，往年带年份`() {
        val today = LocalDate.of(2026, 10, 10)
        assertEquals("10 月 2 日", formatPastDate(LocalDate.of(2026, 10, 2), today, Locale.SIMPLIFIED_CHINESE))
        assertEquals("2024 年 3 月 2 日", formatPastDate(LocalDate.of(2024, 3, 2), today, Locale.SIMPLIFIED_CHINESE))
        assertEquals("Oct 2", formatPastDate(LocalDate.of(2026, 10, 2), today, Locale.US))
        assertEquals("Mar 2, 2024", formatPastDate(LocalDate.of(2024, 3, 2), today, Locale.US))
    }
}
