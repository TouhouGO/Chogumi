package io.github.touhougo.chogumi.core.common.bbcode

/**
 * BBCode 语法树。
 *
 * 节点集合与语义对照 bgm.tv 的方言，**不是**通用 BBCode 实现：
 * 只保留 Bangumi 实际会产出的标签，其余一律落到 [Unknown] 以便可读地回退。
 */
sealed interface BBCodeNode {

    data class Text(val value: String) : BBCodeNode

    data class Bold(val children: List<BBCodeNode>) : BBCodeNode
    data class Italic(val children: List<BBCodeNode>) : BBCodeNode
    data class Underline(val children: List<BBCodeNode>) : BBCodeNode
    data class Strike(val children: List<BBCodeNode>) : BBCodeNode

    /** `[size=13]` —— 相对基准字号的磅值；解析失败时为 null。 */
    data class Size(val value: Double?, val children: List<BBCodeNode>) : BBCodeNode

    /** `[color=red|#rrggbb]` —— 原样保留字符串，由渲染层决定能否解析成颜色。 */
    data class Color(val value: String?, val children: List<BBCodeNode>) : BBCodeNode

    /** `[mask]` —— 剧透，覆盖到点击为止（对齐 web 客户端）。 */
    data class Mask(val children: List<BBCodeNode>) : BBCodeNode

    data class Quote(val children: List<BBCodeNode>) : BBCodeNode

    /** `[url=...]` 或 `[url]...[/url]`；两者都取不到时 [url] 为 null。 */
    data class Url(val url: String?, val children: List<BBCodeNode>) : BBCodeNode

    /** `[user=username]` 或 `[user]username[/user]`。 */
    data class User(val username: String, val children: List<BBCodeNode>) : BBCodeNode

    /** `(bgm38)` 这类表情；[code] 保留原始写法（含括号），由渲染层解析。 */
    data class Smiley(val code: String) : BBCodeNode

    /**
     * 图片；[width]/[height] 来自 `[img=W,H]`，未声明时为 null。
     * [link] 是包着它的 `[url]` 的地址（`[url=…][img]…[/img][/url]`，网页上点图跳链接），没有时为 null。
     */
    data class Image(val url: String?, val width: Double?, val height: Double?, val link: String?) : BBCodeNode

    /**
     * 独占一段的块级容器：`[left]` `[center]` `[right]` `[align=…]`、`[indent]`、`[float=…]`。
     * 里面是一整段正文；怎么排由 [layout] 决定。
     */
    data class Section(val layout: SectionLayout, val children: List<BBCodeNode>) : BBCodeNode

    /**
     * `[code]` —— 逐字文本，内容**原样捕获**（里面的 wiki 标记绝不能当 BBCode 解析）。
     */
    data class CodeBlock(val text: String) : BBCodeNode

    /**
     * `[list]` —— 旧站的 `<ul>`，每个 `[*]` 开一项（`[*]` 没有闭合标签）；网页不画项目符号，一项一行。
     * 第一个 `[*]` 之前有字时那段字自成一项。项首尾的空白与换行已去掉。
     * 渲染器可以把整块交给块级插件（core:reader `BBCodeBlockPlugin`），认不出时照常一项一行。
     */
    data class ListBlock(val items: List<List<BBCodeNode>>) : BBCodeNode

    /**
     * 未识别的标签，保留原始拼写以便可读回退。
     *
     * [hasClosingTag] 为 false 表示这是"看起来像标签但从未闭合"的括号文本
     * （Bangumi 上的纯中文方括号、wiki 片段），渲染时应原样显示。
     */
    data class Unknown(
        val name: String,
        val value: String?,
        val children: List<BBCodeNode>,
        val hasClosingTag: Boolean,
    ) : BBCodeNode {
        /** 开标签的原文拼写；纯文本、渲染与块元素上浮都按这一份还原。 */
        val openingLiteral: String get() = if (value != null) "[$name=$value]" else "[$name]"

        /** 闭标签的原文拼写；从未闭合时为空。 */
        val closingLiteral: String get() = if (hasClosingTag) "[/$name]" else ""
    }
}

/** 水平方向的一侧或居中；Start / End 跟随书写方向。 */
enum class BBCodeAlign { Start, Center, End }

/** [BBCodeNode.Section] 的排法。 */
sealed interface SectionLayout {

    /** `[left]` `[center]` `[right]` 与 `[align=left|center|right]`：段内按 [align] 对齐（网页是 `text-align`）。 */
    data class Aligned(val align: BBCodeAlign) : SectionLayout

    /** `[indent]`：整段缩进（网页是 `<blockquote>`）。 */
    data object Indent : SectionLayout

    /** `[float=left|right]`：网页上贴 [side] 一侧浮动、宽度随内容的盒子。 */
    data class Floating(val side: BBCodeAlign) : SectionLayout
}

/**
 * 解析结果。
 *
 * [plainText] 拼出整段文字，只给本模块的解析测试核对内容；列表里的一行摘要用 [excerptOf]。
 */
data class BBCodeAst(val nodes: List<BBCodeNode>) {

    internal val plainText: String get() = nodes.joinToString("") { it.plainText() }
}

/** 剧透的占位符；用块字符而非文字，避免被读成"这里真的有内容"。 */
const val SPOILER_PLACEHOLDER = "██"

fun BBCodeNode.plainText(): String = when (this) {
    is BBCodeNode.Text -> value
    is BBCodeNode.Bold -> children.text()
    is BBCodeNode.Italic -> children.text()
    is BBCodeNode.Underline -> children.text()
    is BBCodeNode.Strike -> children.text()
    is BBCodeNode.Size -> children.text()
    is BBCodeNode.Color -> children.text()
    is BBCodeNode.Mask -> children.text()
    is BBCodeNode.Quote -> children.text()
    is BBCodeNode.Url -> children.text().ifEmpty { url.orEmpty() }
    is BBCodeNode.User -> children.text()
    is BBCodeNode.Smiley -> "($code)"
    is BBCodeNode.Image -> url ?: "[img]"
    is BBCodeNode.CodeBlock -> text
    is BBCodeNode.Section -> children.text()
    is BBCodeNode.ListBlock -> items.joinToString("\n") { it.text() }
    is BBCodeNode.Unknown -> openingLiteral + children.text() + closingLiteral
}

/**
 * 一层楼的正文 → 「复制」拿到的纯文字（全仓唯一）：开头的引用块不要（那是被回复的人的话），其余标签去掉、只留文字，
 * 换行原样保留；剧透照出原文（复制是自己要看的）；图片留地址、表情留代码（贴回 bgm.tv 仍是这个表情）。
 */
fun floorCopyTextOf(content: String): String =
    BBCodeParser.parse(splitLeadingQuote(content).body).nodes.joinToString("") { it.plainText() }.trim()

/**
 * 服务端截好的 BBCode 摘要（日志摘要）→ 列表里的一行纯文字：标签去掉（方言之外但成对的也去掉）；剧透换成占位块，绝不泄漏；
 * 图片与表情不出字（摘要里一串图片地址没有意义）；截断落在标签中间留下的半个标签（`[url=https://…` 没有 `]`）丢掉；
 * 换行与连续空白收成一个空格。
 */
fun excerptOf(source: String): String {
    val open = source.lastIndexOf('[')
    val whole = if (open >= 0 && source.indexOf(']', open) < 0 && truncatedTag.matches(source.substring(open))) {
        source.substring(0, open)
    } else source
    return excerptOf(BBCodeParser.parse(whole).nodes)
}

/** 已解析的一段 → 一行纯文字，规则同上（剧透换成占位块，图片与表情不出字，空白收成一个空格）。 */
fun excerptOf(nodes: List<BBCodeNode>): String = nodes.excerpt().replace(whitespace, " ").trim()

private val truncatedTag = Regex("""\[/?[A-Za-z]*(=.*)?""", RegexOption.DOT_MATCHES_ALL)
private val whitespace = Regex("""\s+""")

private fun BBCodeNode.excerptText(): String = when (this) {
    is BBCodeNode.Mask -> SPOILER_PLACEHOLDER
    is BBCodeNode.Image, is BBCodeNode.Smiley -> ""
    is BBCodeNode.Bold -> children.excerpt()
    is BBCodeNode.Italic -> children.excerpt()
    is BBCodeNode.Underline -> children.excerpt()
    is BBCodeNode.Strike -> children.excerpt()
    is BBCodeNode.Size -> children.excerpt()
    is BBCodeNode.Color -> children.excerpt()
    is BBCodeNode.Quote -> children.excerpt()
    is BBCodeNode.Url -> children.excerpt().ifEmpty { url.orEmpty() }
    is BBCodeNode.User -> children.excerpt()
    // 段落容器在一行摘要里只留文字，前后补空格免得两段粘在一起（多余的空白最后收成一个）
    is BBCodeNode.Section -> " " + children.excerpt() + " "
    // 列表各项之间同样补空格
    is BBCodeNode.ListBlock -> items.joinToString(" ", prefix = " ", postfix = " ") { it.excerpt() }
    // 方言之外但成对的标签（[center]…[/center]）也只留文字；不成对的是字面方括号，原样保留
    is BBCodeNode.Unknown -> if (hasClosingTag) children.excerpt() else plainText()
    else -> plainText()
}

private fun List<BBCodeNode>.text() = joinToString("") { it.plainText() }
private fun List<BBCodeNode>.excerpt() = joinToString("") { it.excerptText() }
