package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** 章节方格里按像素算的几处：提示放在哪（[tipPosition]，图表气泡也用它）、分段导航条各段多宽（[rangeSegmentWidths]）、手指落在哪一段（[rangeSegmentAt]）。 */
class EpisodeTileLayoutTest {

    private val rich = IntSize(200, 80)
    private val plain = IntSize(120, 24)

    /** 宿主（方格）横向占 [left]..[left] + 328，窗口高 [windowHeight]。 */
    private fun position(anchor: IntRect, tip: IntSize, kind: TipKind, windowHeight: Int = 1000, left: Int = 0) =
        tipPosition(anchor, tip, IntRect(left, 0, left + 328, 400), windowHeight, spacing = 4, step = 8, kind = kind)

    @Test
    fun `富提示在格子右下开 左缘对着格子中线`() {
        assertEquals(IntOffset(30, 64), position(IntRect(0, 0, 60, 60), rich, TipKind.Rich))
    }

    @Test
    fun `富提示右边放不下时按 8 一档往左挪 不出宿主右缘`() {
        // 中线 298，右缘要到 498：超出 170，挪 176（22 档）
        assertEquals(IntOffset(122, 64), position(IntRect(268, 0, 328, 60), rich, TipKind.Rich))
    }

    @Test
    fun `富提示 格子在窗口下半时朝上开`() {
        assertEquals(IntOffset(30, 772), position(IntRect(0, 856, 60, 916), rich, TipKind.Rich))
    }

    @Test
    fun `上下都放不下时放在下方 收进窗口不出底边`() {
        // 下方从 44 开始会出底边（44 + 80 > 100）：收到 100 - 80
        assertEquals(IntOffset(30, 20), position(IntRect(0, 10, 60, 40), rich, TipKind.Rich, windowHeight = 100))
    }

    @Test
    fun `上下都放不下 卡比窗口还高时贴着顶边`() {
        assertEquals(IntOffset(30, 0), position(IntRect(0, 10, 60, 40), rich, TipKind.Rich, windowHeight = 60))
    }

    @Test
    fun `普通提示开在格子正上方 上方放不下才朝下`() {
        assertEquals(IntOffset(34, 36), position(IntRect(64, 64, 124, 124), plain, TipKind.Plain))
        assertEquals(IntOffset(34, 64), position(IntRect(64, 0, 124, 60), plain, TipKind.Plain))
    }

    @Test
    fun `普通提示对着格子居中 不出宿主两边`() {
        assertEquals(IntOffset(0, 36), position(IntRect(0, 64, 60, 124), plain, TipKind.Plain))
        assertEquals(IntOffset(208, 36), position(IntRect(268, 64, 328, 124), plain, TipKind.Plain))
        assertEquals(IntOffset(16, 36), position(IntRect(16, 64, 76, 124), plain, TipKind.Plain, left = 16))
    }

    @Test
    fun `导航条 选中段按 5点2 比 1 分得更宽 各段宽度加起来正好是可用宽度`() {
        val widths = rangeSegmentWidths(count = 12, selected = 3, available = 300, minSelected = 60)
        assertEquals(96, widths[3])
        assertEquals(listOf(19, 19, 19, 19, 19, 19, 18, 18, 18, 18, 18), widths.filterIndexed { index, _ -> index != 3 })
        assertEquals(300, widths.sum())
    }

    @Test
    fun `导航条 段多时选中段至少放得下区间文字 只有一段时占满`() {
        val widths = rangeSegmentWidths(count = 30, selected = 0, available = 300, minSelected = 60)
        assertEquals(60, widths[0])
        assertEquals(300, widths.sum())
        assertEquals(listOf(300), rangeSegmentWidths(count = 1, selected = 0, available = 300, minSelected = 60))
    }

    @Test
    fun `导航条 段间距算给左边那一段 超出两端取首末段`() {
        val widths = listOf(10, 20, 30)
        assertEquals(0, rangeSegmentAt(-5f, widths, gap = 2))
        assertEquals(0, rangeSegmentAt(11.9f, widths, gap = 2))
        assertEquals(1, rangeSegmentAt(12f, widths, gap = 2))
        assertEquals(1, rangeSegmentAt(33.9f, widths, gap = 2))
        assertEquals(2, rangeSegmentAt(34f, widths, gap = 2))
        assertEquals(2, rangeSegmentAt(100f, widths, gap = 2))
    }
}
