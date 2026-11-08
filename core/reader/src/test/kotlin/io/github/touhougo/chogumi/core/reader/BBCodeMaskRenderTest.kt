package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.common.bbcode.plainText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [mask] 遮罩渲染（对齐 bgm.tv 网页版原生行内文字流）：
 * 1. 遮罩里的文字照常参与排版，未揭开时字色与底色同为官方内联样式的 `#555`（文字占位可见，内容不可见）；
 * 2. 遮罩里的表情、样式、代码、用户标签与块级内容都保留，未揭开时不泄露用户链接；
 * 3. 删除线里的表情与遮罩不被丢弃。
 * 遮罩与前后文字同在一个行内流、不占行内槽位见 [InlineFlowSingleTextTest] 与 [BBCodeMaskInlineTest]。
 */
class BBCodeMaskRenderTest {

    private val testLinkColor = Color.Blue

    private companion object {
        const val IMAGE_MARKER = "[图片]"
    }

    @Test
    fun `遮罩中的表情保留在文字之间，未知表情保留原文`() {
        for (revealed in listOf(false, true)) {
            val (text, placed) = renderMask("[mask]front(bgm38)back(bgm999)[/mask]", revealed)
            assertEquals("frontback(bgm999)", text.text.filter { it != '�' && it != '￼' })
            assertEquals(listOf(InlineItem.Smiley("bgm38")), placed.map { it.item })
            assertEquals(5, text.text.indexOfFirst { it == '�' || it == '￼' })
            assertEquals(!revealed, placed.single().hidden)
            assertEquals("mask-0", (text.getLinkAnnotations(5, 6).single().item as LinkAnnotation.Clickable).tag)
        }
    }

    @Test
    fun `遮罩中粗体里的表情与文字都保留样式`() {
        for (revealed in listOf(false, true)) {
            val (text, placed) = renderMask("[mask][b]front(bgm38)back[/b][/mask]", revealed)
            assertEquals(1, placed.size)
            val smileyOffset = text.text.indexOfFirst { it == '�' || it == '￼' }
            assertEquals(5, smileyOffset)
            assertTrue(text.spanStyles.any { it.start <= smileyOffset && it.end > smileyOffset && it.item.fontWeight == FontWeight.Bold })
        }
    }

    @Test
    fun `遮罩中的代码文字在隐藏和揭示时都保留`() {
        for (revealed in listOf(false, true)) {
            val (text, _) = renderMask("[mask]front[code]val x = 1[/code]back[/mask]", revealed)
            assertEquals("frontval x = 1back", text.text)
            assertTrue(text.spanStyles.any { it.start == 5 && it.end == 14 && it.item.fontFamily == FontFamily.Monospace })
        }
    }

    @Test
    fun `遮罩与外部表情使用不同的行内标识`() {
        val (_, placed) = renderMask("(bgm38)[mask](bgm38)[/mask](bgm38)", revealed = false)
        assertEquals(3, placed.size)
        assertEquals(3, placed.map { it.id }.toSet().size)
        assertEquals(listOf(false, true, false), placed.map { it.hidden })
    }

    @Test
    fun `遮罩中用户标签保留嵌套样式表情与点击`() {
        for (revealed in listOf(false, true)) {
            var clicked: String? = null
            val (text, placed) = renderMask("[mask][user=alice][b](bgm38)[/b][/user][/mask]", revealed) { clicked = it }
            assertEquals(listOf(InlineItem.Smiley("bgm38")), placed.map { it.item })
            assertEquals(!revealed, placed.single().hidden)
            assertTrue(text.spanStyles.any { it.start == 0 && it.end == 1 && it.item.fontWeight == FontWeight.Bold })
            val links = text.getLinkAnnotations(0, text.length).map { it.item }.filterIsInstance<LinkAnnotation.Clickable>()
            assertTrue(links.any { it.tag == "mask-0" })
            val user = links.singleOrNull { it.tag == "user-alice" }
            if (revealed) {
                assertNotNull(user)
                user.linkInteractionListener?.onClick(user)
                assertEquals("alice", clicked)
            } else {
                assertEquals(null, user)
            }
        }
    }

    @Test
    fun `遮罩中无用户目标或回调时仍保留用户标签内容`() {
        for (source in listOf("[mask][user= ][b](bgm38)[/b][/user][/mask]", "[mask][user=alice][b](bgm38)[/b][/user][/mask]")) {
            val (text, placed) = renderMask(source, revealed = true)
            assertEquals(listOf(InlineItem.Smiley("bgm38")), placed.map { it.item })
            assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
            assertEquals(listOf("mask-0"), text.getLinkAnnotations(0, text.length).map { (it.item as LinkAnnotation.Clickable).tag })
        }
    }

    @Test
    fun `遮罩中的块级内容只留文字：图片换成标记，引用与段落展开，列表一项一行`() {
        val source = "[mask]前[img]https://example.com/a.png[/img][quote][b]引用[/b][/quote]" +
            "[center]居中[/center][list][*]甲[*][b]乙[/b][/list]后[/mask]"
        for (revealed in listOf(false, true)) {
            val (text, placed) = renderMask(source, revealed)
            assertEquals("前${IMAGE_MARKER}引用居中甲\n乙后", text.text)
            assertTrue(placed.isEmpty())
            assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "引用" })
            assertTrue(text.spanStyles.any { it.item.fontWeight == FontWeight.Bold && text.text.substring(it.start, it.end) == "乙" })
        }
    }

    @Test
    fun `正文用户标签里的图片走图片块，前后文字仍为用户链接`() {
        val source = "[user=alice]前[img]https://example.com/a.png[/img]后[/user]"
        val blocks = BBCodeParser.parse(source).nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = {})
        assertEquals("https://example.com/a.png", blocks.filterIsInstance<Block.Image>().single().url)
        val text = blocks.filterIsInstance<Block.Inline>().map { it.text }
        assertEquals(listOf("前", "后"), text.map { it.text })
        assertTrue(text.all { (it.getLinkAnnotations(0, it.length).single().item as LinkAnnotation.Clickable).tag == "user-alice" })
    }

    @Test
    fun `正文用户标签中的表情与原文降级都保留用户点击`() {
        for (code in listOf("bgm38", "bgm999")) {
            for (source in listOf("[user=alice]($code)[/user]", "[b][user=alice][i]($code)[/i][/user][/b]")) {
                var clicked: String? = null
                val items = BBCodeParser.parse(source).nodes
                    .toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = { clicked = it })
                    .toRows().filterIsInstance<Row.InlineFlow>().single().items
                val (text, placed) = items.toInlineAnnotated(onUserClick = { clicked = it }, imageMarker = IMAGE_MARKER)
                if (code == "bgm38") assertEquals(listOf(InlineItem.Smiley(code)), placed.map { it.item })
                else assertEquals("($code)", text.text)
                val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Clickable
                assertEquals("user-alice", link.tag)
                link.linkInteractionListener?.onClick(link)
                assertEquals("alice", clicked)
            }
        }
    }

    @Test
    fun `遮罩内用户标签里只有空引用或空列表时显示 @用户名`() {
        for (content in listOf("[quote][/quote]", "[list][/list]")) {
            for (revealed in listOf(false, true)) {
                val (text, _) = renderMask("[mask][user=alice]$content[/user][/mask]", revealed)
                assertEquals("@alice", text.text)
            }
        }
    }

    @Test
    fun `外层样式包住遮罩时块内容保留用户链接和嵌套样式`() {
        val cases = listOf(
            "[quote]引用[/quote]" to "引用",
            "[img]https://example.com/a.png[/img]" to IMAGE_MARKER,
            "[code]代码[/code]" to "代码",
            "[list][*]一[*]二[/list]" to "一\n二",
        )
        for ((content, expected) in cases) {
            for (revealed in listOf(false, true)) {
                val source = "[b][mask][user=alice][i]$content[/i][/user][/mask][/b]"
                var clicked: String? = null
                val (text, _) = renderMask(source, revealed) { clicked = it }
                assertEquals(expected, text.text)
                for (offset in text.text.indices) {
                    val style = text.spanStyles.filter { offset >= it.start && offset < it.end }
                        .fold(SpanStyle()) { merged, range -> merged.merge(range.item) }
                    assertEquals(FontWeight.Bold, style.fontWeight, content)
                    assertEquals(FontStyle.Italic, style.fontStyle, content)
                }
                val links = text.getLinkAnnotations(0, text.length).map { it.item }.filterIsInstance<LinkAnnotation.Clickable>()
                assertTrue(links.any { it.tag == "mask-0" })
                val user = links.singleOrNull { it.tag == "user-alice" }
                if (revealed) {
                    assertNotNull(user, content)
                    user.linkInteractionListener?.onClick(user)
                    assertEquals("alice", clicked)
                } else {
                    assertEquals(null, user)
                }
            }
        }
    }

    private fun renderMask(raw: String, revealed: Boolean, onUserClick: ((String) -> Unit)? = null) =
        BBCodeParser.parse(raw).nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)
            .toRows().filterIsInstance<Row.InlineFlow>().single().items.toInlineAnnotated(revealSpoilers = revealed, onUserClick = onUserClick, imageMarker = IMAGE_MARKER)

    @Test
    fun `未揭开的遮罩保留原文，字色与底色同为官方的 555 深灰`() {
        // 官方服务端注入：background-color:#555;color:#555;border:1px solid #555
        val (annotated, _) = renderMask("[mask]不可见内容[/mask]", revealed = false)
        assertEquals("不可见内容", annotated.text, "遮罩原文文字必须保留在文本流中")
        val maskSpan = annotated.spanStyles.firstOrNull { it.item.background == Color(0xFF555555) }
        assertNotNull(maskSpan, "必须包含 #555 背景的 SpanStyle")
        assertEquals(Color(0xFF555555), maskSpan.item.color, "未揭示态文字颜色必须与背景同色（不可见）")
    }

    @Test
    fun `删除线内的表情不被丢弃且带着删除线`() {
        val raw = "[s]，示例文字(bgm38)[/s]"
        val ast = BBCodeParser.parse(raw)
        val blocks = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)
        val (text, placed) = blocks.toRows().filterIsInstance<Row.InlineFlow>().single().items.toInlineAnnotated(imageMarker = IMAGE_MARKER)

        assertEquals(listOf(InlineItem.Smiley("bgm38")), placed.map { it.item }, "删除线内嵌的表情 (bgm38) 必须登记成行内表情，当前块为: $blocks")
        val smileyOffset = text.text.length - 1
        assertTrue(text.spanStyles.any { it.start <= smileyOffset && it.end > smileyOffset && it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun `删除线与mask叠加时mask被正确提取且保留删除线语义`() {
        val raw = "[s]7.示例段落[mask]示例遮罩[/mask][/s]"
        val ast = BBCodeParser.parse(raw)
        val blocks = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)

        val spoilerBlocks = blocks.filterIsInstance<Block.SpoilerBlock>()
        assertEquals(1, spoilerBlocks.size, "删除线内嵌的 mask 必须被正确提取为 Block.SpoilerBlock，当前列表为: $blocks")

        val children = spoilerBlocks.first().children
        assertTrue(
            children.any { it is BBCodeNode.Strike || it.plainText().contains("示例遮罩") },
            "SpoilerBlock 内部必须保留文字与嵌套删除线语义",
        )
    }
}
