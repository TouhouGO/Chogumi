package io.github.touhougo.chogumi.core.model

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiringTimeTest {

    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")
    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun jst(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, tokyo).toInstant().toEpochMilli()

    private fun beijing(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, shanghai).toInstant().toEpochMilli()

    @Test
    fun `晚间首播落在 bgm 给的星期里时_时刻是北京时间`() {
        // 2026-10-01 周四 21:30 JST = 20:30 北京，bgm.tv 周四
        assertEquals(AiringMinute(20 * 60 + 30), airingMinuteOf(4, jst(2026, 10, 1, 21, 30)))
        assertEquals("20:30", airingMinuteOf(4, jst(2026, 10, 1, 21, 30))!!.label())
    }

    @Test
    fun `日本时间凌晨首播归前一天_北京时间零点后的写成 24 点以后`() {
        // 2026-10-04 周日 00:30 JST = 周六 23:30 北京，bgm.tv 周六
        assertEquals(AiringMinute(23 * 60 + 30), airingMinuteOf(6, jst(2026, 10, 4, 0, 30)))
        // 2026-10-05 周一 01:20 JST = 周一 00:20 北京，仍归周日，写成 24:20
        val minute = airingMinuteOf(7, jst(2026, 10, 5, 1, 20))
        assertEquals(AiringMinute(24 * 60 + 20), minute)
        assertEquals("24:20", minute!!.label())
    }

    @Test
    fun `首播落在别的放送日时没有时刻`() {
        // bangumi-data 写的是周三 01:05 JST，bgm.tv 说周日：星期对不上，不显示
        assertNull(airingMinuteOf(7, jst(2026, 10, 7, 1, 5)))
        // 周日 01:00 JST 归周六，bgm.tv 说周日
        assertNull(airingMinuteOf(7, jst(2026, 10, 4, 1, 0)))
    }

    @Test
    fun `放送日在北京时间 4 点翻页`() {
        // 周二 00:50 北京：还是周一的放送日，24:50
        assertEquals(AiringNow(1, AiringMinute(24 * 60 + 50)), airingNowOf(beijing(2026, 10, 13, 0, 50)))
        // 周二 03:59 北京：周一 27:59
        assertEquals(AiringNow(1, AiringMinute(27 * 60 + 59)), airingNowOf(beijing(2026, 10, 13, 3, 59)))
        // 周二 04:00 北京：周二的放送日开始
        assertEquals(AiringNow(2, AiringMinute(4 * 60)), airingNowOf(beijing(2026, 10, 13, 4, 0)))
        // 周一 14:10 北京
        assertEquals(AiringNow(1, AiringMinute(14 * 60 + 10)), airingNowOf(beijing(2026, 10, 12, 14, 10)))
    }

    @Test
    fun `只认每周一次的 broadcast`() {
        assertEquals(jst(2026, 10, 2, 23, 30), weeklyBroadcastStartOf("R/2026-10-02T14:30:00.000Z/P7D"))
        assertEquals(jst(2026, 10, 2, 23, 30), weeklyBroadcastStartOf("R/2026-10-02T23:30:00+09:00/P7D"))
        assertNull(weeklyBroadcastStartOf("R/2026-10-02T14:30:00.000Z/P1D"))
        assertNull(weeklyBroadcastStartOf("2026-10-02T14:30:00.000Z"))
        assertNull(weeklyBroadcastStartOf("R/not-a-time/P7D"))
        assertNull(weeklyBroadcastStartOf(""))
        assertNull(weeklyBroadcastStartOf(null))
    }
}
