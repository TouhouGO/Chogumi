package io.github.touhougo.chogumi.core.common.bbcode

/**
 * 参与引用反查的一层楼。
 *
 * @property id 楼层 id（主楼与楼中楼同一 id 空间）。
 * @property threadId 所在串的主楼 id；主楼自己的 [threadId] 就是 [id]。
 * @property author 发帖人昵称（与引用头里的 `[b]昵称[/b]` 比对）。
 * @property content BBCode 原文。
 */
data class QuotablePost(val id: Int, val threadId: Int, val author: String, val content: String)

/** 一条引用的头：被引者昵称与被引正文摘要（去掉了结尾的省略号）。 */
data class QuoteHeader(val author: String, val excerpt: String)

/** 楼层正文拆成「开头的引用」与「其后的正文」；没有开头引用时 [quote] 为 null、[body] 为原文。 */
data class QuotedContent(val quote: QuoteHeader?, val body: String)

private val LeadingQuote = Regex("""^\s*\[quote]\s*\[b](.*?)\[/b]\s*说:\s*(.*?)\[/quote]""", RegexOption.DOT_MATCHES_ALL)
private val QuoteBlock = Regex("""\[quote].*?\[/quote]""", RegexOption.DOT_MATCHES_ALL)
private val Markup = Regex("""\[/?[a-zA-Z]+(?:=[^\]]*)?]""")
private val Whitespace = Regex("""\s+""")
private const val ELLIPSIS = "..."

/**
 * 拆出楼层开头的引用（bgm 的回复引用格式恒为 `[quote][b]昵称[/b] 说: 摘要[/quote]`，
 * 2026-10-05 实测）。楼层组件据此把引用画成可点的引用条，其余正文照常渲染。
 * 引用不在开头、或格式不符时原样返回。
 */
fun splitLeadingQuote(content: String): QuotedContent {
    val match = LeadingQuote.find(content) ?: return QuotedContent(quote = null, body = content)
    val excerpt = match.groupValues[2].trim().removeSuffix(ELLIPSIS)
    return QuotedContent(
        quote = QuoteHeader(author = match.groupValues[1].trim(), excerpt = excerpt),
        body = content.substring(match.range.last + 1).trimStart(),
    )
}

/**
 * 引用一层楼时写进正文开头的引用块，格式与网页一致（[splitLeadingQuote] 认的就是它）：
 * `[quote][b]昵称[/b] 说: 摘要[/quote]` 后接一个换行。摘要见 [quoteExcerptOf]。
 */
fun quoteOf(author: String, content: String): String = "[quote][b]$author[/b] 说: ${quoteExcerptOf(content)}[/quote]\n"

/**
 * 在平铺的评论区回复某人时写在正文开头的 @，格式与网页一致：`[user=用户名]昵称[/user]` 后接一个空格，楼层里画成能点进主页的名字。
 * [userKey] 是用户名，没有用户名时是数字 id（网页两种都认）。
 */
fun mentionOf(userKey: String, name: String): String = "[user=$userKey]$name[/user] "

/**
 * 被引楼的摘要（引用块里的那段，也是编辑器里被引内容的预览）：去掉它自己的引用块与标签，空白收成一个空格，
 * 超过 [QUOTE_EXCERPT_LENGTH] 个字截断并接 `...`。反查（[resolveQuoteTargets]）按「正文以摘要开头」认，截断不影响。
 */
fun quoteExcerptOf(content: String): String {
    val plain = content.replace(QuoteBlock, "").replace(Markup, "").replace(Whitespace, " ").trim()
    return if (plain.length > QUOTE_EXCERPT_LENGTH) plain.take(QUOTE_EXCERPT_LENGTH) + ELLIPSIS else plain
}

/** 引用摘要的最大字数（设计决定：与网页引用的长短相近，够认出是哪一楼）。 */
private const val QUOTE_EXCERPT_LENGTH = 80

/** 比对用的正文：去掉自身的引用块与标签，去掉全部空白（服务端生成摘要时换行、空格都可能被吞）。 */
private fun comparableText(content: String): String =
    content.replace(QuoteBlock, "").replace(Markup, "").replace(Whitespace, "")

/**
 * 引用反查（唯一真源）：每条带引用的楼 → 被引楼的 id。
 *
 * 引用里**没有被引楼的 id**，只能靠「昵称 + 摘要」在**更早**的楼层里找，按三级回退：
 * 1. 同一串内：昵称相同且正文以摘要开头（离得最近的优先）；
 * 2. 同一串内：正文以摘要开头（老帖的昵称位常是一整段扁平化的旧引用，靠不住）；
 * 3. 全帖更早楼层：正文以摘要开头。
 *
 * 都找不到（被引楼已删除等）就不出现在结果里：引用条照常显示，但不可点，**不猜**。
 * 摘要为空的引用也不反查（空串是任何正文的前缀，会误指）。
 *
 * @param posts 按楼层顺序排好的全部楼（主楼后紧跟它的楼中楼）。
 */
fun resolveQuoteTargets(posts: List<QuotablePost>): Map<Int, Int> {
    val headers = posts.map { post ->
        splitLeadingQuote(post.content).quote?.let { header ->
            header to header.excerpt.replace(Markup, "").replace(Whitespace, "")
        }
    }
    val index = QuoteTargetIndex(headers.mapNotNull { it?.second?.takeIf(String::isNotEmpty) })
    val result = HashMap<Int, Int>()
    posts.forEachIndexed { postIndex, post ->
        headers[postIndex]?.let { (header, excerpt) ->
            if (excerpt.isNotEmpty()) {
                val target = index.targetOf(excerpt, post.threadId, header.author)
                if (target != null) result[post.id] = posts[target].id
            }
        }
        index.add(postIndex, post.threadId, post.author, comparableText(post.content))
    }
    return result
}

/** 只索引本页实际出现过的摘要；每次查询都只会看到此前加入的楼层。 */
private class QuoteTargetIndex(excerpts: List<String>) {
    private class Node {
        val children = HashMap<Char, Node>()
        var terminal: Match? = null
    }

    private class Match {
        var latest: Int? = null
        val byThread = HashMap<Int, Int>()
        val byThreadAuthor = HashMap<ThreadAuthor, Int>()
    }

    private data class ThreadAuthor(val threadId: Int, val author: String)

    private val root = Node()

    init {
        excerpts.forEach { excerpt ->
            var node = root
            excerpt.forEach { character -> node = node.children.getOrPut(character, ::Node) }
            if (node.terminal == null) node.terminal = Match()
        }
    }

    fun targetOf(excerpt: String, threadId: Int, author: String): Int? {
        var node = root
        excerpt.forEach { character -> node = node.children[character] ?: return null }
        val match = node.terminal ?: return null
        return match.byThreadAuthor[ThreadAuthor(threadId, author)] ?: match.byThread[threadId] ?: match.latest
    }

    fun add(index: Int, threadId: Int, author: String, content: String) {
        var node = root
        content.forEach { character ->
            node = node.children[character] ?: return
            node.terminal?.let { match ->
                match.latest = index
                match.byThread[threadId] = index
                match.byThreadAuthor[ThreadAuthor(threadId, author)] = index
            }
        }
    }
}
