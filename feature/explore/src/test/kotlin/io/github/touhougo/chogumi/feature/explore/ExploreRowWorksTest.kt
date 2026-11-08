package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreRowWorksTest {

    private fun works(vararg ids: Int): Load<List<SubjectBo>> =
        Load.Ready(ids.map { SubjectBo(id = it, type = SubjectType.GAME, name = "作品$it", nameCn = "") })

    private fun ids(load: Load<List<SubjectBo>>): List<Int>? = (load as? Load.Ready)?.value?.map { it.id }

    @Test
    fun `一行代表作里同一部作品只出现一次`() {
        // 两个平台的第一名是同一部作品：后一块换成自己的下一部
        val row = rowWorks(listOf(works(7, 1, 2), works(7, 3), works(1, 7, 4)), take = 1)
        assertEquals(listOf(listOf(7), listOf(3), listOf(1)), row.map(::ids))
    }

    @Test
    fun `合集各块叠的封面互不重复，没有重叠时照常取前几部`() {
        val row = rowWorks(listOf(works(1, 2, 3, 4), works(5, 6, 7), works(2, 8, 9, 10)), take = 3)
        assertEquals(listOf(listOf(1, 2, 3), listOf(5, 6, 7), listOf(8, 9, 10)), row.map(::ids))
    }

    @Test
    fun `作品全被前面用掉的块退回自己的前几部`() {
        val row = rowWorks(listOf(works(1, 2), works(2, 1)), take = 1)
        assertEquals(listOf(listOf(1), listOf(2)), row.map(::ids))
        val exhausted = rowWorks(listOf(works(1), works(1)), take = 1)
        assertEquals(listOf(listOf(1), listOf(1)), exhausted.map(::ids))
    }

    @Test
    fun `有一块还在等时整行等待，取不到的块照旧`() {
        assertEquals(List(3) { Load.Pending }, rowWorks(listOf(works(1), Load.Pending, works(2)), take = 1))
        val failed = Load.Failed(Failure.Offline)
        assertEquals(listOf(failed, works(1)), rowWorks(listOf(failed, works(1)), take = 1))
    }
}
