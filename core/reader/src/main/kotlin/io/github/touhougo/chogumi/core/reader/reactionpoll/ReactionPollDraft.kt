package io.github.touhougo.chogumi.core.reader.reactionpoll

import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import io.github.touhougo.chogumi.core.common.bbcode.smileyTextOf
import io.github.touhougo.chogumi.core.model.BgmWeb

/**
 * 编辑器里正在写的贴贴投票：问题（可空）与选项，每个选项是一个贴贴（reaction value）配一句文字。
 * 存的始终是正文里的投票块（[reactionPollSourceOf]）：草稿、改自己的楼时从正文读回（[splitReactionPoll]），编辑器里画成投票卡。
 */
data class ReactionPollDraft(val question: String, val options: List<Option>) {

    data class Option(val reaction: Int, val label: String)

    /** 每个选项都写了文字才能发；选项至少 [REACTION_POLL_MIN_OPTIONS] 个由编辑器保证（少于它不给删）。 */
    val isComplete: Boolean get() = options.size >= REACTION_POLL_MIN_OPTIONS && options.all { it.label.isNotBlank() }

    /** 还能不能加选项：一个选项占一个贴贴，[palette] 里的用完就到头。 */
    fun canAdd(palette: List<Int>): Boolean = palette.any { value -> options.none { it.reaction == value } }

    /** 加一个选项，贴贴取 [palette] 里第一个还没用的；用完了原样返回。 */
    fun withNewOption(palette: List<Int>): ReactionPollDraft {
        val free = palette.firstOrNull { value -> options.none { it.reaction == value } } ?: return this
        return copy(options = options + Option(free, ""))
    }

    companion object {
        /** 新开的投票：两个空选项，贴贴取 [palette] 的前两个。 */
        fun new(palette: List<Int>): ReactionPollDraft =
            ReactionPollDraft("", palette.take(REACTION_POLL_MIN_OPTIONS).map { Option(it, "") })
    }
}

/** 一个投票至少两个选项（只有一项不成投票）。 */
const val REACTION_POLL_MIN_OPTIONS = 2

/** 组件介绍页链接的文字（读的时候只认链接地址，不认这几个字）。 */
private const val MARKER_TEXT = "投票组件"

/**
 * 投票写进正文的样子，与组件的约定格式一致（格式见 [ReactionPoll]）：问题空着就不写问题一项。
 * 文字里的换行换成空格：一项只能占一行，否则读的时候分不清项。
 */
fun reactionPollSourceOf(draft: ReactionPollDraft): String = buildString {
    appendLine("[list]")
    draft.question.oneLine().takeIf { it.isNotEmpty() }?.let { appendLine("[*]投票：${BBCodeParser.literal(it)}") }
    draft.options.forEach { option ->
        val code = requireNotNull(SmileyCatalog.codeOfReaction(option.reaction)) { "不是贴贴：${option.reaction}" }
        appendLine("[*]${smileyTextOf(code)} : ${BBCodeParser.literal(option.label.oneLine())}")
    }
    appendLine("[*][url=${BgmWeb.devApp(REACTION_POLL_APP_ID)}]$MARKER_TEXT[/url]")
    append("[/list]")
}

private fun String.oneLine(): String = replace(Regex("\\s*\\n\\s*"), " ").trim()

/** 正文与投票合成要发出去的内容：投票块放在正文后面，另起一行。 */
fun joinReactionPoll(body: String, draft: ReactionPollDraft?): String {
    if (draft == null) return body
    val block = reactionPollSourceOf(draft)
    val text = body.trimEnd()
    return if (text.isEmpty()) block else "$text\n$block"
}

/** [splitReactionPoll] 的结果：去掉投票块后的正文，与读回的投票（没有时为 null）。 */
data class SplitReactionPoll(val body: String, val poll: ReactionPollDraft?)

/**
 * 从正文里取出投票块（一楼只有一份贴贴，只取最后一个认得出的）：认得出的块从正文里拿掉，前后接回；
 * 认不出的列表、引用块里的列表（阅读端也不把它画成投票）原样留在正文里。[palette] 为空（这里不能贴贴）时不取，投票块照常是正文。
 * 编辑器只写得出每项都是这里能贴的贴贴的投票：有一项不能投（没写表情、表情不是贴贴或重复）的投票也原样留在正文里，免得读回时丢项。
 */
fun splitReactionPoll(source: String, palette: List<Int>): SplitReactionPoll {
    if (palette.isEmpty()) return SplitReactionPoll(source, null)
    val found = BBCodeParser.interactiveLists(source).lastOrNull { draftOf(it.node, palette) != null }
        ?: return SplitReactionPoll(source, null)
    val draft = requireNotNull(draftOf(found.node, palette))
    val before = source.substring(0, found.range.first).trimEnd()
    val after = source.substring(found.range.last + 1).trimStart()
    val body = listOf(before, after).filter { it.isNotEmpty() }.joinToString("\n")
    return SplitReactionPoll(body, draft)
}

/** 解析器交出的列表是否是编辑器写得出的投票，并且每项都能投。 */
private fun draftOf(list: BBCodeNode.ListBlock, palette: List<Int>): ReactionPollDraft? {
    val poll = reactionPollOf(list, palette) ?: return null
    val options = poll.options.map { option -> ReactionPollDraft.Option(option.reaction?.takeIf { option.votable } ?: return null, BBCodeParser.literalText(option.label)) }
    return ReactionPollDraft(BBCodeParser.literalText(poll.question.orEmpty()), options)
}
