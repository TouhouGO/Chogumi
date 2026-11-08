package io.github.touhougo.chogumi.core.common.bbcode

/**
 * 编辑器工具栏包裹选中文字的 BBCode 标记，与网页回复框同一套。字号三档取网页回复框「文字尺寸」下拉的大、中、小
 * （`[size=25]` / `[size=12]` / `[size=10]`）。图片与链接要先填地址，走 [TextEdit.image] / [TextEdit.link]，不在这里。
 */
enum class BBCodeTag(val open: String, val close: String) {
    BOLD("[b]", "[/b]"),
    ITALIC("[i]", "[/i]"),
    UNDERLINE("[u]", "[/u]"),
    STRIKE("[s]", "[/s]"),
    SIZE_LARGE("[size=25]", "[/size]"),
    SIZE_MEDIUM("[size=12]", "[/size]"),
    SIZE_SMALL("[size=10]", "[/size]"),
    QUOTE("[quote]", "[/quote]"),
    MASK("[mask]", "[/mask]"),
    CODE("[code]", "[/code]"),
}

/** 编辑器里的正文与选区；[selectionStart] 与 [selectionEnd] 相等即光标，先后不限（反向拖选时起点在后）。 */
data class TextEdit(val text: String, val selectionStart: Int, val selectionEnd: Int) {
    private val from get() = minOf(selectionStart, selectionEnd)
    private val to get() = maxOf(selectionStart, selectionEnd)

    /** 选中的文字（插入链接时预填成链接文字）；没有选中为空串。 */
    val selected: String get() = text.substring(from, to)

    /** 工具栏按钮：有选中的文字就用 [tag] 包起来、光标放到闭标签后面；没有选中就插入一对标签、光标放在中间。 */
    fun wrapWith(tag: BBCodeTag): TextEdit {
        val wrapped = tag.open + selected + tag.close
        val caret = if (selected.isEmpty()) from + tag.open.length else from + wrapped.length
        return TextEdit(text.substring(0, from) + wrapped + text.substring(to), caret, caret)
    }

    /** 插入一段文字（表情）：替换选中的部分，光标放到插入的文字后面。 */
    fun insert(snippet: String): TextEdit {
        val caret = from + snippet.length
        return TextEdit(text.substring(0, from) + snippet + text.substring(to), caret, caret)
    }

    /** 插入链接，替换选中的部分：有文字是 `[url=地址]文字[/url]`，文字空着就是 `[url]地址[/url]`（网页把地址本身当文字）。 */
    fun link(url: String, label: String): TextEdit =
        insert(if (label.isBlank()) "[url]${url.trim()}[/url]" else "[url=${url.trim()}]$label[/url]")

    /** 插入图片，替换选中的部分：`[img]地址[/img]`。 */
    fun image(url: String): TextEdit = insert("[img]${url.trim()}[/img]")
}

/** 表情在正文里的写法：`(bgm38)`，与网页插入的一致（[SmileyCatalog.detect] 认的就是它）。 */
fun smileyTextOf(code: String): String = "($code)"
