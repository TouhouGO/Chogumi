package io.github.touhougo.chogumi.core.reader

import androidx.compose.ui.graphics.Color
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 契约与功能测试：BBCodeText 必须正确处理裸换行与表情后换行，
 * 保证多行文本在分行排版中产生独立行，杜绝多行内容被折叠挤在一行。
 */
class BBCodeTextNewlineTest {

    private val testLinkColor = Color.Blue

    @Test
    fun `含裸换行的纯文本解析后产生独立多行`() {
        val ast = BBCodeParser.parse("第一行\n第二行")
        val blocks = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)
        val rows = blocks.toRows()

        // 必须产生至少 2 行
        assertEquals(2, rows.size, "第一行\\n第二行 必须分拆为 2 行，当前行数为 ${rows.size}")
    }

    @Test
    fun `Windows 换行 CRLF 与裸 CR 均正确转换为分行`() {
        val ast = BBCodeParser.parse("第一行\r\n第二行\r第三行")
        val blocks = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)
        val rows = blocks.toRows()

        assertEquals(3, rows.size, "CRLF 与 CR 换行必须正确拆分为 3 行，当前行数为 ${rows.size}")
    }

    @Test
    fun `连续多个换行最多折叠为一个空行`() {
        val ast = BBCodeParser.parse("第一段\n\n\n\n\n第二段")
        val blocks = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)
        val rows = blocks.toRows()

        // 第一段 + 1个空行 + 第二段 = 3 行
        assertEquals(3, rows.size, "连续 5 个换行必须折叠为最多 1 个空行（共 3 行），当前行数为 ${rows.size}")
    }

    @Test
    fun `表情后的换行必须切出独立行且不挤在一行`() {
        val rawContent = "示例内容(bgm116)\n续行"
        val ast = BBCodeParser.parse(rawContent)
        val blocks = ast.nodes.toBlocks(testLinkColor, darkTheme = false, onInAppLink = null, onUserClick = null)
        val rows = blocks.toRows()

        // 第一行包含「示例内容」与表情 (bgm116)，第二行包含「续行」
        assertEquals(2, rows.size, "表情后换行必须拆分为 2 行，当前行数为 ${rows.size}")
    }
}
