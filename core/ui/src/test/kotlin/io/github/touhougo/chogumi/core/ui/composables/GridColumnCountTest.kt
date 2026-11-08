package io.github.touhougo.chogumi.core.ui.composables

import org.junit.Assert.assertEquals
import org.junit.Test

/** [gridColumnCount]：全仓网格列数的唯一算法（像素，按密度 1 写，1px = 1dp）。 */
class GridColumnCountTest {

    @Test
    fun `与 Adaptive 同一算法 至少 1 列`() {
        assertEquals(3, gridColumnCount(available = 328, minCell = 100, spacing = 12))
        assertEquals(1, gridColumnCount(available = 50, minCell = 100, spacing = 12))
    }

    /** 屏宽减去两侧 16dp 页边距后排章节方格：手机 6 到 8 列，平板十几列，格子不比触控区窄。 */
    @Test
    fun `章节方格按屏宽排列 手机 6 到 8 列 平板更多`() {
        val cell = GridCell.EpisodeTile
        fun columnsOn(screen: Int) = gridColumnCount(screen - 32, cell.minWidth.value.toInt(), cell.spacing.value.toInt())
        assertEquals("360dp", 6, columnsOn(360))
        assertEquals("380dp", 7, columnsOn(380))
        assertEquals("411dp", 7, columnsOn(411))
        assertEquals("448dp", 8, columnsOn(448))
        assertEquals("840dp 平板", 16, columnsOn(840))
    }
}
