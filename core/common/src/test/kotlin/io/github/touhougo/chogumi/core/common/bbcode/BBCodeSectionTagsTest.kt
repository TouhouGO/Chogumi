package io.github.touhougo.chogumi.core.common.bbcode

import io.github.touhougo.chogumi.core.model.BgmWeb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 段落类标签（`[left]` `[center]` `[right]` `[align]` `[indent]` `[float]`）、`[subject]` 与链接里的图片。
 *
 * 标签全集按 bgm.tv 使用的 BBCode 语法：b i s u mask color size url img sticker left right center indent
 * float code quote subject user align；另有 p1 上传图片的 photo。其中 sticker 是表情（`(bgm38)`），本解析器按表情语法认，不是方括号标签。
 */
class BBCodeSectionTagsTest {

    private fun single(source: String): BBCodeNode = BBCodeParser.parse(source).nodes.single()

    @Test
    fun `网页认的每个方括号标签都不落到 Unknown`() {
        val samples = listOf(
            "[b]x[/b]", "[i]x[/i]", "[s]x[/s]", "[u]x[/u]", "[mask]x[/mask]", "[color=red]x[/color]",
            "[size=12]x[/size]", "[url=https://a.com]x[/url]", "[img]https://a.com/1.png[/img]", "[left]x[/left]",
            "[right]x[/right]", "[center]x[/center]", "[indent]x[/indent]", "[float=left]x[/float]", "[code]x[/code]",
            "[quote]x[/quote]", "[subject=12]x[/subject]", "[user=fixture_user]x[/user]", "[align=center]x[/align]",
            "[photo=171308]d6/c4/x.jpg[/photo]",
        )
        samples.forEach { source ->
            assertFalse(single(source) is BBCodeNode.Unknown, "$source 不该按未知标签原样显示")
        }
        assertEquals(
            "https://lain.bgm.tv/pic/photo/l/d6/c4/x.jpg",
            assertIs<BBCodeNode.Image>(single("[photo=171308]d6/c4/x.jpg[/photo]")).url,
        )
    }

    @Test
    fun `left center right 是按对应一侧对齐的段落`() {
        assertEquals(BBCodeNode.Section(SectionLayout.Aligned(BBCodeAlign.Start), listOf(BBCodeNode.Text("x"))), single("[left]x[/left]"))
        assertEquals(BBCodeNode.Section(SectionLayout.Aligned(BBCodeAlign.Center), listOf(BBCodeNode.Text("x"))), single("[center]x[/center]"))
        assertEquals(BBCodeNode.Section(SectionLayout.Aligned(BBCodeAlign.End), listOf(BBCodeNode.Text("x"))), single("[right]x[/right]"))
    }

    @Test
    fun `align 只认 left center right 三个值`() {
        assertEquals(SectionLayout.Aligned(BBCodeAlign.End), (single("[align=Right]x[/align]") as BBCodeNode.Section).layout)
        assertIs<BBCodeNode.Unknown>(single("[align=justify]x[/align]"))
    }

    @Test
    fun `indent 是缩进的段落`() {
        assertEquals(SectionLayout.Indent, (single("[indent]x[/indent]") as BBCodeNode.Section).layout)
    }

    @Test
    fun `float 贴左右一侧，没写或写错按左`() {
        assertEquals(SectionLayout.Floating(BBCodeAlign.End), (single("[float=right]x[/float]") as BBCodeNode.Section).layout)
        assertEquals(SectionLayout.Floating(BBCodeAlign.Start), (single("[float=left]x[/float]") as BBCodeNode.Section).layout)
        assertEquals(SectionLayout.Floating(BBCodeAlign.Start), (single("[float=none]x[/float]") as BBCodeNode.Section).layout)
    }

    @Test
    fun `subject 是条目链接，id 不对时按字面显示`() {
        assertEquals(BBCodeNode.Url(BgmWeb.subject(12596), listOf(BBCodeNode.Text("Minecraft"))), single("[subject=12596]Minecraft[/subject]"))
        assertEquals(BgmWeb.subject(12), (single("[subject]12[/subject]") as BBCodeNode.Url).url)
        assertIs<BBCodeNode.Unknown>(single("[subject=abc]x[/subject]"))
    }

    @Test
    fun `链接里直接包着的图片带上链接地址`() {
        val url = single("[url=https://apps.apple.com/app/id1][img]https://a.com/badge.svg[/img][/url]") as BBCodeNode.Url
        val image = url.children.single() as BBCodeNode.Image
        assertEquals("https://apps.apple.com/app/id1", image.link)
        assertNull((single("[img]https://a.com/1.png[/img]") as BBCodeNode.Image).link)
    }

    @Test
    fun `段落标签在摘要里只留文字，相邻两段之间隔一个空格`() {
        assertEquals("居中 缩进 浮动", excerptOf("[center]居中[/center][indent]缩进[/indent][float=right]浮动[/float]"))
    }

    /** 居中、字号、颜色、删除线与链接图片的组合。 */
    private val sectionedPost = """
        [center][color=blue][size=25][s]示例标记[/s][/size][/color][/center]
        [center][color=#75BA40][size=30]示例副标题[/size][/color][/center]
        [center][size=32][color=#00A1D8][url=https://example.invalid/project]示例链接[/url][/color] 说明[/size][/center]
        [center][url=https://example.invalid/app][img]https://example.invalid/badge.svg[/img][/url]
        [/center]
    """.trimIndent()

    /** 嵌套缩进、浮动盒、删除线与链接的组合。 */
    private val floatingBio = """
        [indent][color=#afa4b4][s]　　　　[/s][/color][float=right][size=24]　[/size][/float][/indent][indent][float=left][float=right][b]示例：[/b]
        [url=https://bgm.tv/anime/list/fixture_float_user/collect?tag=example]示例条目收藏[/url]
         [/float][b] ＊ [/b][/float][/indent]
        [float=left][float=right][b][url=https://example.invalid/external]示例外链[/url]（[url=https://bgm.tv/subject/12596]Minecraft[/url] 模组）[/b]
         [/float][b] ＊ [/b][/float]
    """.trimIndent()

    @Test
    fun `复杂正文与简介里不剩任何方括号标签`() {
        listOf(sectionedPost, floatingBio).forEach { source ->
            val nodes = BBCodeParser.parse(source).nodes
            assertFalse(nodes.any { it.hasUnknown() }, "不该有按字面显示的标签：$source")
        }
    }

    private fun BBCodeNode.hasUnknown(): Boolean = when (this) {
        is BBCodeNode.Unknown -> true
        is BBCodeNode.Section -> children.any { it.hasUnknown() }
        is BBCodeNode.Bold -> children.any { it.hasUnknown() }
        is BBCodeNode.Strike -> children.any { it.hasUnknown() }
        is BBCodeNode.Size -> children.any { it.hasUnknown() }
        is BBCodeNode.Color -> children.any { it.hasUnknown() }
        is BBCodeNode.Url -> children.any { it.hasUnknown() }
        else -> false
    }
}
