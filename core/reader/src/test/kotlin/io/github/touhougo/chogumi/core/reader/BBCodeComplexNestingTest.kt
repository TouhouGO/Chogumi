package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.common.bbcode.BBCodeAst
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.common.bbcode.plainText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 帖子里常见的复杂写法经解析器后不丢字：嵌套的 size + color + bold、`&nbsp;` 实体、各种特殊符号。
 * 删除线里的表情、遮罩与删除线叠加见 [BBCodeMaskRenderTest]，连续空行折叠见 [BBCodeTextNewlineTest]。
 */
class BBCodeComplexNestingTest {

    @Test
    fun `嵌套 size加color加bold 组合正常解析并保留子文本`() {
        val raw = "[size=25][color=red][b]示例：[/b][/color][/size]"
        val ast = BBCodeParser.parse(raw)
        assertEquals("示例：", ast.text())
        val sizeNode = ast.nodes.firstOrNull() as? BBCodeNode.Size
        assertNotNull(sizeNode)
        assertEquals(25.0, sizeNode.value)

        val colorNode = sizeNode.children.firstOrNull() as? BBCodeNode.Color
        assertNotNull(colorNode)
        assertEquals("red", colorNode.value)

        val boldNode = colorNode.children.firstOrNull() as? BBCodeNode.Bold
        assertNotNull(boldNode)
    }

    @Test
    fun `nbsp 实体被正确解析为真实不间断空格`() {
        val raw = "&nbsp;&nbsp;※示例进度..."
        val ast = BBCodeParser.parse(raw)
        assertTrue(
            ast.text().contains("  ※示例进度..."),
            "&nbsp;&nbsp; 必须被解析为真实不间断空格 \\u00A0，当前为: ${ast.text()}",
        )
    }

    @Test
    fun `特殊字符正常完整保留不丢字`() {
        val specialChars = "① ② ③ ④ 、 ※ ◎ ◉ ↓ ⭐️ ★"
        val ast = BBCodeParser.parse(specialChars)
        assertEquals(specialChars, ast.text())
    }
}

/** 整段文字（各节点的 [plainText] 拼起来）。 */
private fun BBCodeAst.text(): String = nodes.joinToString("") { it.plainText() }
