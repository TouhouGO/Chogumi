package io.github.touhougo.chogumi.core.common.debug

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashRecorderTest {

    private val time = 1_791_000_000_000L

    @Test
    fun `首行是时间与线程，其后是异常类名、描述与调用栈`() {
        val deviceZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val text = try {
            CrashRecorder.format("main", IllegalStateException("坏了"), time)
        } finally {
            TimeZone.setDefault(deviceZone)
        }
        val lines = text.lines()
        assertEquals("2026-10-03 04:00:00  线程 main", lines[0])
        assertEquals("java.lang.IllegalStateException: 坏了", lines[1])
        assertTrue(lines[2].startsWith("\tat "), "第三行起是调用栈")
    }

    @Test
    fun `描述本身会抛错的异常照样记下类名与调用栈`() {
        // 这正是系统写不出崩溃报告的那种异常：一转文字就出错
        val hostile = object : RuntimeException() {
            override val message: String get() = throw StackOverflowError()
        }
        val text = CrashRecorder.format("main", hostile, time)
        assertTrue(text.contains("（描述取不到：java.lang.StackOverflowError）"))
        assertTrue(text.contains("\tat "), "调用栈仍然在")
    }

    @Test
    fun `原因链逐层写出，成环时停下不死循环`() {
        val inner = IllegalArgumentException("里层")
        val outer = RuntimeException("外层", inner)
        inner.initCause(outer)
        val text = CrashRecorder.format("worker", outer, time)
        assertTrue(text.contains("java.lang.RuntimeException: 外层"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: 里层"))
        assertTrue(text.contains("原因链成环"))
    }

    @Test
    fun `帧数有上限，超出部分只记数量`() {
        val error = RuntimeException("深")
        error.stackTrace = Array(500) { StackTraceElement("C", "m$it", "C.kt", it) }
        val text = CrashRecorder.format("main", error, time)
        assertEquals(120, text.lines().count { it.startsWith("\tat ") })
        assertTrue(text.contains("另有 380 帧"))
    }
}
