package io.github.touhougo.chogumi.core.model

import org.junit.Test
import org.junit.Assert.assertEquals

class StableOrderTest {
    private fun order(previous: List<Int>?, sorted: List<Int>) = stableOrder(previous, sorted) { it }

    @Test
    fun `无上次顺序时按当前排序`() {
        assertEquals(listOf(3, 1, 2), order(null, listOf(3, 1, 2)))
    }

    @Test
    fun `已有条目保持上次位置 不随排序变化跳动`() {
        // 条目 2 刚被操作、排序后跑到最前：界面上仍留在原位
        assertEquals(listOf(1, 2, 3), order(listOf(1, 2, 3), listOf(2, 1, 3)))
    }

    @Test
    fun `消失的条目被移除`() {
        assertEquals(listOf(1, 3), order(listOf(1, 2, 3), listOf(3, 1)))
    }

    @Test
    fun `新出现的条目按当前排序放在最前`() {
        assertEquals(listOf(5, 4, 1, 2), order(listOf(1, 2), listOf(5, 2, 4, 1)))
    }

    @Test
    fun `调用方可让新出现的条目追加到末尾`() {
        assertEquals(listOf(1, 2, 5, 4), stableOrder(listOf(1, 2), listOf(5, 2, 4, 1), newItemsFirst = false) { it })
    }

    @Test
    fun `空列表`() {
        assertEquals(emptyList<Int>(), order(listOf(1, 2), emptyList()))
        assertEquals(listOf(1), order(emptyList(), listOf(1)))
    }
}
