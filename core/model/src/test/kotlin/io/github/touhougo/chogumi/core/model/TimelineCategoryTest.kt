package io.github.touhougo.chogumi.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineCategoryTest {

    @Test
    fun `每个编号认回自己`() {
        TimelineCategory.entries.forEach { assertEquals(it, timelineCategoryOf(it.code)) }
    }

    @Test
    fun `编号互不重复`() {
        assertEquals(TimelineCategory.entries.size, TimelineCategory.entries.map { it.code }.toSet().size)
    }

    @Test
    fun `认不出的编号是 null`() {
        listOf(-1, 0, 10, 99).forEach { assertNull(timelineCategoryOf(it), "编号 $it") }
    }

    @Test
    fun `服务端给的九个大类各有其名`() {
        assertEquals(
            listOf(
                TimelineCategory.DAILY, TimelineCategory.WIKI, TimelineCategory.COLLECT, TimelineCategory.PROGRESS,
                TimelineCategory.STATUS, TimelineCategory.BLOG, TimelineCategory.INDEX, TimelineCategory.MONO,
                TimelineCategory.SKYLIGHT,
            ),
            (1..9).map { timelineCategoryOf(it) },
        )
    }
}
