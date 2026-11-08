package io.github.touhougo.chogumi.core.ui.common.navigation

import io.github.touhougo.chogumi.core.model.SubjectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressEntryRequestsTest {

    @Test
    fun `同类型连点也是新请求`() {
        val requests = ProgressEntryRequests()
        val initial = requests.latest.value

        requests.open(SubjectType.BOOK)
        val first = requests.latest.value
        requests.open(SubjectType.BOOK)
        val second = requests.latest.value

        assertEquals(SubjectType.BOOK, first.type)
        assertEquals(SubjectType.BOOK, second.type)
        assertTrue(initial.seq < first.seq && first.seq < second.seq, "每次点击都有新的请求身份")
        assertNotEquals(first, second, "同类型连点必须是两个不相等的请求，否则 StateFlow 会去重")
    }

    @Test
    fun `null 即全部类型`() {
        val requests = ProgressEntryRequests()
        requests.open(SubjectType.BOOK)
        requests.open(null)
        assertNull(requests.latest.value.type)
    }
}
