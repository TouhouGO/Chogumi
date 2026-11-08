package io.github.touhougo.chogumi.core.ui.composables

import io.github.touhougo.chogumi.core.model.SubjectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SubjectTypeIconTest {
    @Test
    fun `每种类型选中时是另一枚实心图标_且彼此不同`() {
        SubjectType.entries.forEach { assertNotEquals(it.iconRes(), it.iconRes(filled = true), "$it") }
        assertEquals(SubjectType.entries.size, SubjectType.entries.map { it.iconRes(filled = true) }.distinct().size)
    }

    @Test
    fun `全部格的图标取未知类型的网格`() {
        assertEquals(SubjectType.UNKNOWN.iconRes(filled = true), (null as SubjectType?).iconOrAllRes(filled = true))
    }
}
