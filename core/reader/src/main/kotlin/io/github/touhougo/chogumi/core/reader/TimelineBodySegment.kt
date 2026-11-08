package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog

/**
 * 一段正文切分后的单元。
 *
 * 文本与表情要分开，因为 Compose 里图片塞不进 `AnnotatedString` ——
 * 必须由调用方把两者交错渲染成多个 Composable。
 */
sealed interface BodySegment {
    data class Text(val value: String) : BodySegment
    data class Smiley(val code: String) : BodySegment
}

/**
 * 把正文切成「文本 / 表情」的有序单元。
 *
 * ## 为什么是纯文本而不是 HTML / BBCode
 *
 * 查证结论：服务端对时间线 status 做 `escape` → `unescape`
 * 成环，客户端拿到的是**反转义后的纯文本**；回复 content 两端都不处理，
 * 是**用户原样输入**。两者都不是 HTML，也都不走 BBCode 通路。
 *
 * 故这里只识别括号里的纯文本写法：`(bgmNN)`、`(musume_NN)` 一类与颜文字原文 `(=w=)`。
 *
 * ## 三条边界
 *
 * 1. **越界 code 退回原文**：`(bgm999)` 语法合法但无对应素材，
 *    显示原文比显示空白诚实 —— 用户至少能看到对方写了什么。
 * 2. **普通括号不是表情**：`(笑)`、`(1)` 原样保留。
 *    与 `BBCodeParser` 的判定同源，都在 [SmileyCatalog.detect]（解析器调它的不带括号形态 `detectBare`）。
 * 3. **字面 HTML 按字面显示**：`<b>`、`<script>` 作为文本呈现，
 *    绝不解析成富文本或执行。这是安全底线 —— 走 `HtmlCompat.fromHtml`
 *    就是在解析用户输入，方向是错的。
 */
fun segmentTimelineBody(body: String): List<BodySegment> {
    if (body.isEmpty()) return emptyList()
    val segments = mutableListOf<BodySegment>()
    val text = StringBuilder()
    var i = 0
    while (i < body.length) {
        if (body[i] == '(') {
            val close = body.indexOf(')', startIndex = i)
            if (close > i) {
                val candidate = body.substring(i, close + 1)
                val code = resolvableSmileyCode(candidate)
                // 只有**能解析出地址**的才当表情；越界的退回原文
                if (code != null) {
                    if (text.isNotEmpty()) {
                        segments += BodySegment.Text(text.toString())
                        text.clear()
                    }
                    segments += BodySegment.Smiley(code)
                    i = close + 1
                    continue
                }
            }
        }
        text.append(body[i])
        i++
    }
    if (text.isNotEmpty()) segments += BodySegment.Text(text.toString())
    return segments
}
