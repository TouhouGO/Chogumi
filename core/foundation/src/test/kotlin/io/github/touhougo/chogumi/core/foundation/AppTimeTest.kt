package io.github.touhougo.chogumi.core.foundation

import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AppTimeTest {

    /** 北京时间 2026-10-11 23:59:30 */
    private val beforeMidnight = ZonedDateTime.of(2026, 10, 11, 23, 59, 30, 0, BANGUMI_ZONE).toInstant().toEpochMilli()

    @Test
    fun `前台时整分钟一跳_跨北京时间零点今天随之变`() = runTest {
        val clock = AppClock { beforeMidnight + testScheduler.currentTime }
        val time = AppTime.ticking(clock, MutableStateFlow(true), backgroundScope)
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 11), time.today.value)

        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(LocalDate.of(2026, 10, 11), time.today.value, "还没到零点")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(beforeMidnight + 30_000, time.now.value, "整分钟那一刻跳")
        assertEquals(LocalDate.of(2026, 10, 12), time.today.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(beforeMidnight + 90_000, time.now.value, "之后每分钟一跳")
    }

    @Test
    fun `后台不走_回到前台立刻对表`() = runTest {
        val clock = AppClock { beforeMidnight + testScheduler.currentTime }
        val foreground = MutableStateFlow(false)
        val time = AppTime.ticking(clock, foreground, backgroundScope)
        runCurrent()

        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(beforeMidnight, time.now.value, "后台没人看，不跳")

        foreground.value = true
        runCurrent()
        assertEquals(beforeMidnight + 3_600_000, time.now.value)
        assertEquals(LocalDate.of(2026, 10, 12), time.today.value)
    }

    @Test
    fun `今天按北京时间_不按本机时区`() {
        // 北京时间 10-12 00:30 = UTC 10-11 16:30：在 UTC 及以西的设备上本机还是 11 日
        // 本机时区钉在美西，否则在 UTC+8 的机器上读本机时区也是 12 日，测不出来。
        val millis = ZonedDateTime.of(2026, 10, 12, 0, 30, 0, 0, BANGUMI_ZONE).toInstant().toEpochMilli()
        val deviceZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        try {
            assertEquals(LocalDate.of(2026, 10, 12), businessDateOf(millis))
        } finally {
            TimeZone.setDefault(deviceZone)
        }
    }
}
