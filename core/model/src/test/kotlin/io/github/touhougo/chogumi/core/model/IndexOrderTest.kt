package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class IndexOrderTest {

    private fun entry(id: Int, order: Int) = IndexEntryBo(id = id, targetId = id, name = "e$id", order = order)

    /** 服务端怎么排：`(order, id)` 升序。 */
    private fun serverOrder(entries: List<IndexEntryBo>): List<Int> =
        entries.sortedWith(compareBy({ it.order }, { it.id })).map { it.id }

    /** 按 [changes] 写过之后服务端排出来的顺序。 */
    private fun afterWrites(entries: List<IndexEntryBo>, changes: List<IndexOrderChange>): List<Int> {
        val written = changes.associate { it.relatedId to it.order }
        return serverOrder(entries.map { e -> written[e.id]?.let { e.copy(order = it) } ?: e })
    }

    /** 新收录的默认都是 0：列表按 id（收录先后）排。 */
    private val fresh = (1..10).map { entry(it, 0) }

    @Test
    fun `全是 0 的列表把一条挪到末尾只改它一条`() {
        val ids = listOf(1, 2, 4, 5, 6, 7, 8, 9, 10, 3)
        val reorder = indexReorderOf(fresh, ids)
        assertEquals(listOf(3), reorder.changes.map { it.relatedId })
        assertEquals(ids, afterWrites(fresh, reorder.changes))
    }

    @Test
    fun `全是 0 的列表把最后一条挪到最前：只能把它前面 id 更小的都往后排，排序值不取负数`() {
        val ids = listOf(10) + (1..9).toList()
        val reorder = indexReorderOf(fresh, ids)
        assertEquals(ids, afterWrites(fresh, reorder.changes))
        assertEquals(9, reorder.changes.size)
        assertTrue(reorder.entries.all { it.order >= 0 })
    }

    @Test
    fun `写过一次的末尾按间隔拉开，之后挪进它们之间只改一条`() {
        val first = indexReorderOf(fresh, listOf(1, 2, 4, 5, 6, 7, 8, 9, 10, 3))
        val second = indexReorderOf(first.entries, listOf(1, 2, 4, 5, 6, 7, 8, 9, 3, 10))
        assertEquals(1, second.changes.size)
        assertEquals(listOf(1, 2, 4, 5, 6, 7, 8, 9, 3, 10), afterWrites(first.entries, second.changes))
    }

    @Test
    fun `有空隙时挪到两条之间只改挪动的那条，落在两个值的中间`() {
        val spaced = (1..5).map { entry(it, it * 100) }
        val reorder = indexReorderOf(spaced, listOf(1, 5, 2, 3, 4))
        assertEquals(listOf(IndexOrderChange(relatedId = 5, order = 150, previous = 500, comment = "")), reorder.changes)
    }

    @Test
    fun `结果整列就是新顺序，带上新排序值，没变的不写`() {
        val spaced = (1..5).map { entry(it, it * 100) }
        val reorder = indexReorderOf(spaced, listOf(2, 1, 3, 4, 5))
        assertEquals(listOf(2, 1, 3, 4, 5), reorder.entries.map { it.id })
        assertEquals(listOf(2, 1, 3, 4, 5), serverOrder(reorder.entries))
        assertTrue(reorder.changes.all { change -> spaced.first { it.id == change.relatedId }.order == change.previous && change.previous != change.order })
    }

    @Test
    fun `随机排列：写完总是新顺序，改动条数等于穷举出的最少条数`() {
        val random = Random(7)
        repeat(300) {
            val size = random.nextInt(1, 7)
            val ids = (1..size).shuffled(random)
            val entries = ids.map { entry(it, random.nextInt(0, 4)) }.sortedWith(compareBy({ it.order }, { it.id }))
            val target = entries.map { it.id }.shuffled(random)
            val reorder = indexReorderOf(entries, target)
            assertEquals(target, afterWrites(entries, reorder.changes))
            assertTrue(reorder.entries.all { it.order >= 0 })
            assertEquals("entries=$entries target=$target", fewestChanges(entries, target), reorder.changes.size)
        }
    }

    @Test
    fun `上界附近重排保持范围和新顺序，并只改最少的一条`() {
        val entries = listOf(entry(1, ORDER_LIMIT), entry(2, ORDER_LIMIT))
        val target = listOf(2, 1)

        val reorder = indexReorderOf(entries, target)

        assertTrue(reorder.entries.all { it.order in 0..ORDER_LIMIT })
        assertEquals(target, serverOrder(reorder.entries))
        assertEquals(target, afterWrites(entries, reorder.changes))
        assertEquals(fewestChanges(entries, target), reorder.changes.size)
    }

    @Test
    fun `随机上界附近排列保持范围和新顺序，并只改最少条数`() {
        val random = Random(19)
        repeat(300) {
            val size = random.nextInt(1, 7)
            val ids = (1..size).shuffled(random)
            val entries = ids.map { id ->
                val order = when (random.nextInt(4)) {
                    0 -> ORDER_LIMIT
                    1 -> ORDER_LIMIT - random.nextInt(1, 12)
                    else -> random.nextInt(0, 4)
                }
                entry(id, order)
            }.sortedWith(compareBy({ it.order }, { it.id }))
            val target = entries.map { it.id }.shuffled(random)

            val reorder = indexReorderOf(entries, target)

            assertTrue("entries=$entries target=$target", reorder.entries.all { it.order in 0..ORDER_LIMIT })
            assertEquals("entries=$entries target=$target", target, afterWrites(entries, reorder.changes))
            assertEquals("entries=$entries target=$target", fewestChanges(entries, target), reorder.changes.size)
        }
    }

    /**
     * 穷举：最少要改几条。保留一组条目原值不动、其余任意改成 0..[ORDER_LIMIT] 内的值能排出 [target]，当且仅当按 [target] 顺序依次给
     * 每条取能取的最小键（保留的必须正好是它的原键）一路排得下去。
     */
    private fun fewestChanges(entries: List<IndexEntryBo>, target: List<Int>): Int {
        val byId = entries.associateBy { it.id }
        val sequence = target.map { byId.getValue(it) }
        val n = sequence.size
        return (0 until (1 shl n)).filter { mask ->
            var order = 0L
            var id = Int.MIN_VALUE
            val fits = sequence.withIndex().all { (i, e) ->
                val (minOrder, minId) = if (e.id > id) order to e.id else (order + 1) to e.id
                if (mask and (1 shl i) != 0) {
                    val withinBounds = e.order in 0..ORDER_LIMIT
                    val fits = withinBounds &&
                        (e.order > minOrder || (e.order.toLong() == minOrder && e.id >= minId))
                    order = e.order.toLong()
                    id = e.id
                    fits
                } else {
                    order = minOrder
                    id = minId
                    order <= ORDER_LIMIT
                }
            }
            fits && order <= ORDER_LIMIT
        }.minOf { n - Integer.bitCount(it) }
    }

    private companion object {
        const val ORDER_LIMIT = 16_777_215
    }
}
