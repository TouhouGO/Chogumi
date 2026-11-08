package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 只有表情的段落照常进行内流、连续图片归成一组、图片尺寸（不放大、长图上限、未知占位）、折叠线不切图片。 */
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

    private fun single(width: Float?, height: Float?, column: androidx.compose.ui.unit.Dp): ImageCell =
        imageRowsOf(listOf(if (width != null && height != null) width to height else null), column).single().cells.single()

    @Test
    fun `单图：宽不超过栏宽，高不超过宽的四分之三倍以上即标长图，尺寸未知按十六比九占位`() {
        assertEquals(ImageCell(300.dp, 150.dp, isLong = false), single(600f, 300f, column = 300.dp))
        assertEquals(ImageCell(100.dp, 100.dp, isLong = false), single(100f, 100f, column = 300.dp), "小图按 1px≈1dp 原样大小，不拉伸")
        assertEquals(ImageCell(300.dp, 400.dp, isLong = true), single(300f, 3000f, column = 300.dp))
        assertEquals(ImageCell(320.dp, 180.dp, isLong = false), single(null, null, column = 320.dp))
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
            assertEquals(
                ImageCell(320.dp, 180.dp, isLong = false),
                single(pixelWidth, pixelHeight, column = 320.dp),
                "$pixelWidth × $pixelHeight 的无效尺寸不进入比例计算，按 16:9 占位、不标长图",
            )
        }
    }

    @Test
    fun `一组小图并一行时不放大，这一行窄于栏宽`() {
        val rows = imageRowsOf(listOf(100f to 100f, 100f to 100f), column = 300.dp)
        assertEquals(listOf(ImageRow(0, listOf(ImageCell(100.dp, 100.dp, false), ImageCell(100.dp, 100.dp, false)))), rows)
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
