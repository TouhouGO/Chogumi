package io.github.touhougo.chogumi.feature.home

import io.github.touhougo.chogumi.core.model.SubjectType
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeResultRequestTest {
    @Test
    fun `点全部之后旧分类结果不能提交`() {
        assertFalse(ContinueFilterRequest(1, SubjectType.BOOK).isCurrent(ContinueFilterRequest(2, null)))
    }

    @Test
    fun `快速动画书籍动画后首次动画的失效结果不能清除新动画选择`() {
        assertFalse(ContinueFilterRequest(1, SubjectType.ANIME).isCurrent(ContinueFilterRequest(3, SubjectType.ANIME)))
    }

    @Test
    fun `本轮全部结果和本轮分类结果都能提交`() {
        assertTrue(ContinueFilterRequest(2, null).isCurrent(ContinueFilterRequest(2, null)))
        assertTrue(ContinueFilterRequest(3, SubjectType.BOOK).isCurrent(ContinueFilterRequest(3, SubjectType.BOOK)))
    }
}
