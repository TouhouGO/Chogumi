package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.common.bbcode.plainText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 契约测试：mask 必须作为**行内元素**参与正常排版，严禁强制换行。
 *
 * 真源：bgm.tv 官方 `bangumi.min.css` 的 `span.text_mask` 规则只有
 * `border-radius:2px; padding:0 5px; position:relative` 与内层 `opacity:0`，
 * **没有任何块级属性**——它在页面里是行内 span，不改变文字流。
 * 服务端注入的内联样式为 `background-color:#555;color:#555;border:1px solid #555`。
 */
class BBCodeMaskInlineTest {

    private val testLinkColor = Color.Blue

    @Test
    fun `mask 后面的文字必须继续接在同一行内而非被断开`() {
        val ast = BBCodeParser.parse("是相当没有意思[mask]甚至是充满妄想[/mask]的角色了")
        val blocks = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)
        val rows = blocks.toRows()

        assertEquals(
            1,
            rows.size,
            "mask 前后文字必须保持同一行内流，当前行数为 ${rows.size}",
        )

        val inline = rows.single()
        assertTrue(inline is Row.InlineFlow, "该行必须是 InlineFlow，当前为 $inline")
        val texts = inline.items.filterIsInstance<Block.Inline>().joinToString("") { it.text.text }
        assertTrue(texts.contains("是相当没有意思"), "mask 前的文字必须保留在同一行内流，当前为: $texts")
        assertTrue(texts.contains("的角色了"), "mask 后的文字必须继续接在同一行内流，当前为: $texts")
    }

    @Test
    fun `mask 必须保留原文参与排版而非定长占位符`() {
        val ast = BBCodeParser.parse("[mask]前世男的忌日[/mask]")
        val blocks = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)
        val rows = blocks.toRows()

        val inline = rows.single() as Row.InlineFlow
        val mask = inline.items.filterIsInstance<Block.SpoilerBlock>().single()
        assertEquals(
            "前世男的忌日",
            mask.children.joinToString("") { it.plainText() },
            "遮罩长度必须恒等于原文自然排版长度",
        )
    }

    @Test
    fun `mask 必须与前后文字处于同一行内流且顺序正确`() {
        val ast = BBCodeParser.parse("鲁迪的生日 &[mask]前世男的忌日[/mask]")
        val blocks = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)
        val rows = blocks.toRows()

        val inline = rows.single() as Row.InlineFlow
        // 顺序必须是：前文 -> mask -> （若有）后文
        val kinds = inline.items.map { it::class.simpleName }
        assertEquals(
            listOf("Inline", "SpoilerBlock"),
            kinds,
            "mask 必须排在前序文字之后、且同处一个行内流，当前顺序为 $kinds",
        )
    }
}
