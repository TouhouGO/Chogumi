package io.github.touhougo.chogumi.core.ui.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 分数尺下方的字怎么让位，热度格怎么分档。 */
class ChartLayoutRulesTest {

    @Test
    fun `分数尺：隔得开的字都在第一行，居中对准自己的点`() {
        val placed = rulerLabelLefts(centers = listOf(100f, 200f), widths = listOf(40f, 40f), canvasWidth = 300f, gap = 8f)
        assertEquals(listOf(0 to 80f, 0 to 180f), placed)
    }

    @Test
    fun `分数尺：挨着的两个均分，后一条换到第二行`() {
        // 317613：看过 8.34、在看 8.35 几乎同一处
        val placed = rulerLabelLefts(centers = listOf(250f, 251f), widths = listOf(50f, 50f), canvasWidth = 300f, gap = 8f)
        assertEquals(listOf(0 to 225f, 1 to 226f), placed)
    }

    @Test
    fun `分数尺：两行挤时仅完整放得下的字接在最空那一行末尾`() {
        val placed = rulerLabelLefts(centers = listOf(100f, 102f, 104f), widths = listOf(40f, 40f, 40f), canvasWidth = 300f, gap = 8f)
        assertEquals(listOf(0 to 80f, 1 to 82f, 0 to 128f), placed)

        val atEdge = rulerLabelLefts(centers = listOf(290f, 292f, 294f), widths = listOf(40f, 40f, 40f), canvasWidth = 300f, gap = 8f)
        assertEquals(listOf(0 to 260f, 1 to 260f, null), atEdge, "贴右边两行都满时不能夹回已排的字上")
    }

    @Test
    fun `分数尺：同中心的三个长标签在各自行内不相交且不出画布`() {
        val widths = listOf(180f, 180f, 180f)
        val placed = rulerLabelLefts(centers = listOf(150f, 150f, 150f), widths = widths, canvasWidth = 300f, gap = 8f)
        assertEquals(listOf(0 to 60f, 1 to 60f, null), placed)
        assertWithinRows(placed, widths, canvasWidth = 300f, gap = 8f)
    }

    @Test
    fun `分数尺：贴两端与放大字宽仅保留界内隔开的标签`() {
        val centers = listOf(0f, 300f, 150f, 150f, 150f)
        listOf(listOf(40f, 40f, 180f, 180f, 180f), listOf(80f, 80f, 360f, 360f, 360f)).forEach { widths ->
            val placed = rulerLabelLefts(centers, widths, canvasWidth = 300f, gap = 8f)
            assertWithinRows(placed, widths, canvasWidth = 300f, gap = 8f)
            assertTrue(placed[0] != null && placed[1] != null, "两端短标签仍可显示")
            if (widths[2] > 300f) assertEquals(listOf(null, null, null), placed.drop(2))
        }
    }

    private fun assertWithinRows(placed: List<Pair<Int, Float>?>, widths: List<Float>, canvasWidth: Float, gap: Float) {
        placed.forEachIndexed { i, spot ->
            spot?.let { (row, left) ->
                assertTrue(left >= 0f && left + widths[i] <= canvasWidth, "第 ${i + 1} 条必须在画布内")
                placed.take(i).forEachIndexed { j, other ->
                    other?.let { (otherRow, otherLeft) ->
                        if (row == otherRow) {
                            assertTrue(left >= otherLeft + widths[j] + gap || otherLeft >= left + widths[i] + gap, "第 ${i + 1} 条不能压住第 ${j + 1} 条")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `热度分档：0 是 0 档，最大的在最深一档，其余按比例向上取整`() {
        assertEquals(listOf(0, 1, 1, 2, 3, 4), listOf(0, 1, 25, 26, 60, 100).map { heatLevelOf(it, peak = 100) })
        assertEquals(0, heatLevelOf(5, peak = 0))
    }
}
