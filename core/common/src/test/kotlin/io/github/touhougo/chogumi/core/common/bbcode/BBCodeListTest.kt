package io.github.touhougo.chogumi.core.common.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `[list]` 与 `[*]`：旧站的 `<ul>` / `<li>`（next 站不认，基准按旧站，见 [BBCodeParser]）。 */
class BBCodeListTest {

    private fun list(source: String): BBCodeNode.ListBlock = assertIs(BBCodeParser.parse(source).nodes.single())

    @Test
    fun `每个星号开一项，项首尾的换行与空白去掉`() {
        assertEquals(
            BBCodeNode.ListBlock(listOf(listOf(BBCodeNode.Text("甲")), listOf(BBCodeNode.Text("乙")))),
            list("[list]\n[*]甲\n[*] 乙 \n[/list]"),
        )
    }

    @Test
    fun `贴贴投票块切成问题、表情选项与组件链接`() {
        val source = "[list]\n[*]投票：选择示例选项？\n[*](bgm38) : 甲\n[*](bgm124) : 乙\n" +
            "[*][url=https://bgm.tv/dev/app/3792]投票组件[/url]\n[/list]"
        val items = list(source).items
        assertEquals(4, items.size)
        assertEquals(listOf(BBCodeNode.Text("投票：选择示例选项？")), items[0])
        assertEquals(listOf(BBCodeNode.Smiley("bgm38"), BBCodeNode.Text(" : 甲")), items[1])
        assertIs<BBCodeNode.Url>(items[3].single())
    }

    @Test
    fun `第一个星号之前有字时自成一项，只有空白时丢掉`() {
        assertEquals(2, list("[list]前言[*]甲[/list]").items.size)
        assertEquals(1, list("[list]\n  [*]甲[/list]").items.size)
    }

    @Test
    fun `列表外和样式标签里的星号是字面文本`() {
        assertEquals(listOf(BBCodeNode.Text("[*]甲")), BBCodeParser.parse("[*]甲").nodes)
        val item = list("[list][*][b]甲[*]乙[/b][/list]").items.single()
        assertEquals(BBCodeNode.Bold(listOf(BBCodeNode.Text("甲[*]乙"))), item.single())
    }

    @Test
    fun `摘要里列表各项之间隔一个空格`() {
        assertEquals("前 甲 乙 后", excerptOf("前[list][*]甲[*]乙[/list]后"))
    }
}
