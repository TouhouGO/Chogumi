package io.github.touhougo.chogumi.core.common.bbcode

import kotlin.test.Test
import kotlin.test.assertEquals

class BBCodeEditingTest {

    @Test
    fun `没有选中时插入一对标签，光标在中间`() {
        assertEquals(TextEdit("前[b][/b]后", 4, 4), TextEdit("前后", 1, 1).wrapWith(BBCodeTag.BOLD))
    }

    @Test
    fun `有选中时包起来，光标放到闭标签后面`() {
        assertEquals(TextEdit("说[mask]剧透[/mask]了", 16, 16), TextEdit("说剧透了", 1, 3).wrapWith(BBCodeTag.MASK))
    }

    @Test
    fun `反向拖选与正向拖选结果相同`() {
        assertEquals(TextEdit("说剧透了", 1, 3).wrapWith(BBCodeTag.CODE), TextEdit("说剧透了", 3, 1).wrapWith(BBCodeTag.CODE))
    }

    @Test
    fun `字号三档与网页回复框的大中小一致`() {
        assertEquals(TextEdit("[size=25]大[/size]", 17, 17), TextEdit("大", 0, 1).wrapWith(BBCodeTag.SIZE_LARGE))
        assertEquals(TextEdit("[size=12][/size]", 9, 9), TextEdit("", 0, 0).wrapWith(BBCodeTag.SIZE_MEDIUM))
        assertEquals(TextEdit("[size=10][/size]", 9, 9), TextEdit("", 0, 0).wrapWith(BBCodeTag.SIZE_SMALL))
    }

    @Test
    fun `链接替换选中的文字，文字空着时地址本身就是文字`() {
        val withLabel = TextEdit("看这里", 1, 3).link(" https://bgm.tv ", "这里")
        assertEquals(TextEdit("看[url=https://bgm.tv]这里[/url]", 29, 29), withLabel)
        assertEquals(TextEdit("[url]https://bgm.tv[/url]", 25, 25), TextEdit("", 0, 0).link("https://bgm.tv", " "))
    }

    @Test
    fun `选中的文字给链接预填，反向拖选也一样`() {
        assertEquals("这里", TextEdit("看这里", 3, 1).selected)
        assertEquals("", TextEdit("看这里", 2, 2).selected)
    }

    @Test
    fun `图片插在光标处，光标放到图片后面`() {
        assertEquals(TextEdit("图[img]https://x.png[/img]", 25, 25), TextEdit("图", 1, 1).image("https://x.png"))
    }

    @Test
    fun `表情替换选中的部分，光标放到表情后面`() {
        assertEquals(TextEdit("好(bgm38)！", 8, 8), TextEdit("好耶！", 1, 2).insert(smileyTextOf("bgm38")))
    }
}
