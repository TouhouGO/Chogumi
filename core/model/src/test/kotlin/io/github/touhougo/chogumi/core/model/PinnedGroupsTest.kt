package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedGroupsTest {

    private fun group(name: String) = GroupSummaryBo(name = name, title = name, icon = null, members = 0)

    private val joined = listOf(group("a"), group("b"), group("c"), group("d"))

    @Test
    fun `置顶的按置顶名单的顺序排在前，其余按服务端顺序在后`() {
        assertEquals(listOf("c", "a", "b", "d"), pinnedFirst(joined, pinned = listOf("c", "a")).map { it.name })
    }

    @Test
    fun `已经退出的小组留在名单里也不画`() {
        assertEquals(listOf("b", "a", "c", "d"), pinnedFirst(joined, pinned = listOf("gone", "b")).map { it.name })
    }

    @Test
    fun `没有置顶时就是服务端顺序`() {
        assertEquals(joined, pinnedFirst(joined, pinned = emptyList()))
    }
}
