package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlaceholderVerticalAlign
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 行内内容的排版不变量：大表情对齐行底（撑高这一行，不向上溢出盖住上一行），遮罩的底色只盖遮罩里的字。
 * 遮罩走原生文字流、不占行内槽位见 [InlineFlowSingleTextTest]，点一下揭开见 [BBCodeMaskedLinkTest]。
 */
class InlinePlaceholderSizeTest {

    /**
     * 官方 `img.smile-dynamic` 的 `vertical-align:bottom` 是**对齐行底**，会撑高该行；
     * 译成 `TextBottom`（对齐文字底）不改变行高，3.4em 高的表情会从基线向上凸出、遮挡上一行。
     * 小表情一行高，居中对齐。
     */
    @Test
    fun `大表情对齐行底，小表情与认不出的居中`() {
        assertEquals(PlaceholderVerticalAlign.Bottom, SmileySizes.verticalAlignOf("musume_15"))
        assertEquals(PlaceholderVerticalAlign.Bottom, SmileySizes.verticalAlignOf("blake_01"))
        assertEquals(PlaceholderVerticalAlign.TextCenter, SmileySizes.verticalAlignOf("bgm38"))
        assertEquals(PlaceholderVerticalAlign.TextCenter, SmileySizes.verticalAlignOf("bgm999"))
    }

    /** 长遮罩折行时底色按字走：底色的区间与遮罩里的字一一对齐，不多盖前文或整行空隙。 */
    @Test
    fun `遮罩底色的区间正好是遮罩里的字`() {
        val longMaskContent = "这是一段用于验证长遮罩折行的虚构文字，长度超过三十个汉字并包含标点符号与换行边界。"
        val raw = "这是虚构的前文，[mask]$longMaskContent[/mask]"
        val (annotated, _) = BBCodeParser.parse(raw).nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = null, onUserClick = null).toRows()
            .filterIsInstance<Row.InlineFlow>().single().items.toInlineAnnotated(imageMarker = "[图片]")

        val maskStart = annotated.text.indexOf(longMaskContent)
        assertTrue(maskStart >= 0, "长遮罩必须原样保留在文本流中")
        val maskRange = annotated.spanStyles.firstOrNull { it.item.background == Color(0xFF555555) }
        assertNotNull(maskRange, "必须存在 0xFF555555 遮罩背景 SpanStyle")
        assertEquals(maskStart, maskRange.start, "遮罩背景起始位置必须严格对齐遮罩首字符")
        assertEquals(maskStart + longMaskContent.length, maskRange.end, "遮罩背景结束位置必须严格对齐遮罩尾字符")
    }
}
