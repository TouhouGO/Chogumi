package io.github.touhougo.chogumi.core.common.bbcode

import io.github.touhougo.chogumi.core.foundation.BGM_SITE
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 旧站渲染好的正文 HTML → BBCode，交给 [BBCodeParser] 与 core:reader 的 `BBCodeText`：短信正文只有网页渲染好的 HTML，
 * 还原成 BBCode 后和帖子走同一个渲染器，全 App 不另写一套 HTML 显示。
 *
 * [html] 是正文元素（短信的 `.pm-message-body`）里面的原始 HTML，服务端给的原样，不经格式化输出
 * （jsoup 的 `Element.html()` 默认 prettyPrint 会在块元素之间加换行和缩进，那就成了正文里的字）。
 *
 * 认的写法是旧站 BBCode 渲染器的固定输出（对照同一封信的发信原文与网页 HTML）：
 *
 * | 旧站输出 | 还原 |
 * |---|---|
 * | `<span style="font-weight:bold">` / `font-style:italic` / `text-decoration:underline` / `line-through` | `[b]` `[i]` `[u]` `[s]` |
 * | `<span class="text_mask"><span class="inner">…</span></span>` | `[mask]` |
 * | `<span style="font-size:25px">` / `<span style="color:red">` | `[size=25]` / `[color=red]` |
 * | `<a href="X">文字</a>` | 文字就是 X 时 `[url]X[/url]`，否则 `[url=X]文字[/url]`；短信里的 `[img]` 旧站也输出成链接 |
 * | `<div class="quote"><q>…</q></div>` | `[quote]` |
 * | `<div class="codeHighlight"><pre>…</pre></div>` | `[code]`，逐字 |
 * | `<p style="text-align:center">` / `right` / `left` | `[center]` / `[right]` / `[left]` |
 * | `<img class="smile" alt="(bgm38)">` | `(bgm38)` |
 * | `<br>` | 换行；紧跟在后面的那个换行符是服务端排版（发信原文的换行变成 `<br>` 加原换行），不算 |
 *
 * 不认识的元素只留里面的文字。实体（`&lt;` `&amp;` …）按 HTML 解码，`&nbsp;` 是空格（旧站把代码里的空格写成它）。
 * 文字部分经 [BBCodeParser.literal]，字面的方括号不会被认成标签。服务端对没闭合的标签照样输出开标签
 * （发信原文里字面的 `[b]` 网页上一路粗体到结尾），HTML 解析按浏览器的规则补上闭合，还原出来的标签总是成对的，
 * 显示与网页一致。
 */
fun bbcodeOfHtml(html: String): String =
    HtmlToBBCode().apply { children(Jsoup.parseBodyFragment(html, BGM_SITE).body()) }.result()

private class HtmlToBBCode {
    private val out = StringBuilder()

    /** 还没写出的一段字面文字：攒到下一个标记前一起经 [BBCodeParser.literal]，跨文字节点的写法也认得出。 */
    private val pending = StringBuilder()

    /** 上一个节点是 `<br>`：下一段文字开头的换行符是服务端排版，丢掉。 */
    private var afterBreak = false

    fun result(): String {
        flush()
        return out.toString()
    }

    fun children(element: Element) {
        element.childNodes().forEach(::node)
    }

    private fun node(node: Node) {
        val dropNewline = afterBreak
        afterBreak = false
        when (node) {
            is TextNode -> pending.append(textOf(node, dropNewline))
            is Element -> element(node)
        }
    }

    private fun element(element: Element) {
        when (element.normalName()) {
            "br" -> {
                markup("\n")
                afterBreak = true
            }
            "img" -> if (element.hasClass(SMILEY_CLASS)) markup(element.attr("alt"))
            "a" -> link(element)
            "span" -> span(element)
            "div" -> when {
                element.hasClass(QUOTE_CLASS) -> wrap(listOf(Wrapper("quote")), element)
                element.hasClass(CODE_CLASS) -> markup("[code]${codeTextOf(element)}[/code]")
                else -> children(element)
            }
            "p" -> {
                val align = styleOf(element)["text-align"]?.takeIf { it in ALIGNS }
                if (align == null) children(element) else wrap(listOf(Wrapper(align)), element)
            }
            else -> children(element)
        }
    }

    private fun span(element: Element) {
        // 遮罩外层的 style 是它自己的配色（color:#555），不是 [color]
        if (element.hasClass(MASK_CLASS)) return wrap(listOf(Wrapper("mask")), element)
        val style = styleOf(element)
        val decorations = style["text-decoration"].orEmpty().split(' ')
        val wrappers = buildList {
            if (style["font-weight"] == "bold") add(Wrapper("b"))
            if (style["font-style"] == "italic") add(Wrapper("i"))
            if ("underline" in decorations) add(Wrapper("u"))
            if ("line-through" in decorations) add(Wrapper("s"))
            style["font-size"]?.removeSuffix("px")?.toIntOrNull()?.let { add(Wrapper("size", it.toString())) }
            style["color"]?.let { add(Wrapper("color", it)) }
        }
        wrap(wrappers, element)
    }

    private fun link(element: Element) {
        val href = element.absUrl("href")
        if (href.isEmpty()) return children(element)
        wrap(listOf(Wrapper("url", href.takeUnless { element.wholeText() == it })), element)
    }

    private fun wrap(wrappers: List<Wrapper>, element: Element) {
        wrappers.forEach { markup(if (it.value == null) "[${it.tag}]" else "[${it.tag}=${it.value}]") }
        children(element)
        wrappers.asReversed().forEach { markup("[/${it.tag}]") }
    }

    private fun markup(text: String) {
        flush()
        out.append(text)
    }

    private fun flush() {
        if (pending.isEmpty()) return
        out.append(BBCodeParser.literal(pending.toString()))
        pending.clear()
    }

    /** 代码块里的字逐字取出（`[code]` 里不是标记，不经 [BBCodeParser.literal]），`<br>` 同样是换行。 */
    private fun codeTextOf(element: Element): String {
        val text = StringBuilder()
        var afterCodeBreak = false
        fun collect(node: Node) {
            val dropNewline = afterCodeBreak
            afterCodeBreak = false
            when {
                node is TextNode -> text.append(textOf(node, dropNewline))
                node is Element && node.normalName() == "br" -> {
                    text.append('\n')
                    afterCodeBreak = true
                }
                else -> node.childNodes().forEach(::collect)
            }
        }
        element.childNodes().forEach(::collect)
        return text.toString()
    }

    private class Wrapper(val tag: String, val value: String? = null)

    private companion object {
        const val SMILEY_CLASS = "smile"
        const val MASK_CLASS = "text_mask"
        const val QUOTE_CLASS = "quote"
        const val CODE_CLASS = "codeHighlight"

        /** 段落的 `text-align` 取值，与 `[left]` `[center]` `[right]` 同名。 */
        val ALIGNS = setOf("left", "center", "right")

        /** 文字节点解码后的字：`&nbsp;` 换成空格；[dropNewline] 时去掉开头那个排版用的换行符。 */
        fun textOf(node: TextNode, dropNewline: Boolean): String {
            val text = node.wholeText.replace('\u00A0', ' ')
            return if (dropNewline) text.removePrefix("\n") else text
        }

        /** `style` 属性 → 属性名（小写）到取值；`font-size:25px; line-height:25px;` → {font-size=25px, line-height=25px}。 */
        fun styleOf(element: Element): Map<String, String> =
            element.attr("style").split(';').mapNotNull { declaration ->
                val parts = declaration.split(':', limit = 2)
                if (parts.size < 2) null else parts[0].trim().lowercase() to parts[1].trim()
            }.toMap()
    }
}
