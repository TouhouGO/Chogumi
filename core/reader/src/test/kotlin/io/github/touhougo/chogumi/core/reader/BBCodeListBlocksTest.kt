package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** 列表切成什么块、不认识的标签碰上表情怎么还原，以及块级插件的接法。 */
class BBCodeListBlocksTest {

    private fun blocksOf(source: String, plugin: BBCodeBlockPlugin? = null) =
        BBCodeParser.parse(source).nodes.toBlocks(Color.Blue, darkTheme = false, onInAppLink = null, onUserClick = null, plugin = plugin)

    private fun List<Row>.lines(): List<String> = filterIsInstance<Row.InlineFlow>().map { row ->
        row.items.joinToString("") { item ->
            when (item) {
                is Block.Inline -> item.text.text
                is Block.Smiley -> "(${item.rawCode})"
                else -> ""
            }
        }
    }

    @Test
    fun `列表一项一行，方括号不进正文`() {
        val source = "统计。\n[list]\n[*]投票：选哪个？\n[*](bgm38) : ♂\n[*](bgm124) : ♀\n" +
            "[*][url=https://bgm.tv/dev/app/3792]投票组件[/url]\n[/list]\n完"
        assertEquals(
            listOf("统计。", "投票：选哪个？", "(bgm38) : ♂", "(bgm124) : ♀", "投票组件", "完"),
            blocksOf(source).toRows().lines(),
        )
    }

    @Test
    fun `列表紧跟在字后面也另起一行`() {
        assertEquals(listOf("前", "甲", "后"), blocksOf("前[list][*]甲[/list]后").toRows().lines())
    }

    @Test
    fun `外层样式带进列表每一项`() {
        val item = blocksOf("[b][list][*]粗[/list][/b]").filterIsInstance<Block.Inline>().single()
        assertEquals("粗", item.text.text)
        assertFalse(item.text.spanStyles.isEmpty(), "外层 [b] 的样式要带进项里")
    }

    @Test
    fun `不认识的标签包着表情时方括号只出现一次`() {
        val lines = blocksOf("[sup]上(bgm38)下[/sup]").toRows().lines()
        assertEquals(listOf("[sup]上(bgm38)下[/sup]"), lines)
    }

    @Test
    fun `插件认下的列表整块交给插件，独占一行`() {
        val content = FakeContent("投票")
        val blocks = blocksOf("前[list][*]甲[/list]后", plugin = FakePlugin(content))
        val rows = blocks.toRows()
        assertEquals(Row.Single(Block.Plugged(content)), rows[1])
        assertEquals(listOf("前", "后"), rows.lines())
    }

    @Test
    fun `插件认不出时照常一项一行`() {
        assertEquals(listOf("甲", "乙"), blocksOf("[list][*]甲[*]乙[/list]", plugin = FakePlugin(null)).toRows().lines())
    }

    @Test
    fun `折叠线不切开插件块`() {
        val rows = listOf(Row.InlineFlow(emptyList()), Row.Single(Block.Plugged(FakeContent("卡"))), Row.InlineFlow(emptyList()))
        // 第一行占 1 行；插件块 5 行高，预算 3 行放不下：整块留在折叠线以上，折叠线落在它底边
        val line = foldLineOf(
            budget = 3,
            rows = rows,
            tops = intArrayOf(0, 10, 60),
            heights = intArrayOf(10, 50, 10),
            textLayouts = arrayOfNulls(3),
            sampleLineHeight = { 10 },
        )
        assertEquals(60, line)
        assertNull(foldLineOf(3, rows.take(2), intArrayOf(0, 10), intArrayOf(10, 50), arrayOfNulls(2)) { 10 }, "插件块在最后一行时整段不折")
    }

    private data class FakeContent(val name: String) : BBCodeBlockContent {
        @Composable
        override fun Content() = Unit
    }

    private class FakePlugin(private val content: BBCodeBlockContent?) : BBCodeBlockPlugin {
        override fun claim(list: BBCodeNode.ListBlock): BBCodeBlockContent? = content
    }
}
