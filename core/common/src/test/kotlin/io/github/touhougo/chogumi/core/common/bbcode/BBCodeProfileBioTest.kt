package io.github.touhougo.chogumi.core.common.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 个人简介里的 BBCode 排版：多段文字、删除线、链接与换行归一。 */
class BBCodeProfileBioTest {

    private val sampleBio = """
        这是一个虚构的个人简介。
        第一行没有格式，第二行继续描述示例动画和示例游戏。

        [s]这是一段示例删除线。[/s]

        每段末尾保留一个换行。

        [url=https://example.invalid/profile]示例评分统计[/url]
        最后一行用来验证链接后面的文字仍在正文里。
    """.trimIndent()

    @Test
    fun `简介里的删除线被解析成 strike 节点`() {
        val ast = BBCodeParser.parse(sampleBio)
        val strike = ast.nodes.filterIsInstance<BBCodeNode.Strike>().singleOrNull()
        assertTrue(strike != null, "[s] 应被解析成 Strike")
        assertEquals("这是一段示例删除线。", strike.children.joinToString("") { it.plainText() })
    }

    @Test
    fun `简介里的链接被解析成可点击的 url 节点`() {
        val ast = BBCodeParser.parse(sampleBio)
        val url = ast.nodes.filterIsInstance<BBCodeNode.Url>().singleOrNull()
        assertTrue(url != null, "[url=...] 应被解析成 Url")
        assertEquals("https://example.invalid/profile", url.url)
        assertEquals("示例评分统计", url.children.joinToString("") { it.plainText() })
    }

    @Test
    fun `标签不遮蔽前后的普通文本`() {
        val text = BBCodeParser.parse(sampleBio).plainText
        // 删除线之前与之后的内容都必须完整保留
        assertTrue(text.contains("这是一个虚构的个人简介。"))
        assertTrue(text.contains("每段末尾保留一个换行。"))
        assertTrue(text.contains("最后一行用来验证"))
        // 方括号不能泄漏
        assertTrue(!text.contains("[s]") && !text.contains("[url"), "标签不该出现在正文里")
    }

    @Test
    fun `换行归一成单个 LF 以免渲染出方块字符`() {
        // 裸 \r 在 Compose 文本里会渲染成方块，所以解析期统一成 \n。
        assertEquals("第一行\n第二行", BBCodeParser.parse("第一行\r\n第二行").plainText)
        assertEquals("第一行\n第二行", BBCodeParser.parse("第一行\r第二行").plainText)
        assertEquals("第一行\n第二行", BBCodeParser.parse("第一行\n第二行").plainText)
    }

    @Test
    fun `整段简介里只有预期的那两个标签`() {
        val ast = BBCodeParser.parse(sampleBio)
        val structural = ast.nodes.filter { it !is BBCodeNode.Text }
        assertEquals(
            listOf("Strike", "Url"),
            structural.map { it::class.simpleName },
            "简介里只应有 [s] 与 [url] 两个结构节点",
        )
    }
}
