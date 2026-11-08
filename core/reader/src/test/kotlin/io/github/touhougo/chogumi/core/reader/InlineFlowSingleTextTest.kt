package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 行内「文字 + 表情 + 遮罩」必须合成单一文本流，而非多个并列块。
 *
 * 表情是基线对齐的行内元素，折行必须由文本排版引擎统一决定。断言不比对
 * `appendInlineContent` 的占位符字符本身，只断言结构与顺序。
 */
class InlineFlowSingleTextTest {

    private val testLinkColor = Color.Blue

    private fun inlineOf(raw: String): Pair<AnnotatedString, List<Placed>> {
        val ast = BBCodeParser.parse(raw)
        val rows = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null).toRows()
        val inline = rows.single() as Row.InlineFlow
        return inline.items.toInlineAnnotated(imageMarker = "[图片]")
    }

    /** 去掉行内内容的占位字符，只留可见文字 —— 用于断言文字本身没丢没乱。 */
    private fun AnnotatedString.visibleText(): String =
        text.filter { it != '�' && it != '￼' }

    @Test
    fun `文字与末尾连续表情必须合成单一文本流`() {
        val (annotated, placed) = inlineOf("討厭魯迪吧(bgm85)(bgm85)")

        assertEquals(
            "討厭魯迪吧",
            annotated.visibleText(),
            "可见文字必须完整保留在同一条文本流里",
        )
        assertEquals(
            2,
            placed.size,
            "末尾两个表情必须都登记为行内内容（而非被拆成并列块后被挤到下一行），当前为 $placed",
        )
    }

    @Test
    fun `连续同名表情必须获得互不相同的行内id`() {
        val (_, placed) = inlineOf("(bgm85)(bgm85)")

        val ids = placed.map { it.id }
        assertEquals(
            ids.size,
            ids.toSet().size,
            "行内 id 必须唯一 —— 共用 id 会让后一个表情覆盖前一个（inlineContent 按 id 索引），当前为 $ids",
        )
    }

    @Test
    fun `表情夹在文字中间时顺序必须保持`() {
        val (annotated, placed) = inlineOf("笑死(bgm38)确实")

        assertEquals("笑死确实", annotated.visibleText(), "前文与后文都必须保留")
        assertEquals(1, placed.size)
        assertEquals(InlineItem.Smiley("bgm38"), placed.single().item)

        // 顺序：表情必须夹在「笑死」与「确实」之间
        val smileyAt = annotated.text.indexOf('�').takeIf { it >= 0 }
            ?: annotated.text.indexOf('￼')
        assertTrue(smileyAt in 2..2, "表情占位符必须位于前文之后、后文之前，当前位置为 $smileyAt")
    }

    @Test
    fun `不可解析的表情不产生行内内容且保留原文`() {
        val (annotated, placed) = inlineOf("测试(bgm999)结束")

        assertTrue(
            placed.isEmpty(),
            "不可解析的 (bgm999) 不应产生行内内容，当前为 $placed",
        )
        assertEquals(
            "测试(bgm999)结束",
            annotated.visibleText(),
            "不可解析的表情必须原样保留原文（不得静默丢弃）",
        )
    }

    @Test
    fun `遮罩必须是行内原生文本流而非并列块`() {
        val (annotated, placed) = inlineOf("生日 &[mask]忌日[/mask]")

        // 1. 遮罩文本与前序文本必须同处单一文本流，严丝合缝排版且不被截断
        assertEquals("生日 &忌日", annotated.visibleText(), "遮罩文本与前后文字必须完整保留在单一文本流中")

        // 2. 文本遮罩直接以 SpanStyle 行内呈现，不产生占位槽位，杜绝矩形块无法换行与挤占空行缺陷
        assertTrue(
            placed.isEmpty(),
            "纯文本遮罩无需占位槽位，当前 placed 为 $placed",
        )

        // 3. 遮罩文本范围必须带有 0xFF555555 遮罩背景色
        val maskSpan = annotated.spanStyles.firstOrNull { it.item.background == Color(0xFF555555) }
        assertNotNull(maskSpan, "遮罩文本必须带有 0xFF555555 遮罩背景色 SpanStyle")
    }
}
