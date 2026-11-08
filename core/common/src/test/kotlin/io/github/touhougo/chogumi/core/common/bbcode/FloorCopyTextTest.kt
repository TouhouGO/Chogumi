package io.github.touhougo.chogumi.core.common.bbcode

import org.junit.Assert.assertEquals
import org.junit.Test

/** 楼层长按「复制」拿到的文字：不带开头的引用，标签去掉，剧透照出原文，表情留代码，图片留地址。 */
class FloorCopyTextTest {

    @Test
    fun `开头的引用不复制`() {
        assertEquals("这是引用后的回复正文", floorCopyTextOf("[quote][b]示例引用者甲[/b] 说: 这是虚构的引用摘要[/quote]\n这是引用后的回复正文"))
    }

    @Test
    fun `标签去掉，换行保留`() {
        assertEquals("粗体与链接\n第二行", floorCopyTextOf("[b]粗体[/b]与[url=https://bgm.tv]链接[/url]\n第二行"))
    }

    @Test
    fun `剧透照出原文，表情留代码，图片留地址`() {
        assertEquals("结局是他(bgm38)", floorCopyTextOf("结局是[mask]他[/mask](bgm38)"))
        assertEquals("https://lain.bgm.tv/a.jpg", floorCopyTextOf("[img]https://lain.bgm.tv/a.jpg[/img]"))
    }
}
