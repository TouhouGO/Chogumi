package io.github.touhougo.chogumi.core.reader.reactionpoll

import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog
import io.github.touhougo.chogumi.core.common.bbcode.excerptOf
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommentReactionBo

/**
 * 贴贴投票：bgm.tv 社区组件「讨论贴贴贴投票」写进正文的投票。站点没有投票功能，组件拿一楼的贴贴当票：
 * 每个选项对应一个贴贴表情，投票就是贴这个表情，票数就是这楼这个表情的贴贴数。
 *
 * 正文里的约定格式（发帖端由组件生成）：
 * ```
 * [list]
 * [*]投票：问题
 * [*](bgm38) : 选项一
 * [*](bgm124) : 选项二
 * [*][url=https://bgm.tv/dev/app/3792]投票组件[/url]
 * [/list]
 * ```
 * 认法与组件读投票时一致：列表里任何一项含组件介绍页的链接，就是投票（这一项本身不显示）；第一项以「投票：」开头是问题；
 * 其余每一项是一个选项，写法是「表情 : 文字」，表情可以没有。格式只在本包里认（[reactionPollOf]），组件改了格式只改这里；
 * 认不出时整块照常按列表显示。
 */
data class ReactionPoll(val question: String?, val options: List<Option>) {

    /**
     * 一个选项：[smiley] 是正文里写的表情（没写时 null），[label] 是文字。[reaction] 是计这一项票数的贴贴（reaction value）：
     * 表情不是贴贴、或前面的选项已经用了同一个贴贴（票数分不开）时为 null，这一项一直 0 票。
     * [votable]：这里能点它投票（[reaction] 不为空且在这里能贴）；不能投的项照常列出、照常显示票数。
     */
    data class Option(val reaction: Int?, val smiley: String?, val label: String, val votable: Boolean)
}

/** 组件在站点上的编号（介绍页 `/dev/app/3792`），投票块里有一项链接到这里。 */
internal const val REACTION_POLL_APP_ID = 3792

/** 问题一项的前缀（全角、半角冒号都认）。 */
private val QUESTION_PREFIXES = listOf("投票：", "投票:")

/** 选项里表情与文字之间的冒号。 */
private val OPTION_COLONS = charArrayOf(':', '：')

/**
 * 认贴贴投票：认得出返回投票，否则 null（照常是列表）。
 * 与组件一样宽：组件链接在哪一项都行；选项的表情不是贴贴、不在这里能贴的 [palette] 里、或没写表情，照样是选项，只是不能在这里投。
 * 但列表里的每一项都要有着落（问题、选项或组件链接），有一项既不是问题也不是「表情 : 文字」就不算投票：
 * 投票卡里放不下这一项，画成投票卡会把它弄丢。
 */
fun reactionPollOf(list: BBCodeNode.ListBlock, palette: List<Int>): ReactionPoll? {
    val markerAt = list.items.indexOfFirst { item -> item.any(::isMarker) }.takeIf { it >= 0 } ?: return null
    val body = list.items.filterIndexed { index, _ -> index != markerAt }
    val question = body.firstOrNull()?.let(::questionOf)
    val written = (if (question != null) body.drop(1) else body).map { item -> writtenOptionOf(item) ?: return null }
    if (written.isEmpty()) return null
    val used = mutableSetOf<Int>()
    val options = written.map { (smiley, label) ->
        val reaction = smiley?.let(SmileyCatalog::reactionOfCode)?.takeIf { used.add(it) }
        ReactionPoll.Option(reaction, smiley, label, votable = reaction != null && reaction in palette)
    }
    return ReactionPoll(question, options)
}

private fun isMarker(node: BBCodeNode): Boolean = node is BBCodeNode.Url && node.url?.trim() == BgmWeb.devApp(REACTION_POLL_APP_ID)

private fun questionOf(item: List<BBCodeNode>): String? {
    val text = excerptOf(item)
    val prefix = QUESTION_PREFIXES.firstOrNull { text.startsWith(it) } ?: return null
    return text.removePrefix(prefix).trim().ifEmpty { null }
}

/** 一项写的选项：开头的表情（可以没有，前面的空白不算）与冒号后面的文字；不是这个写法时 null。 */
private fun writtenOptionOf(item: List<BBCodeNode>): Pair<String?, String>? {
    val nodes = item.dropWhile { it is BBCodeNode.Text && it.value.isBlank() }
    val smiley = (nodes.firstOrNull() as? BBCodeNode.Smiley)?.code
    val rest = excerptOf(if (smiley != null) nodes.drop(1) else nodes)
    if (rest.firstOrNull()?.let { it in OPTION_COLONS } != true) return null
    val label = rest.drop(1).trim().ifEmpty { return null }
    return smiley to label
}

/**
 * 一楼贴贴下的计票：[counts] 与 [ReactionPoll.options] 一一对应；[mine] 是我投的选项（我的贴贴不在选项里时为 null）。
 * 服务端每人每楼只留一个贴贴，所以一人一票、改投就是换贴贴。
 */
data class ReactionPollTally(val counts: List<Int>, val mine: Int?) {
    val total: Int get() = counts.sum()
}

fun ReactionPoll.tallyOf(reactions: List<CommentReactionBo>): ReactionPollTally {
    val byValue = reactions.associateBy { it.value }
    val mine = reactions.firstOrNull { it.selected }?.value?.takeIf { value -> options.any { it.reaction == value } }
    return ReactionPollTally(options.map { option -> option.reaction?.let { byValue[it]?.count } ?: 0 }, mine)
}

/** 这一项是不是我投的：它有计票的贴贴，并且就是我贴的那个。 */
fun ReactionPollTally.isMine(option: ReactionPoll.Option): Boolean = mine != null && option.reaction == mine

/** 点一个选项（[reaction] 是它的贴贴）要贴什么：点我投的那项是撤票（null），点别的是投它（原来投了别的就是改投）。 */
fun voteFor(reaction: Int, mine: Int?): Int? = reaction.takeIf { it != mine }

/** 结果条的长度（0..1）：这项占总票数的比例，没人投时 0。 */
fun shareOf(count: Int, total: Int): Float = if (total > 0) count.toFloat() / total else 0f
