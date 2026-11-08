package io.github.touhougo.chogumi.core.ui.chart

import kotlin.test.Test
import kotlin.test.assertEquals

/** 华夫格的取整与图例占比。 */
class PercentCellsTest {

    @Test
    fun `华夫格图例：有格写百分比，有数不足一格写小于一，没有写零`() {
        assertEquals(18, shownPercentOf(cells = 18, count = 22))
        assertEquals(null, shownPercentOf(cells = 0, count = 2))
        assertEquals(0, shownPercentOf(cells = 0, count = 0))
    }

    @Test
    fun `percentCells合计恰好一百`() {
        val cells = percentCells(listOf(58, 22, 19, 6, 4))
        assertEquals(100, cells.sum())
        // 58/109=53.2%→53，22/109=20.2%→20，19/109=17.4%→17，6/109=5.5%→5，4/109=3.7%→3：整数部分 98，
        // 余 2 格按小数部分（.7,.5,.4,.2,.2）补给 4 和 6。
        assertEquals(listOf(53, 20, 17, 6, 4), cells)
    }

    @Test
    fun `percentCells不足半百分点可为0格`() {
        // 1 / 400 = 0.25%：不到 0.5%，分不到格子。
        val cells = percentCells(listOf(399, 1))
        assertEquals(100, cells.sum())
        assertEquals(0, cells[1])
    }

    @Test
    fun `percentCells全零返回全零`() {
        assertEquals(listOf(0, 0), percentCells(listOf(0, 0)))
    }
}
