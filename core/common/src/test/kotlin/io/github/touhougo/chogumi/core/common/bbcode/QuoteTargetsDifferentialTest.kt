package io.github.touhougo.chogumi.core.common.bbcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuoteTargetsDifferentialTest {
    @Test
    fun `匹配三级回退与逐楼往前扫的参照实现一致`() {
        val random = Random(8123)
        repeat(10) {
            val posts = List(240) { index ->
                val author = listOf("甲", "乙", "重名", "")[random.nextInt(4)]
                val body = listOf("[b]相同[/b] 正文", "相同\n正文续", "", "另一段", "[i]甲[/i]")[random.nextInt(5)]
                val excerpt = listOf("相同 正文", "相同", "另一段", "[b]甲[/b]", " ", "不存在")[random.nextInt(6)]
                val quote = if (random.nextBoolean()) "[quote][b]$author[/b] 说: $excerpt...[/quote]\n" else ""
                QuotablePost(index % 67, random.nextInt(4), author, quote + body)
            }
            assertEquals(referenceTargets(posts), resolveQuoteTargets(posts))
        }
    }

    @Test
    fun `重复id按输入顺序覆盖且只匹配严格更早的楼层`() {
        val posts = listOf(
            QuotablePost(1, 1, "甲", "目标一"),
            QuotablePost(7, 1, "乙", "[quote][b]甲[/b] 说: 目标一[/quote]第一次"),
            QuotablePost(2, 1, "丙", "目标二"),
            QuotablePost(7, 1, "丁", "[quote][b]丙[/b] 说: 目标二[/quote]第二次"),
            QuotablePost(9, 1, "丁", "[quote][b]丁[/b] 说: 后面[/quote]不能往后找"),
            QuotablePost(10, 1, "丁", "后面"),
        )
        assertEquals(mapOf(7 to 2), resolveQuoteTargets(posts))
    }

    @Test
    fun `大列表的引用反查不反复读取更早楼层`() {
        for (size in listOf(1_000, 5_000)) {
            for (found in listOf(false, true)) {
                val posts = CountingPosts(List(size) { index ->
                    val content = when {
                        index == 0 -> "唯一目标"
                        index % 4 == 0 -> "[quote][b]甲[/b] 说: ${if (found) "唯一目标" else "不存在"}[/quote]回复$index"
                        else -> "普通正文$index"
                    }
                    QuotablePost(index, 1, "甲", content)
                })
                val result = resolveQuoteTargets(posts)
                assertEquals(if (found) (size - 1) / 4 else 0, result.size)
                assertTrue("size=$size found=$found reads=${posts.reads}", posts.reads <= size * 10)
            }
        }
    }

    private class CountingPosts(private val posts: List<QuotablePost>) : AbstractList<QuotablePost>() {
        var reads = 0
        override val size: Int get() = posts.size
        override fun get(index: Int): QuotablePost {
            reads++
            return posts[index]
        }
    }

    // 仅用于比较完整输出；生产决策仍由 resolveQuoteTargets 独占。
    private fun referenceTargets(posts: List<QuotablePost>): Map<Int, Int> {
        val quoteBlock = Regex("""\[quote].*?\[/quote]""", RegexOption.DOT_MATCHES_ALL)
        val markup = Regex("""\[/?[a-zA-Z]+(?:=[^\]]*)?]""")
        val whitespace = Regex("""\s+""")
        val comparable = posts.map { it.content.replace(quoteBlock, "").replace(markup, "").replace(whitespace, "") }
        val result = HashMap<Int, Int>()
        posts.forEachIndexed { index, post ->
            val header = splitLeadingQuote(post.content).quote ?: return@forEachIndexed
            val excerpt = header.excerpt.replace(markup, "").replace(whitespace, "")
            if (excerpt.isEmpty()) return@forEachIndexed
            val earlier = index - 1 downTo 0
            fun matches(i: Int) = comparable[i].startsWith(excerpt)
            val sameThread = earlier.filter { posts[it].threadId == post.threadId }
            val target = sameThread.firstOrNull { matches(it) && posts[it].author == header.author }
                ?: sameThread.firstOrNull { matches(it) }
                ?: earlier.firstOrNull { matches(it) }
            if (target != null) result[post.id] = posts[target].id
        }
        return result
    }
}
