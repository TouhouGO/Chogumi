package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.em
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeAlign
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.common.bbcode.SectionLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 段落容器（`[center]` 一类、`[indent]`、`[float]`）与链接里的图片切成什么块。 */
class BBCodeSectionBlocksTest {

    private fun blocksOf(source: String) =
        BBCodeParser.parse(source).nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = null, onUserClick = null)

    @Test
    fun `center 独占一个段落块，方括号不进正文`() {
        val blocks = blocksOf("前[center]居中[/center]后")
        val section = blocks.filterIsInstance<Block.Section>().single()
        assertEquals(SectionLayout.Aligned(BBCodeAlign.Center), section.layout)
        val text = blocks.filterIsInstance<Block.Inline>().joinToString("") { it.text.text }
        assertEquals("前后", text)
        assertIs<Row.Single>(blocks.toRows()[1], "段落容器独占一行")
    }

    @Test
    fun `外层样式带进段落容器里面`() {
        val section = blocksOf("[color=grey][center]灰字[/center][/color]").single()
        assertIs<Block.Section>(section)
        val color = section.children.single()
        assertIs<BBCodeNode.Color>(color)
        assertEquals("grey", color.value)
    }

    @Test
    fun `只有空白的浮动盒不画，有字的照画`() {
        assertTrue(blocksOf("[float=right][size=24]　[/size][/float]").isEmpty())
        assertEquals(1, blocksOf("[float=right]字[/float]").filterIsInstance<Block.Section>().size)
        // 只有浮动盒是留白；居中、缩进里的空白照常是一段
        assertEquals(1, blocksOf("[center]　[/center]").filterIsInstance<Block.Section>().size)
    }

    @Test
    fun `链接里的图片画成图并带着链接，地址不当成链接文字`() {
        val blocks = blocksOf("[url=https://apps.apple.com/app/id1][img]https://a.com/badge.svg[/img][/url]")
        val image = blocks.filterIsInstance<Block.Image>().single()
        assertEquals("https://a.com/badge.svg", image.url)
        assertEquals("https://apps.apple.com/app/id1", image.link)
        assertFalse(blocks.any { it is Block.Inline && it.text.text.contains("badge.svg") })
    }

    @Test
    fun `链接里图片旁边的文字仍是链接`() {
        val blocks = blocksOf("[url=https://a.com/x]看[img]https://a.com/1.png[/img][/url]")
        val inline = blocks.filterIsInstance<Block.Inline>().single()
        assertEquals("看", inline.text.text)
        assertEquals(1, inline.text.getLinkAnnotations(0, inline.text.length).size)
    }

    @Test
    fun `浮动盒贴自己那一侧、宽度随内容，对齐与缩进的段落占满栏宽`() {
        val right = sectionGeometryOf(SectionLayout.Floating(BBCodeAlign.End))
        assertEquals(BBCodeAlign.End, right.placement)
        assertFalse(right.fill)
        assertEquals(null, right.content, "浮动盒里的对齐沿用外层")
        val center = sectionGeometryOf(SectionLayout.Aligned(BBCodeAlign.Center))
        assertEquals(BBCodeAlign.Center, center.content)
        assertTrue(center.fill)
        val indent = sectionGeometryOf(SectionLayout.Indent)
        assertTrue(indent.fill && indent.indent)
    }

    /** [text] 第一个字最终的颜色：覆盖它的样式里最后一个写了颜色的（后加的压过先加的，同渲染）。 */
    private fun AnnotatedString.colorAtStart(): Color =
        spanStyles.filter { it.start == 0 && it.item.color != Color.Unspecified }.last().item.color

    private fun inlineOf(source: String): AnnotatedString = blocksOf(source).filterIsInstance<Block.Inline>().single().text

    @Test
    fun `链接文字带外层的字号`() {
        val text = inlineOf("[size=24][url=https://a.com]GitHub[/url][/size]")
        assertTrue(text.spanStyles.any { it.start == 0 && it.end == text.length && it.item.fontSize == 1.5.em })
    }

    @Test
    fun `链接里面的颜色压过链接色，外层的颜色压不过`() {
        assertEquals(Color(0xFFF888B5), inlineOf("[url=https://a.com][color=#f888b5]动画[/color][/url]").colorAtStart())
        assertEquals(Color.Blue, inlineOf("[color=#f888b5][url=https://a.com]动画[/url][/color]").colorAtStart())
    }

    @Test
    fun `一栏结尾只有半角空格的一行不占行，全角空格照占`() {
        assertEquals(1, blocksOf("入坑作\n ").toRows().size)
        assertEquals(1, blocksOf("[float=left]入坑作\n [/float]").filterIsInstance<Block.Section>().single().children
            .let { blocksOfNodes(it) }.toRows().size)
        assertEquals(2, blocksOf("入坑作\n　").toRows().size)
    }

    @Test
    fun `一栏结尾只有不换行空格的一行也不占行`() {
        // 浮动盒以「\r\n\u00A0[/float]」收尾时，空格不能单独占一行。
        val section = blocksOf("[float=right]示例摘要。\r\n\u00A0[/float]").filterIsInstance<Block.Section>().single()
        assertEquals(1, blocksOfNodes(section.children).toRows().size)
    }

    private fun blocksOfNodes(nodes: List<BBCodeNode>) =
        nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = null, onUserClick = null)
}
