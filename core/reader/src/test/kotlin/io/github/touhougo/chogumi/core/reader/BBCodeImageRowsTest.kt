package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 只有表情的段落照常进行内流、连续图片归组成宫格、单图尺寸上限、折叠线不切图片。 */
class BBCodeImageRowsTest {

    private fun blocksOf(raw: String) =
        BBCodeParser.parse(raw).nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = null, onUserClick = null)

    @Test
    fun `只有表情的一段也进行内流，与夹在文字里的表情同一种画法`() {
        assertEquals(listOf(Row.InlineFlow::class), blocksOf("(bgm38)").toRows().map { it::class })
        assertEquals(listOf(Row.InlineFlow::class), blocksOf("(S_S)(bgm38)").toRows().map { it::class })
    }

    @Test
    fun `连续图片（隔着换行与空行）归成一组，夹了文字就分组`() {
        val rows = blocksOf("[img]https://a/1.png[/img]\n\n[img]https://a/2.png[/img] [img]https://a/3.png[/img]\n文字\n[img]https://a/4.png[/img]").toRows()
        val groups = rows.filterIsInstance<Row.Images>().map { row -> row.images.map { it.url.substringAfterLast('/') } }
        assertEquals(listOf(listOf("1.png", "2.png", "3.png"), listOf("4.png")), groups)
        assertTrue(rows.none { it is Row.BlankLine }, "图片之间的空行并进组里，不单独占行")
    }

    @Test
    fun `图片后面接文字时挂起的空行照常补上`() {
        val rows = blocksOf("[img]https://a/1.png[/img]\n\n文字").toRows()
        assertEquals(listOf(Row.Images::class, Row.BlankLine::class, Row.InlineFlow::class), rows.map { it::class })
    }

    @Test
    fun `宫格列数：两张四张两列，其余三列`() {
        assertEquals(listOf(2, 3, 2, 3, 3), listOf(2, 3, 4, 5, 9).map(::imageGridColumns))
    }

    @Test
    fun `单图：宽不超过栏宽，高不超过宽的四分之三倍以上即标长图，尺寸未知按十六比九占位`() {
        val (w1, h1, long1) = singleImageSize(600f, 300f, column = 300.dp)
        assertEquals(300.dp, w1); assertEquals(150.dp, h1); assertEquals(false, long1)
        val (w2, h2, long2) = singleImageSize(100f, 100f, column = 300.dp)
        assertEquals(100.dp, w2, "小图按 1px≈1dp 原样大小，不拉伸"); assertEquals(100.dp, h2); assertEquals(false, long2)
        val (w3, h3, long3) = singleImageSize(300f, 3000f, column = 300.dp)
        assertEquals(300.dp, w3); assertEquals(400.dp, h3); assertEquals(true, long3)
        val (w4, h4, _) = singleImageSize(null, null, column = 320.dp)
        assertEquals(320.dp, w4); assertEquals(180.dp, h4)
    }

    @Test
    fun `单图的非有限或非正像素尺寸按未知尺寸占位`() {
        val invalid = listOf(
            Float.POSITIVE_INFINITY to 1f,
            Float.NEGATIVE_INFINITY to 1f,
            Float.NaN to 1f,
            1f to Float.NaN,
            Float.MIN_VALUE to Float.MAX_VALUE,
            1f to 0f,
        )

        invalid.forEach { (pixelWidth, pixelHeight) ->
            val (width, height, isLong) = singleImageSize(pixelWidth, pixelHeight, column = 320.dp)
            assertEquals(320.dp, width, "$pixelWidth × $pixelHeight 的无效尺寸不进入比例计算")
            assertEquals(180.dp, height, "$pixelWidth × $pixelHeight 按现有 16:9 未知尺寸占位")
            assertFalse(isLong, "$pixelWidth × $pixelHeight 不误标成长图")
        }
    }

    @Test
    fun `折叠线落在图片行里时整组图留在线上，不切开`() {
        val rows = listOf(
            Row.InlineFlow(emptyList()),
            Row.Images(listOf(Block.Image("u", null, null, link = null))),
            Row.InlineFlow(emptyList()),
        )
        // 行高 20：第 0 行是 2 行文字（高 40），图片行高 200（折成 10 行）；预算 6 行落在图片里
        val fold = foldLineOf(
            budget = 6,
            rows = rows,
            tops = intArrayOf(0, 44, 248),
            heights = intArrayOf(40, 200, 20),
            textLayouts = arrayOfNulls(3),
            sampleLineHeight = { 20 },
        )
        assertEquals(244, fold, "折叠线在图片行底边，而不是图片中间")
    }
}
