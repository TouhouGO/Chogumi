package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString

/** 被截短的那一段末尾补的省略号。 */
internal const val FIT_ELLIPSIS = "…"

/**
 * 「一串文字放不下一行时，只截其中一段」的**唯一判定**（搜索框提示与 [MetaText] 共用）。
 *
 * [fits] 回答「那一段只保留前 `keep` 个字（keep == [length] 即原样）时，整串放不放得下」，单调：keep 越小越容易放下。
 * 返回要保留的字数（0 until [length]，截断处补 [FIT_ELLIPSIS]）；原样已放得下，或连只剩「…」都放不下时返回 null，
 * 意思是「别动」——放不下的部分交给 Text 在句尾省略。
 */
internal fun fitKeep(length: Int, fits: (keep: Int) -> Boolean): Int? {
    if (length <= 0 || fits(length)) return null
    var low = 0
    var high = length - 1
    var best = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (fits(mid)) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best.takeIf { it >= 0 }
}

/** 「只保留前 [keep] 个字 + …」：全仓截短一段文字的唯一写法。 */
internal fun ellipsized(text: AnnotatedString, keep: Int): AnnotatedString = buildAnnotatedString {
    append(text.subSequence(0, keep))
    append(FIT_ELLIPSIS)
}

/** 可被截短的末段至少要留下的字数；再少只剩「…」，不如整段去掉。 */
internal const val SHRINK_MIN_KEEP = 2

/**
 * 让 [parts] 拼起来放进 [maxWidth]：放得下原样返回；放不下时只把第 [shrink] 段截到最长的「前缀 + …」，其余各段不动。
 * 放不下也截不动（连「…」都放不下）时原样返回。[widthOf] 量一串候选各段拼起来的宽度（调用方负责把段与段之间的
 * 空白，算进去）。
 */
internal fun fitSegment(
    parts: List<AnnotatedString>,
    shrink: Int,
    maxWidth: Int,
    widthOf: (List<AnnotatedString>) -> Int,
): List<AnnotatedString> {
    val target = parts.getOrNull(shrink) ?: return parts
    fun candidate(keep: Int) =
        if (keep >= target.length) parts else parts.toMutableList().also { it[shrink] = ellipsized(target, keep) }
    val keep = fitKeep(target.length) { widthOf(candidate(it)) <= maxWidth }
    return if (keep == null) parts else candidate(keep)
}

/**
 * 越靠后越可有可无的一串段：放不下（[widthOf] 量一串段拼起来的宽度，含段间非文字宽度）就从末尾起整段去掉，
 * 直到放下、只剩一段，或剩下的最后一段已不是可丢的（[IndexedValue.index] 小于 [droppableFrom]）。末段正是
 * [shrinkIndex] 时先看截短能不能放下且至少留 [SHRINK_MIN_KEEP] 个字，能就停在这里（由调用方去截），不能才整段去掉。
 */
internal fun dropTrailingToFit(
    parts: List<IndexedValue<AnnotatedString>>,
    droppableFrom: Int,
    shrinkIndex: Int?,
    maxWidth: Int,
    widthOf: (List<IndexedValue<AnnotatedString>>) -> Int,
): List<IndexedValue<AnnotatedString>> {
    var kept = parts
    while (kept.size > 1 && widthOf(kept) > maxWidth && kept.last().index >= droppableFrom) {
        val last = kept.last()
        if (last.index == shrinkIndex) {
            val keep = fitKeep(last.value.length) { widthOf(kept.dropLast(1) + IndexedValue(last.index, ellipsized(last.value, it))) <= maxWidth }
            if (keep != null && keep >= SHRINK_MIN_KEEP) return kept
        }
        kept = kept.dropLast(1)
    }
    return kept
}

/**
 * 搜索框提示：让 [text] 放进 [maxWidth]，只缩短句中的 [value] 那一段（如标签名），其余文字保持完整。
 * [value] 为空或不在句中时原样返回，由 Text 在句尾省略。
 */
internal fun fitPlaceholder(text: String, value: String?, maxWidth: Int, widthOf: (String) -> Int): String {
    if (value.isNullOrEmpty()) return text
    val at = text.indexOf(value)
    if (at < 0) return text
    val parts = listOf(text.substring(0, at), value, text.substring(at + value.length)).map(::AnnotatedString)
    return fitSegment(parts, shrink = 1, maxWidth = maxWidth) { widthOf(it.joinToString("") { part -> part.text }) }
        .joinToString("") { it.text }
}
