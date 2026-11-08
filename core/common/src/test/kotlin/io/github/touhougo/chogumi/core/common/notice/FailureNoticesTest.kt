package io.github.touhougo.chogumi.core.common.notice

import io.github.touhougo.chogumi.core.foundation.Failure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class FailureNoticesTest {

    @Test
    fun `发出后等待显示，显示完清空`() {
        val notices = FailureNotices()
        notices.post(Failure.Offline)
        val notice = notices.current.value!!
        assertEquals(NoticeText.Reason(Failure.Offline), notice.text)
        notices.consume(notice)
        assertNull(notices.current.value)
    }

    @Test
    fun `同一个原因连发两次是两条提示`() {
        val notices = FailureNotices()
        notices.post(Failure.Offline)
        val first = notices.current.value!!
        notices.consume(first)
        notices.post(Failure.Offline)
        assertNotSame(first, notices.current.value)
    }

    @Test
    fun `显示期间来了新的，确认旧的不会把新的清掉`() {
        val notices = FailureNotices()
        notices.post(Failure.Timeout)
        val first = notices.current.value!!
        notices.post(42)
        val second = notices.current.value!!
        notices.consume(first)
        assertSame(second, notices.current.value)
        assertEquals(NoticeText.Resource(42), second.text)
    }

    @Test
    fun `字符串资源带上格式参数`() {
        val notices = FailureNotices()
        notices.post(42, Failure.Http(500))
        assertEquals(NoticeText.Resource(42, listOf(Failure.Http(500))), notices.current.value!!.text)
    }

    @Test
    fun `重试回调原样带给界面`() {
        val notices = FailureNotices()
        var retried = 0
        notices.post(Failure.Offline) { retried++ }
        notices.current.value!!.retry!!.invoke()
        assertEquals(1, retried)
    }
}
