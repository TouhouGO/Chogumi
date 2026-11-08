package io.github.touhougo.chogumi.core.data.repository

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [NsfwFilter] 组件本身：开关开/关各一条，钉住「调用时读当前值」的语义。 */
class NsfwFilterTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private data class Item(val id: Int, val nsfw: Boolean)

    @Test
    fun `开关关闭时剔除NSFW条目`() = runTest {
        val items = listOf(Item(1, nsfw = false), Item(2, nsfw = true))
        val visible = testNsfwFilter(resources, showNsfw = false).apply(items) { it.nsfw }
        assertEquals(listOf(1), visible.map { it.id })
    }

    @Test
    fun `开关打开时保留NSFW条目`() = runTest {
        val items = listOf(Item(1, nsfw = false), Item(2, nsfw = true))
        val visible = testNsfwFilter(resources, showNsfw = true).apply(items) { it.nsfw }
        assertEquals(listOf(1, 2), visible.map { it.id })
    }
}
