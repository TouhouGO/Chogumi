package io.github.touhougo.chogumi.core.common.bbcode

import org.junit.Assert.assertEquals
import org.junit.Test

/** 楼层开头的引用拆成昵称、摘要与后随正文，并反查被引楼层。 */
class QuoteTargetsTest {

    @Test
    fun `拆出开头的引用：昵称、去掉省略号的摘要、其后的正文`() {
        assertEquals(
            QuotedContent(QuoteHeader("示例引用者甲", "这是虚构的引用摘要"), "这是引用后的回复正文"),
            splitLeadingQuote("[quote][b]示例引用者甲[/b] 说: 这是虚构的引用摘要[/quote]\n这是引用后的回复正文"),
        )
        assertEquals(
            QuotedContent(QuoteHeader("示例引用者乙", "另一段虚构摘要"), "回复"),
            splitLeadingQuote("[quote][b]示例引用者乙[/b] 说: 另一段虚构摘要...[/quote]回复"),
        )
        assertEquals(QuotedContent(null, "没有引用的楼"), splitLeadingQuote("没有引用的楼"))
        val inMiddle = "正文在前 [quote][b]a[/b] 说: b[/quote]"
        assertEquals(QuotedContent(null, inMiddle), splitLeadingQuote(inMiddle))
    }

    @Test
    fun `同串内昵称与摘要都对上的优先，离得近的优先`() {
        val posts = listOf(
            QuotablePost(1, 1, "主楼", "主楼正文"),
            QuotablePost(2, 1, "示例引用者甲", "这是虚构的引用摘要"),
            QuotablePost(3, 1, "别人", "这是虚构的引用摘要"),
            QuotablePost(4, 1, "示例回复者丙", "[quote][b]示例引用者甲[/b] 说: 这是虚构的引用摘要[/quote]\n回嘴"),
        )
        assertEquals(mapOf(4 to 2), resolveQuoteTargets(posts))
    }

    @Test
    fun `昵称对不上时退回同串内摘要匹配，再退回全帖更早楼层`() {
        val posts = listOf(
            QuotablePost(10, 10, "甲", "[b]粗体[/b]第一楼的\r\n正文很长很长"),
            QuotablePost(20, 20, "乙", "第二楼"),
            QuotablePost(21, 20, "丙", "第二串里的回复"),
            // 昵称位是扁平化的旧引用，对不上任何人；摘要能在同串找到
            QuotablePost(22, 20, "丁", "[quote][b]回复 乙：第二串里的回复[/b] 说: 第二串里的回复[/quote]哈"),
            // 引的是另一串的主楼：只能在全帖更早楼层里找到；摘要里的换行与标签都被服务端吞掉
            QuotablePost(23, 20, "戊", "[quote][b]改名了[/b] 说: 粗体第一楼的正文...[/quote]哦"),
        )
        assertEquals(mapOf(22 to 21, 23 to 10), resolveQuoteTargets(posts))
    }

    @Test
    fun `只往前找；找不到或摘要为空的不猜`() {
        val posts = listOf(
            QuotablePost(1, 1, "甲", "[quote][b]乙[/b] 说: 后面才出现的话[/quote]抢答"),
            QuotablePost(2, 1, "乙", "后面才出现的话"),
            QuotablePost(3, 1, "丙", "[quote][b]乙[/b] 说: [/quote]空引用"),
            QuotablePost(4, 1, "丁", "[quote][b]某人[/b] 说: 已删除的楼[/quote]回复"),
        )
        assertEquals(emptyMap<Int, Int>(), resolveQuoteTargets(posts))
    }

    @Test
    fun `被引楼自身带引用时，只拿它自己的正文比对`() {
        val posts = listOf(
            QuotablePost(1, 1, "甲", "原话"),
            QuotablePost(2, 1, "乙", "[quote][b]甲[/b] 说: 原话[/quote]\n乙的回应"),
            QuotablePost(3, 1, "丙", "[quote][b]乙[/b] 说: 乙的回应[/quote]丙的回应"),
        )
        assertEquals(mapOf(2 to 1, 3 to 2), resolveQuoteTargets(posts))
    }

    @Test
    fun `写出的引用块拆得回来，也反查得到被引楼`() {
        val quoted = "[quote][b]甲[/b] 说: 更早的引用[/quote]\n[b]原话[/b]\n  第二行"
        val reply = quoteOf("乙", quoted) + "回应"
        assertEquals(QuotedContent(QuoteHeader("乙", "原话 第二行"), "回应"), splitLeadingQuote(reply))
        val posts = listOf(QuotablePost(1, 1, "乙", quoted), QuotablePost(2, 1, "丙", reply))
        assertEquals(mapOf(2 to 1), resolveQuoteTargets(posts))
    }

    @Test
    fun `长楼的摘要截断并接省略号，反查照样认得`() {
        val long = "一".repeat(200)
        val excerpt = quoteExcerptOf(long)
        assertEquals("一".repeat(80) + "...", excerpt)
        val posts = listOf(QuotablePost(1, 1, "甲", long), QuotablePost(2, 1, "乙", quoteOf("甲", long) + "回应"))
        assertEquals(mapOf(2 to 1), resolveQuoteTargets(posts))
    }
}
