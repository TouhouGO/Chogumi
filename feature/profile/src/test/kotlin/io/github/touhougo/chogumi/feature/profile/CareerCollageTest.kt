package io.github.touhougo.chogumi.feature.profile

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 生涯卡的封面拼图：封面一律 3:4、不叠不出界、不留空洞；够多就铺满，不够就排成几行贴右。 */
class CareerCollageTest {

    @Test
    fun `封面够多时铺满整张卡，第一张占二乘二`() {
        CARDS.forEach { (width, height) ->
            val capacity = collageCapacity(width, height, GAP)
            val collage = collageOf(capacity + 10, width, height, GAP)
            assertTrue(collage.full)
            assertEquals(capacity, collage.cells.size, "铺满只用得下 capacity 张")
            val hero = collage.cells.first()
            val cell = collage.cells[1]
            assertEquals(cell.width * 2 + GAP, hero.width, 0.01f)
            assertEquals(cell.height * 2 + GAP, hero.height, 0.01f)
            assertTrue(collage.cells.maxOf { it.y + it.height } >= height, "网格盖到卡片下沿")
            assertEquals(width, collage.cells.maxOf { it.x + it.width }, 0.01f, "网格盖满卡宽")
        }
    }

    @Test
    fun `封面不够时每一张都在，排满一边，贴右`() {
        CARDS.forEach { (width, height) ->
            (1 until collageCapacity(width, height, GAP)).forEach { count ->
                val collage = collageOf(count, width, height, GAP)
                assertFalse(collage.full)
                assertEquals(count, collage.cells.size, "$count 张都排进去")
                assertEquals(width, collage.cells.maxOf { it.x + it.width }, 0.01f, "$count 张：贴右")
                val fillsHeight = abs(collage.cells.maxOf { it.y + it.height } - height) < 0.01f
                val fillsWidth = abs(collage.cells.minOf { it.x }) < 0.01f
                assertTrue(fillsHeight || fillsWidth, "$count 张：占满高或占满宽")
                assertCells(collage.cells, width, height, "$count 张")
            }
        }
    }

    @Test
    fun `只有一张收藏时这一张占满卡高`() {
        val (width, height) = CARDS.first()
        val only = collageOf(1, width, height, GAP).cells.single()
        assertEquals(height, only.height, 0.01f)
        assertEquals(0f, only.y)
    }

    @Test
    fun `没有封面时拼图是空的`() {
        val (width, height) = CARDS.first()
        assertTrue(collageOf(0, width, height, GAP).cells.isEmpty())
    }

    /** 每张 3:4、在卡内、两两不叠。 */
    private fun assertCells(cells: List<CollageCell>, width: Float, height: Float, name: String) {
        cells.forEach { cell ->
            assertEquals(0.75f, cell.width / cell.height, 0.001f, "$name：3:4")
            assertTrue(cell.x >= -0.01f && cell.x + cell.width <= width + 0.01f, "$name：不出左右")
            assertTrue(cell.y >= -0.01f && cell.y + cell.height <= height + 0.01f, "$name：不出上下")
        }
        cells.forEachIndexed { i, a ->
            cells.drop(i + 1).forEach { b ->
                val apart = a.x + a.width <= b.x + 0.01f || b.x + b.width <= a.x + 0.01f ||
                    a.y + a.height <= b.y + 0.01f || b.y + b.height <= a.y + 0.01f
                assertTrue(apart, "$name：$a 与 $b 重叠")
            }
        }
    }

    private companion object {
        const val GAP = 3f

        /** 380dp 手机（卡宽 348、高 1.2 倍）与平板宽（卡高封顶 440）。 */
        val CARDS = listOf(348f to 417.6f, 640f to 440f)
    }
}
