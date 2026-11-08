package io.github.touhougo.chogumi.core.common.bbcode

import io.github.touhougo.chogumi.core.model.BgmWeb

/**
 * Bangumi 主题/日志/简介所用的 BBCode 方言解析器。
 *
 * ## 基准：bgm.tv **旧站**，刻意不是 next.bgm.tv
 *
 * 两个官方渲染器在**畸形嵌套**上不一致（作者手误，如维基管理员月报话题 469999：
 * 一个本该写成 `[/color]` 的 `[/size]`，以及 `[s][mask]…[/s][/mask]` 这种交叠）：
 *
 * - **bgm.tv 旧站**——**治愈**：孤立的已知名闭合标签会结束最内层打开的框架；
 *   与外层框架匹配的闭合标签会隐式结束其上所有框架。畸形片段照样渲染，
 *   帖子其余部分保持干净。
 * - **next.bgm.tv**——**硬失败**：解析中止并显示**原文**（BBCode 方括号泄漏到正文里）。
 *
 * 本实现对齐旧站：**一个手误不该毁掉一整段**。不要在没有明确决定的情况下
 * 把它改成 next 的 fail-raw 行为——这套语义由 [BBCodeNestingSemanticsTest] 钉住。
 *
 * ## 为什么不能用正则或简单栈
 *
 * 三条真实存在的输入让简单做法失效（都来自实际反馈）：
 * 1. `[code]` 里是**逐字**内容（wiki 模板、方括号、竖线），当 BBCode 解析会把整帖毁掉；
 * 2. 未闭合的**未知**标签是普通方括号文本（纯中文的 `[动画]`），不能当标签；
 * 3. 但"能闭合的未知标签"又要正常递归——判据是**向后查找是否存在闭合标签**，
 *    而不是递归后回退（回退会按括号数指数级重解析，实测 20 个片段冻结主线程 6.85 秒）。
 */
object BBCodeParser {

    fun parse(source: String): BBCodeAst =
        BBCodeAst(Parser(normalizeLineEndings(source)).parseSequence(null).nodes)

    /** 一个闭合的 `[list]` 块：[range] 是它在交给 [interactiveLists] 的原文里的位置（含开闭标签）。 */
    data class ListSource(val range: IntRange, val node: BBCodeNode.ListBlock)

    /**
     * [source] 里能当块级组件用的列表（投票）：闭合、不在引用里、不套在别的列表里；代码块里的字不是标记，本来就没有列表。
     * 判断按解析器自己的上下文做，不另外按字面找标签。位置对应原文，不做 [normalizeLineEndings]。
     */
    fun interactiveLists(source: String): List<ListSource> {
        val parser = Parser(source)
        parser.parseSequence(null)
        return parser.lists
    }

    /**
     * 一段字面文字 → 解析回来还是这段字的写法。方言没有转义符：凡是本解析器会认成标记的起点（成立的标签、列表项 `[*]`、表情、
     * `&nbsp;`），在它的第一个字符后面插一个 [WORD_JOINER]（不占宽、不显示），这里就不再成立。
     * 其余字符原样保留，所以纯文字（含 `[动画]` 这类本来就按字面显示的括号）不变。
     */
    fun literal(text: String): String {
        val probe = Parser(text)
        val out = StringBuilder(text.length)
        text.forEachIndexed { index, char ->
            out.append(char)
            if (probe.startsMarkupAt(index)) out.append(WORD_JOINER)
        }
        return out.toString()
    }

    /** [literal] 的逆：去掉它插在 `[`、`(`、`&` 后面的 [WORD_JOINER]，还原成用户输入的字（投票的问题与选项按字面存）。 */
    fun literalText(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            append(char)
            if (char in "[(&" && text.getOrNull(index) == WORD_JOINER) index++
        }
    }

    /** [literal] 插进标记起点的字符（U+2060 WORD JOINER）：不是字母、不是空白，不显示。 */
    private const val WORD_JOINER = '\u2060'

    /** 解析前统一换成不换行空格（U+00A0）的实体写法（见 [normalizeLineEndings]）。 */
    private const val NBSP = "&nbsp;"

    /**
     * `\r\n` 与裸 `\r` 一律归一成 `\n`。
     *
     * 服务端给的是 CRLF（线上实测），而 `\r` 在 Compose 文本里会渲染成一个方块字符。
     * 旧站把它转成 `<br />`，这里对应地转成 `\n`——**在解析期做一次**，
     * 免得渲染层、摘要、无障碍标签各自处理一遍还漏掉某处。
     */
    private fun normalizeLineEndings(source: String): String {
        val crlfNormalized = if (source.indexOf('\r') < 0) source else source.replace("\r\n", "\n").replace('\r', '\n')
        return if (crlfNormalized.contains(NBSP)) crlfNormalized.replace(NBSP, "\u00A0") else crlfNormalized
    }

    /**
     * 方言真正定义的标签（对照 bgm.tv 两版网页渲染器的标签全集，外加 p1 上传图片的 `[photo]`）。
     * `[list]` 只有旧站认（渲染成 `<ul>`，next 站原样露出方括号），与上面的基准一致按旧站认。
     * 其余"看起来像标签"的括号文本在未闭合时按字面渲染。
     */
    private val KNOWN_TAGS = setOf(
        "b", "i", "u", "s", "size", "color", "mask", "quote",
        "url", "user", "img", "photo", "code",
        "left", "center", "right", "align", "indent", "float", "subject", "list",
    )

    /** `[list]` 里开一项的标记（没有闭合标签，也不是字母名，按标签解析认不出来）。 */
    private const val LIST_ITEM = "[*]"

    /** `[align=…]` 的取值，与 `[left]` `[center]` `[right]` 三个标签同名。 */
    private val ALIGNS = mapOf("left" to BBCodeAlign.Start, "center" to BBCodeAlign.Center, "right" to BBCodeAlign.End)

    /** 标签嵌套的深度上限（设计决定，不在 M3 里）：正常帖子的嵌套不过个位数。 */
    private const val MAX_NESTING = 64

    private class Tag(val name: String, val value: String?, val raw: String)

    /** [itemStarts]：`[list]` 这一层里每个 [LIST_ITEM] 落在 [nodes] 的哪个下标（别的层恒为空）。 */
    private class SequenceResult(val nodes: List<BBCodeNode>, val closed: Boolean, val itemStarts: List<Int> = emptyList())

    private class Parser(private val source: String) {
        private var cursor = 0
        val lists = mutableListOf<ListSource>()

        /** 当前打开的所有框架的停止标签，从最外层到最内层（含正在解析的这一层）；进出递归时压栈、弹栈，不逐层复制。 */
        private val open = ArrayList<String>()

        /**
         * [open] 承载所有打开的框架的停止标签。
         *
         * 闭合标签策略（对齐 bgm.tv 网页版，话题 469999）：
         * 1. 匹配当前框架 → 正常闭合；
         * 2. 匹配某个祖先 → 隐式结束本框架，把闭合标签留给它的所有者
         *    （`[/s]` 先结束打开的 `[mask]`，然后才轮到 `[s]`）；
         * 3. **已知**标签名但没有任何打开的框架匹配 → 治愈：结束最内层打开的框架
         *    （在顶层则静默丢弃）；
         * 4. **未知**标签名 → 字面文本。
         */
        fun parseSequence(stopTag: String?): SequenceResult {
            val nodes = mutableListOf<BBCodeNode>()
            val text = StringBuilder()
            val itemStarts = mutableListOf<Int>()

            fun flush() {
                if (text.isEmpty()) return
                val last = nodes.lastOrNull()
                // 刚开了一个列表项时不并进上一项的文字
                if (last is BBCodeNode.Text && itemStarts.lastOrNull() != nodes.size) {
                    nodes[nodes.size - 1] = BBCodeNode.Text(last.value + text)
                } else {
                    nodes.add(BBCodeNode.Text(text.toString()))
                }
                text.clear()
            }

            while (cursor < source.length) {
                // 列表项只在 [list] 这一层认；包在 [b] 一类里面的 [*] 是字面文本（旧站同样只认 ul 下面的）
                if (stopTag == "list" && source.startsWith(LIST_ITEM, cursor)) {
                    flush()
                    itemStarts.add(nodes.size)
                    cursor += LIST_ITEM.length
                    continue
                }
                if (source[cursor] == '[') {
                    val tagStart = cursor
                    val closing = parseClosingTag()
                    if (closing != null) {
                        if (closing.name == stopTag) {
                            flush()
                            return SequenceResult(nodes, closed = true, itemStarts)
                        }
                        if (open.contains(closing.name)) {
                            // 交叠：闭合标签属于某个打开的外层框架——本框架在此隐式结束，
                            // 标签留给它的所有者。
                            cursor = tagStart
                            flush()
                            return SequenceResult(nodes, closed = false, itemStarts)
                        }
                        if (KNOWN_TAGS.contains(closing.name)) {
                            if (stopTag == null) {
                                // 顶层孤立闭合标签：网页版静默丢弃。
                                continue
                            }
                            // 治愈：手误的闭合标签结束最内层打开的框架，
                            // 而不是作为字面文本泄漏出去。
                            flush()
                            return SequenceResult(nodes, closed = false, itemStarts)
                        }
                        text.append(closing.raw)
                        continue
                    }

                    val opening = parseOpeningTag()
                    if (opening != null) {
                        // [code]：逐字捕获——内容是原始文本（wiki 模板、方括号、竖线），
                        // 当 BBCode 解析正是 463691 的失败模式。直接扫到 [/code]。
                        if (opening.name == "code") {
                            val afterOpening = cursor
                            val close = findClosingTag("code")
                            if (close != null) {
                                flush()
                                nodes.add(BBCodeNode.CodeBlock(source.substring(cursor, close.first)))
                                cursor = close.second
                                continue
                            }
                            // 未闭合的 [code]：按字面文本处理，回退到标签之后。
                            cursor = afterOpening
                            text.append(opening.raw)
                            continue
                        }

                        // 未知标签 + 后方根本没有闭合标签：字面括号文本——
                        // [url] 显示文本里的 wiki 片段（反馈 463691：整帖变成一个蓝色链接）。
                        // 这里用**向后查找**而不是"递归后回退"：后者会按括号数
                        // 指数级重解析后缀（实测 20 个片段冻结主线程 6.85 秒）。
                        if (!KNOWN_TAGS.contains(opening.name) && findClosingTag(opening.name) == null) {
                            text.append(opening.raw)
                            continue
                        }

                        // 已知标签（旧站宽松：未闭合时子节点一路吃到结尾），
                        // 以及"确实有闭合"的未知标签——照常递归。
                        // 嵌套深度有上限：超出的标签按字面文本，递归深度因此有界，不会被几千个 [b] 撑爆栈。
                        if (open.size >= MAX_NESTING) {
                            text.append(opening.raw)
                            continue
                        }
                        open.add(opening.name)
                        val childResult = parseSequence(opening.name)
                        open.removeAt(open.lastIndex)
                        flush()
                        val node = if (opening.name == "list") {
                            BBCodeNode.ListBlock(listItemsOf(childResult.nodes, childResult.itemStarts))
                        } else {
                            makeNode(opening, childResult.nodes, childResult.closed)
                        }
                        if (node is BBCodeNode.ListBlock && childResult.closed && "quote" !in open && "list" !in open) {
                            lists += ListSource(tagStart until cursor, node)
                        }
                        nodes.add(node)
                        continue
                    }
                }

                if (source[cursor] == '(') {
                    val smiley = parseSmiley()
                    if (smiley != null) {
                        flush()
                        nodes.add(BBCodeNode.Smiley(smiley))
                        continue
                    }
                }

                text.append(source[cursor])
                cursor++
            }

            flush()
            return SequenceResult(nodes, closed = false, itemStarts)
        }

        /**
         * [index] 处是否是 [parseSequence] 会认成标记的起点（[literal] 用），判据与它相同：已知名的闭合标签；
         * 已知名的开标签，或后面有闭合标签的未知名开标签；表情；`&nbsp;`。未知名又没有闭合的括号本来就按字面显示，不算。
         * `[*]` 也算：字面文字放进列表的一项里（投票选项），不能多分出一项。
         */
        fun startsMarkupAt(index: Int): Boolean {
            cursor = index
            return when (source[index]) {
                '[' -> parseClosingTag()?.let { it.name in KNOWN_TAGS }
                    ?: parseOpeningTag()?.let { it.name in KNOWN_TAGS || findClosingTag(it.name) != null }
                    ?: source.startsWith(LIST_ITEM, index)
                '(' -> parseSmiley() != null
                '&' -> source.startsWith(NBSP, index)
                else -> false
            }
        }

        private fun parseOpeningTag(): Tag? {
            if (cursor >= source.length || source[cursor] != '[') return null
            val start = cursor
            val firstEnd = source.indexOf(']', cursor)
            if (firstEnd < 0) return null
            var end = firstEnd
            // [url=...] 的值本身可能含 ']'，此时真正的结束符是下一个 ']'
            val header = source.substring(cursor + 1, firstEnd)
            if (header.lowercase().startsWith("url=") && firstEnd + 1 < source.length && source[firstEnd + 1] == ']') {
                end = firstEnd + 1
            }
            val raw = source.substring(start, end + 1)
            val body = source.substring(start + 1, end)
            if (body.startsWith("/")) return null
            val name = body.split('=', limit = 2).firstOrNull() ?: return null
            val normalized = name.trim().lowercase()
            // 只接受纯字母名——纯中文方括号（[动画]）绝不能当标签
            if (normalized.isEmpty() || !normalized.all { it.isLetter() }) return null
            val value = body.split('=', limit = 2).getOrNull(1)
            cursor = end + 1
            return Tag(normalized, value, raw)
        }

        private fun parseClosingTag(): Tag? {
            if (!source.startsWith("[/", cursor)) return null
            val end = source.indexOf(']', cursor)
            if (end < 0) return null
            val body = source.substring(cursor + 2, end).lowercase()
            if (body.isEmpty() || !body.all { it.isLetter() }) return null
            val start = cursor
            cursor = end + 1
            return Tag(body, null, source.substring(start, end + 1))
        }

        /**
         * `(name)` 形式的表情，是不是表情由 [SmileyCatalog.detectBare] 判，正文里普通的括号内容不会被当成表情。
         * 节点里留用户写的原文（不改大小写）：解析不出地址时界面按原文显示。
         */
        private fun parseSmiley(): String? {
            if (source[cursor] != '(') return null
            val end = source.indexOf(')', cursor)
            if (end < 0) return null
            val code = source.substring(cursor + 1, end)
            SmileyCatalog.detectBare(code) ?: return null
            cursor = end + 1
            return code
        }

        /**
         * 正文里所有闭合标签：小写名 → 各处 `[/name]` 的范围，按位置排好。第一次查找时扫一遍正文建好，此后每次查找是二分：
         * 未知名又没闭合的括号（`[动画]` 这类）每遇到一个都要查一次，逐个往后扫会让长正文变成平方级。
         * 名字的认法与 [parseClosingTag] 相同（纯字母，大小写不论）。
         */
        private val closingTags: Map<String, List<IntRange>> by lazy {
            val tags = HashMap<String, MutableList<IntRange>>()
            var at = source.indexOf("[/")
            while (at >= 0) {
                var end = at + 2
                while (end < source.length && source[end].isLetter()) end++
                if (end > at + 2 && end < source.length && source[end] == ']') {
                    tags.getOrPut(source.substring(at + 2, end).lowercase()) { ArrayList() }.add(at..end)
                }
                at = source.indexOf("[/", at + 1)
            }
            tags
        }

        /** [cursor] 起第一个 `[/name]`（开闭标签都做了小写归一，`[/CODE]` 也必须能闭合）：开始与结束（不含）位置。 */
        private fun findClosingTag(name: String): Pair<Int, Int>? {
            val tags = closingTags[name] ?: return null
            val found = tags.binarySearch { it.first.compareTo(cursor) }
            val tag = tags.getOrNull(if (found >= 0) found else -found - 1) ?: return null
            return tag.first to tag.last + 1
        }

        private fun makeNode(opening: Tag, children: List<BBCodeNode>, hasClosingTag: Boolean): BBCodeNode =
            when (opening.name) {
                "b" -> BBCodeNode.Bold(children)
                "i" -> BBCodeNode.Italic(children)
                "u" -> BBCodeNode.Underline(children)
                "s" -> BBCodeNode.Strike(children)
                "size" -> BBCodeNode.Size(opening.value?.toDoubleOrNull(), children)
                "color" -> BBCodeNode.Color(opening.value, children)
                "mask" -> BBCodeNode.Mask(children)
                "quote" -> BBCodeNode.Quote(children)
                "url" -> {
                    val value = opening.value
                        ?: children.joinToString("") { it.plainText() }.trim()
                    val url = value.ifEmpty { null }
                    // 链接里直接包着的图片（[url=…][img]…[/img][/url]）：点图去链接，与网页一致
                    BBCodeNode.Url(url, children.map { if (it is BBCodeNode.Image) it.copy(link = url) else it })
                }
                // [subject=12]名字[/subject]：条目链接；id 不是正整数时不认这个标签，按字面显示
                "subject" -> {
                    val id = (opening.value ?: children.joinToString("") { it.plainText() }).trim().toIntOrNull()
                    if (id != null && id > 0) {
                        BBCodeNode.Url(BgmWeb.subject(id), children)
                    } else {
                        BBCodeNode.Unknown(opening.name, opening.value, children, hasClosingTag)
                    }
                }
                "left", "center", "right" ->
                    BBCodeNode.Section(SectionLayout.Aligned(ALIGNS.getValue(opening.name)), children)
                // 取值只有 left / center / right（网页对别的值不认这个标签）
                "align" -> ALIGNS[opening.value?.trim()?.lowercase()]
                    ?.let { BBCodeNode.Section(SectionLayout.Aligned(it), children) }
                    ?: BBCodeNode.Unknown(opening.name, opening.value, children, hasClosingTag)
                "indent" -> BBCodeNode.Section(SectionLayout.Indent, children)
                // 浮动只有左右两侧；没写或写错的值按 left（盒子照样独占一段）
                "float" -> {
                    val side = if (opening.value?.trim()?.lowercase() == "right") BBCodeAlign.End else BBCodeAlign.Start
                    BBCodeNode.Section(SectionLayout.Floating(side), children)
                }
                "user" -> BBCodeNode.User((opening.value ?: children.joinToString("") { it.plainText() }).trim(), children)
                "img" -> {
                    // [img]url[/img] 与 [img=W,H]url[/img]：URL 在**子节点**里；
                    // 有值时它是尺寸声明（"250,250"，从别的论坛粘过来的）。
                    // 把值当 URL 会让 [img=250,250] 变成对 lain.bgm.tv 的无效请求 → 404
                    // → 媒体视图永远是个灰块（反馈 470703）。
                    var urlStr = children.joinToString("") { it.plainText() }.trim()
                    if (urlStr.isEmpty()) urlStr = opening.value?.trim().orEmpty()
                    SmileyCatalog.codeFromImageUrl(urlStr)?.let { return BBCodeNode.Smiley(it) }
                    val (w, h) = parseDimensions(opening.value)
                    BBCodeNode.Image(BangumiUrls.resolveImageUrl(urlStr), w, h, link = null)
                }
                "photo" -> {
                    // p1 上传的图片：[photo=171308]d6/c4/xxx.jpg[/photo]
                    // 值是站点图片 id，子节点是相对存储路径。
                    val urlStr = children.joinToString("") { it.plainText() }.trim()
                    BBCodeNode.Image(BangumiUrls.resolveImageUrl(urlStr), null, null, link = null)
                }
                else -> BBCodeNode.Unknown(opening.name, opening.value, children, hasClosingTag)
            }

        /**
         * 按 [itemStarts] 把 `[list]` 的子节点切成项：第一个 `[*]` 之前只有空白时丢掉，有字时自成一项；
         * 每项首尾的空白与换行去掉（写法上一项一行，换行不是内容）。
         */
        private fun listItemsOf(children: List<BBCodeNode>, itemStarts: List<Int>): List<List<BBCodeNode>> {
            val bounds = listOf(0) + itemStarts + children.size
            return bounds.zipWithNext().mapIndexedNotNull { index, (from, to) ->
                val item = trimItem(children.subList(from, to))
                item.takeUnless { index == 0 && it.isEmpty() }
            }
        }

        private fun trimItem(nodes: List<BBCodeNode>): List<BBCodeNode> {
            val trimmed = nodes.toMutableList()
            (trimmed.firstOrNull() as? BBCodeNode.Text)?.let { first ->
                val text = first.value.trimStart()
                if (text.isEmpty()) trimmed.removeAt(0) else trimmed[0] = BBCodeNode.Text(text)
            }
            (trimmed.lastOrNull() as? BBCodeNode.Text)?.let { last ->
                val text = last.value.trimEnd()
                if (text.isEmpty()) trimmed.removeAt(trimmed.lastIndex) else trimmed[trimmed.lastIndex] = BBCodeNode.Text(text)
            }
            return trimmed
        }

        /** `[img=250,250]` 或 `[img=250x250]`。 */
        private fun parseDimensions(value: String?): Pair<Double?, Double?> {
            if (value == null || !(value.contains(',') || value.contains('x'))) return null to null
            val parts = if (value.contains(',')) value.split(',') else value.split('x')
            if (parts.size < 2) return null to null
            return parts[0].trim().toDoubleOrNull() to parts[1].trim().toDoubleOrNull()
        }
    }
}
