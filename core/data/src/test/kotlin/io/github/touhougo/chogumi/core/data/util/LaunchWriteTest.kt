package io.github.touhougo.chogumi.core.data.util

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.foundation.Failure
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** 本机写入抛了不能把进程带崩：失败收进提示，成功不出声。 */
class LaunchWriteTest {

    @Test
    fun `写入抛出时失败发到提示而不是冲出作用域`() = runTest {
        val failures = FailureNotices()

        backgroundScope.launchWrite(failures) { throw IOException("磁盘满") }.join()

        val notice = failures.current.value
        assertEquals(Failure.Storage, (notice?.text as? NoticeText.Reason)?.failure)
    }

    @Test
    fun `写入成功时不发提示`() = runTest {
        val failures = FailureNotices()

        backgroundScope.launchWrite(failures) { }.join()

        assertNull(failures.current.value)
    }

    @Test
    fun `失败的提示带上重试`() = runTest {
        val failures = FailureNotices()
        var retried = false

        backgroundScope.launchWrite(failures, retry = { retried = true }) { throw IOException("磁盘满") }.join()

        failures.current.value?.retry?.invoke()
        assertEquals(true, retried)
    }
}
