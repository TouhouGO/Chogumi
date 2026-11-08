package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * BBCode 的用户节点与表情代码判定：
 *
 * 1. [user] 节点有 Medium 样式与 Clickable 链接，点击回调传递用户名；
 * 2. resolvableSmileyCode 是表情代码的唯一判定（TimelineBodySegment 与 BBCodeText 都调它）：认得的带不带括号都认，越界与普通括号不认。
 */
class BBCodeUserAndSmileyCodeTest {
    @Test
    fun `无参数用户标签经解析渲染点击正文目标`() {
        assertParsedClick("[user] alice [/user]", expectedTarget = "alice", expectedLabel = " alice ")
        assertParsedClick("[user][b]alice[/b][/user]", expectedTarget = "alice", expectedLabel = "alice")
    }

    @Test
    fun `用户标签参数优先且去除目标首尾空白`() {
        assertParsedClick("[user= alice ]Bob[/user]", expectedTarget = "alice", expectedLabel = "Bob")
    }

    @Test
    fun `空用户目标经解析渲染仍不可点`() {
        for (source in listOf("[user][/user]", "[user]   [/user]", "[user= ]Bob[/user]")) {
            val ast = BBCodeParser.parse(source)
            val node = ast.nodes.single() as BBCodeNode.User
            assertEquals("", node.username)
            val texts = ast.nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = null, onUserClick = {})
                .filterIsInstance<Block.Inline>().map { it.text }
            assertTrue(texts.all { it.getLinkAnnotations(0, it.length).isEmpty() }, source)
        }
    }

    private fun assertParsedClick(source: String, expectedTarget: String, expectedLabel: String) {
        var clicked: String? = null
        val ast = BBCodeParser.parse(source)
        val node = ast.nodes.single() as BBCodeNode.User
        assertEquals(expectedTarget, node.username, "用户目标只由解析器定下")
        val text = ast.nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = null, onUserClick = { clicked = it })
            .filterIsInstance<Block.Inline>().single().text
        assertEquals(expectedLabel, text.text)
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Clickable
        requireNotNull(link.linkInteractionListener).onClick(link)
        assertEquals(expectedTarget, clicked, "实际渲染链接应交出解析器目标")
    }

    @Test
    fun `user 节点接通 onUserClick 并生成 Clickable 链接与主题色样式`() {
        var clickedUser: String? = null
        val userNode = BBCodeNode.User(
            username = "clannad",
            children = listOf(BBCodeNode.Text("古河渚")),
        )
        val linkColor = Color.Magenta
        val blocks = listOf(userNode).toBlocks(linkColor, darkTheme = false, onInAppLink = null, onUserClick = { clickedUser = it })

        assertEquals(1, blocks.size)
        val inlineBlock = blocks[0] as Block.Inline
        val annotated = inlineBlock.text

        // 验证文本内容
        assertEquals("古河渚", annotated.text)

        // 验证包含 LinkAnnotation.Clickable 且 tag 为 user-clannad
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        val link = links[0].item as LinkAnnotation.Clickable
        assertEquals("user-clannad", link.tag)

        // 验证点击事件触发 onUserClick 回调
        link.linkInteractionListener?.onClick(link)
        assertEquals("clannad", clickedUser)

        // 验证样式带有 linkColor
        val linkStyle = link.styles?.style
        assertNotNull(linkStyle, "user 链接必须带有样式")
        assertEquals(linkColor, linkStyle.color)
        assertTrue(annotated.spanStyles.any { it.start == 0 && it.end == annotated.length && it.item.fontWeight == FontWeight.Medium }, "用户名整段是 Medium 字重")
    }

    @Test
    fun `user 节点在无 onUserClick 时依然保留 Medium 样式但无 Clickable 链接`() {
        val userNode = BBCodeNode.User(
            username = "clannad",
            children = listOf(BBCodeNode.Text("古河渚")),
        )
        val linkColor = Color.Cyan
        val blocks = listOf(userNode).toBlocks(linkColor, darkTheme = false, onInAppLink = null, onUserClick = null)

        val inlineBlock = blocks[0] as Block.Inline
        val annotated = inlineBlock.text

        assertEquals("古河渚", annotated.text)
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertTrue(links.isEmpty(), "未提供 onUserClick 时不应生成 Clickable 链接")
        val spanStyles = annotated.spanStyles
        assertTrue(spanStyles.isNotEmpty(), "未提供 onUserClick 时依然应保留主题色样式")
        assertEquals(linkColor, spanStyles[0].item.color)
        assertTrue(spanStyles.any { it.start == 0 && it.end == annotated.length && it.item.fontWeight == FontWeight.Medium }, "没有点击回调时用户名整段仍是 Medium 字重")
    }

    @Test
    fun `resolvableSmileyCode 判定纯函数统一且覆盖常规与异常情况`() {
        // 常规内置与按需表情：必须可解析
        assertEquals("bgm38", resolvableSmileyCode("bgm38"))
        assertEquals("bgm38", resolvableSmileyCode("(bgm38)"))
        assertEquals("musume_1", resolvableSmileyCode("musume_1"))
        assertEquals("musume_1", resolvableSmileyCode("(musume_1)"))

        // 越界代码：必须返回 null
        assertNull(resolvableSmileyCode("bgm999"))
        assertNull(resolvableSmileyCode("(bgm999)"))

        // 普通括号或文本：必须返回 null
        assertNull(resolvableSmileyCode("(笑)"))
        assertNull(resolvableSmileyCode("hello"))
        assertNull(resolvableSmileyCode(""))
    }
}
