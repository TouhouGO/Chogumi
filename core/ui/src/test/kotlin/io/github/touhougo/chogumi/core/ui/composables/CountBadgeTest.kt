package io.github.touhougo.chogumi.core.ui.composables

import org.junit.Assert.assertEquals
import org.junit.Test

class CountBadgeTest {

    @Test
    fun `数字徽标文案_0不显示_超过999截为999加号`() {
        // M3 feedback.md Badges：大徽标标签最多 4 个字符（含「+」）
        assertEquals(null, countBadgeText(0))
        assertEquals(null, countBadgeText(-1))
        assertEquals("1", countBadgeText(1))
        assertEquals("999", countBadgeText(999))
        assertEquals("999+", countBadgeText(1000))
    }
}
