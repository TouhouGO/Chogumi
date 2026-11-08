package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 遮罩覆盖内层链接和显式颜色，未揭开时只有揭示动作，揭开后链接恢复可用。 */
class BBCodeMaskedLinkTest {

    @Test
    fun `遮罩内的链接描述和文字颜色都不能泄露`() {
        val contents = listOf(
            "[url=https://example.com]链接描述[/url]",
            "[url]https://example.com[/url]",
            "[url=https://bgm.tv/subject/1][b][color=red]条目描述[/color][/b][/url]",
            "[color=red]指定颜色[/color]",
            "[user=alice]用户描述[/user]",
        )
        for (content in contents) {
            val text = render("[url=https://example.org]外部[/url][mask]$content[/mask]")
            assertEquals(Color.Blue, text.styleAt(0).color, "遮罩外的链接色保持不变")
            for (offset in 2 until text.length) {
                assertEquals(Color(0xFF555555), text.styleAt(offset).color, content)
                assertEquals(Color(0xFF555555), text.styleAt(offset).background, content)
            }
        }
    }

    @Test
    fun `未揭开的链接只响应揭开遮罩`() {
        var clicked: Int? = null
        val text = render("[mask][url=https://example.com]链接描述[/url][/mask]", onMaskClick = { clicked = it })
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        val mask = links.single().item as LinkAnnotation.Clickable
        assertEquals("mask-0", mask.tag)
        mask.linkInteractionListener?.onClick(mask)
        assertEquals(0, clicked)
    }

    @Test
    fun `揭开后保留链接目标且描述使用揭示文字色`() {
        val text = render("[mask][url=https://example.com][color=red]链接描述[/color][/url][/mask]", revealed = true)
        assertEquals("链接描述", text.text)
        assertEquals("https://example.com", text.getLinkAnnotations(0, text.length).map { it.item }.filterIsInstance<LinkAnnotation.Url>().single().url)
        assertTrue((0 until text.length).all { text.styleAt(it).color == Color.White })
    }

    private fun render(raw: String, revealed: Boolean = false, onMaskClick: ((Int) -> Unit)? = null): AnnotatedString =
        BBCodeParser.parse(raw).nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = {}, onUserClick = {})
            .toRows().filterIsInstance<Row.InlineFlow>().single().items
            .toInlineAnnotated(revealedMasks = if (revealed) setOf(0) else emptySet(), onMaskClick = onMaskClick, linkColor = Color.Blue, imageMarker = "[图片]").first

    private fun AnnotatedString.styleAt(offset: Int): SpanStyle = spanStyles
        .filter { offset >= it.start && offset < it.end }
        .fold(SpanStyle()) { style, range -> style.merge(range.item) }
}
