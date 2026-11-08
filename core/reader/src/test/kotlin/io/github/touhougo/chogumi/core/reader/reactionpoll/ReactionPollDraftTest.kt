package io.github.touhougo.chogumi.core.reader.reactionpoll

import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑器里的投票与正文里的投票块：写出去的块读得回同一个投票，正文其余部分原样留着。 */
class ReactionPollDraftTest {

    private val palette = COMMON_REACTIONS
    private val draft = ReactionPollDraft(
        question = "开局选哪个？",
        options = listOf(ReactionPollDraft.Option(54, "男"), ReactionPollDraft.Option(140, "女"), ReactionPollDraft.Option(80, "看心情")),
    )

    @Test
    fun 写出去的投票块阅读端认得出同一个投票() {
        val list = BBCodeParser.parse(reactionPollSourceOf(draft)).nodes.single() as BBCodeNode.ListBlock

        val poll = reactionPollOf(list, palette)

        assertEquals("开局选哪个？", poll?.question)
        assertEquals(listOf(54, 140, 80), poll?.options?.map { it.reaction })
        assertEquals(listOf("男", "女", "看心情"), poll?.options?.map { it.label })
    }

    @Test
    fun 正文接上投票再拆开还是原来的正文与投票() {
        val joined = joinReactionPoll("想做个统计。\n\n", draft)

        assertEquals(SplitReactionPoll("想做个统计。", draft), splitReactionPoll(joined, palette))
    }

    @Test
    fun 问题空着不写问题一项() {
        val source = reactionPollSourceOf(draft.copy(question = "  "))

        assertFalse(source.contains("投票："))
        assertEquals(draft.copy(question = ""), splitReactionPoll(source, palette).poll)
    }

    @Test
    fun 文字里的换行变成空格一项只占一行() {
        val source = reactionPollSourceOf(draft.copy(question = "第一行\n第二行", options = listOf(ReactionPollDraft.Option(54, "甲\n乙"), ReactionPollDraft.Option(140, "丙"))))

        val poll = splitReactionPoll(source, palette).poll

        assertEquals("第一行 第二行", poll?.question)
        assertEquals(listOf("甲 乙", "丙"), poll?.options?.map { it.label })
    }

    @Test
    fun 选项中的字面列表标记不会改变投票结构() {
        val literal = draft.copy(
            question = "问题[*]与[/list]",
            options = listOf(ReactionPollDraft.Option(54, "第一项[*]多出一项"), ReactionPollDraft.Option(140, "[/list]内容")),
        )
        assertTrue(literal.isComplete)
        assertEquals(literal, splitReactionPoll(reactionPollSourceOf(literal), palette).poll)
    }

    @Test
    fun 正文中间的投票块取出后前后接回() {
        val source = "前面一段\n${reactionPollSourceOf(draft)}\n后面一段"

        assertEquals(SplitReactionPoll("前面一段\n后面一段", draft), splitReactionPoll(source, palette))
    }

    @Test
    fun 认不出的列表留在正文里() {
        val source = "[list]\n[*]第一项\n[*]第二项\n[/list]"

        assertEquals(SplitReactionPoll(source, null), splitReactionPoll(source, palette))
    }

    @Test
    fun 有一项投不了的投票留在正文里不丢项() {
        val source = "[list]\n[*]投票：XX 的味道\n[*](bgm67) : 无味\n[*] : 不知道\n[*](bgm27) :腥味\n" +
            "[*][url=https://bgm.tv/dev/app/3792]投票组件[/url]\n[/list]"

        assertEquals(SplitReactionPoll(source, null), splitReactionPoll(source, palette))
    }

    @Test
    fun 不能贴贴的地方不取投票块() {
        val source = reactionPollSourceOf(draft)

        assertEquals(SplitReactionPoll(source, null), splitReactionPoll(source, emptyList()))
    }

    @Test
    fun 引用块里的投票留在正文里引用后面的投票照取() {
        val quoted = "[quote=某人]${reactionPollSourceOf(draft.copy(question = "引用里的"))}[/quote]"

        assertEquals(SplitReactionPoll(quoted, null), splitReactionPoll(quoted, palette))
        assertEquals(SplitReactionPoll(quoted, draft), splitReactionPoll(joinReactionPoll(quoted, draft), palette))
    }

    @Test
    fun 代码示例中的投票原样留在正文里() {
        val source = "[code]${reactionPollSourceOf(draft)}[/code]"
        assertEquals(SplitReactionPoll(source, null), splitReactionPoll(source, palette))
    }

    @Test
    fun 代码里的引用字面不阻挡后面的投票() {
        val code = "[code][quote]字面[/code]"
        assertEquals(SplitReactionPoll(code, draft), splitReactionPoll(joinReactionPoll(code, draft), palette))
    }

    @Test
    fun 有两个投票块时只取最后一个() {
        val first = reactionPollSourceOf(draft.copy(question = "第一个"))
        val source = "$first\n${reactionPollSourceOf(draft)}"

        assertEquals(SplitReactionPoll(first, draft), splitReactionPoll(source, palette))
    }

    @Test
    fun 新投票两个空选项取前两个表情加选项取第一个没用的() {
        val fresh = ReactionPollDraft.new(palette)
        assertEquals(palette.take(2), fresh.options.map { it.reaction })

        val changed = fresh.copy(options = listOf(ReactionPollDraft.Option(palette[0], ""), ReactionPollDraft.Option(palette[2], "")))
        assertEquals(palette[1], changed.withNewOption(palette).options.last().reaction)
    }

    @Test
    fun 表情用完不能再加选项() {
        val full = ReactionPollDraft("", palette.map { ReactionPollDraft.Option(it, "项") })

        assertFalse(full.canAdd(palette))
        assertEquals(full, full.withNewOption(palette))
    }

    @Test
    fun 每个选项都写了文字才算写完() {
        assertTrue(draft.isComplete)
        assertFalse(draft.copy(options = draft.options + ReactionPollDraft.Option(0, " ")).isComplete)
        assertNull(splitReactionPoll("没有投票", palette).poll)
    }
}
