package io.github.touhougo.chogumi.core.common.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 复杂 BBCode 组合：粗体、多个图片、删除线内表情、表情代码与引用后的正文。 */
class BBCodeComplexContentTest {

    private val sampleReply = """
        [b]示例标题[/b] 这是一段用于覆盖复杂组合的虚构文字，包含一个表情(bgm94)。
        [img]https://example.invalid/photo/example-1.jpg[/img]
        [img]https://example.invalid/photo/example-2.jpg[/img]
        [img]d6/c4/example-3.jpg[/img]

             第三行带缩进，仍应作为同一段中的换行保留。
    """.trimIndent()

    @Test
    fun `复杂回复里的粗体与表情都被正确识别`() {
        val ast = BBCodeParser.parse(sampleReply)
        assertTrue(ast.nodes.any { it is BBCodeNode.Bold }, "应识别出 [b]")
        val smileys = ast.nodes.filterIsInstance<BBCodeNode.Smiley>()
        assertEquals(listOf("bgm94"), smileys.map { it.code })
    }

    @Test
    fun `复杂回复里的图片解析成绝对地址`() {
        val ast = BBCodeParser.parse(sampleReply)
        val images = ast.nodes.filterIsInstance<BBCodeNode.Image>()
        assertEquals(3, images.size)
        assertEquals("https://example.invalid/photo/example-1.jpg", images[0].url)
        assertEquals("https://example.invalid/photo/example-2.jpg", images[1].url)
        assertEquals("https://lain.bgm.tv/pic/photo/l/d6/c4/example-3.jpg", images[2].url)
    }

    @Test
    fun `复杂回复的纯文本不残留任何 BBCode 方括号`() {
        val text = BBCodeParser.parse(sampleReply).plainText
        assertTrue(text.contains("示例标题"))
        assertTrue(text.contains("(bgm94)"))
        assertTrue(!text.contains("[b]") && !text.contains("[/b]"), "方括号不该泄漏到正文里")
        assertTrue(!text.contains("[img]"), "图片标签不该泄漏到正文里")
    }

    @Test
    fun `整段删除线内的表情被正确保留`() {
        val source = "[s]  这是一段虚构的删除线(bgm72)，覆盖文字与表情混排[/s]\\r\\n后面的正文"
        val ast = BBCodeParser.parse(source)
        val strike = ast.nodes.filterIsInstance<BBCodeNode.Strike>().singleOrNull()
        assertTrue(strike != null, "应识别出整段 [s]")
        assertEquals(1, strike.children.filterIsInstance<BBCodeNode.Smiley>().size)
        assertTrue(ast.plainText.endsWith("后面的正文"))
    }

    @Test
    fun `角色评论中的嵌套引用与后随正文被正确解析`() {
        val quoteReply = "[quote][b]示例引用者[/b] 说: 这是虚构的引用句[/quote]\n这是引用后的正文"
        val ast = BBCodeParser.parse(quoteReply)
        val quoteNodes = ast.nodes.filterIsInstance<BBCodeNode.Quote>()
        assertEquals(1, quoteNodes.size, "必须识别出 [quote] 节点")
        val quote = quoteNodes.first()
        assertTrue(
            quote.children.any { it is BBCodeNode.Bold },
            "[quote] 内部的 [b] 必须被正确解析为 Bold 节点",
        )
        val textNodes = ast.nodes.filterIsInstance<BBCodeNode.Text>()
        assertTrue(
            textNodes.any { it.value.contains("这是引用后的正文") },
            "[quote] 闭合标签后的正文文本不得丢失",
        )
    }
}
