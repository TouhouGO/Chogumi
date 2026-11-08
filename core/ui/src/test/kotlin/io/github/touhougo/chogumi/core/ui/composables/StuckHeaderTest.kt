package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.lazy.LazyListItemInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [stuckHeaderBottom]：列表里的吸顶项吸住了没有（柔边画不画、画在哪），只看可见项是否互相压着。 */
class StuckHeaderTest {

    private fun item(at: Int, top: Int, height: Int): LazyListItemInfo = object : LazyListItemInfo {
        override val index: Int = at
        override val key: Any = at
        override val offset: Int = top
        override val size: Int = height
    }

    @Test
    fun `吸顶项在原位时没有吸住`() {
        val items = listOf(item(0, 0, 300), item(1, 300, 56), item(2, 356, 200))
        assertNull(stuckHeaderBottom(items, viewportStartOffset = 0))
    }

    @Test
    fun `后面的项滚到吸顶项下面时算吸住，柔边画在它下沿`() {
        // 排序行（2）吸在顶上，楼层（3）已经滚到它下面
        val items = listOf(item(2, 0, 56), item(3, -40, 200), item(4, 160, 200))
        assertEquals(56, stuckHeaderBottom(items, viewportStartOffset = 0))
    }

    @Test
    fun `下沿相对列表顶边，扣掉视口起点`() {
        val items = listOf(item(2, -8, 56), item(3, -40, 200))
        assertEquals(56, stuckHeaderBottom(items, viewportStartOffset = -8))
    }
}
