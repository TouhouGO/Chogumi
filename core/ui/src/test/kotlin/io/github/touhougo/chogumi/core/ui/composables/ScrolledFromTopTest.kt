package io.github.touhougo.chogumi.core.ui.composables

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [isScrolledFromTop]：头部页顶栏填不填色的唯一判定，列表只要离开顶部一点就算。 */
class ScrolledFromTopTest {

    @Test
    fun `停在顶部时不算离开`() {
        assertFalse(isScrolledFromTop(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun `首项滚上去一点就算离开`() {
        assertTrue(isScrolledFromTop(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 1))
    }

    @Test
    fun `首项已滚出就算离开`() {
        assertTrue(isScrolledFromTop(firstVisibleItemIndex = 1, firstVisibleItemScrollOffset = 0))
    }
}
