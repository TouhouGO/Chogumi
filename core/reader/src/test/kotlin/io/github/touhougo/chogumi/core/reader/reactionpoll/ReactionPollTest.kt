package io.github.touhougo.chogumi.core.reader.reactionpoll

import io.github.touhougo.chogumi.core.common.bbcode.BBCodeNode
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.model.COMMON_REACTIONS
import io.github.touhougo.chogumi.core.model.SUBJECT_COLLECT_REACTIONS
import io.github.touhougo.chogumi.core.model.reactionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** 贴贴投票与组件认得一样宽，列表里有放不进投票卡的项才退回列表；计票、投票与比例都从这一楼的贴贴来。 */
class ReactionPollTest {

    private val marker = "[*][url=https://bgm.tv/dev/app/3792]投票组件[/url]\n"

    private fun listBlock(source: String): BBCodeNode.ListBlock = assertIs(BBCodeParser.parse(source).nodes.single())

    private fun pollOf(body: String, palette: List<Int> = COMMON_REACTIONS) =
        reactionPollOf(listBlock("[list]\n$body$marker[/list]"), palette)

    @Test
    fun `认出组件写的投票块`() {
        val poll = pollOf("[*]投票：选择示例选项？\n[*](bgm38) : 甲\n[*](bgm124) : 乙\n")
        assertEquals(
            ReactionPoll(
                question = "选择示例选项？",
                options = listOf(ReactionPoll.Option(54, "bgm38", "甲", votable = true), ReactionPoll.Option(140, "bgm124", "乙", votable = true)),
            ),
            poll,
        )
    }

    @Test
    fun `问题可以省，半角冒号与全角冒号都认`() {
        val poll = pollOf("[*](bgm67)：甲\n[*](bgm63):乙\n")
        assertNull(poll?.question)
        assertEquals(listOf("甲", "乙"), poll?.options?.map { it.label })
    }

    @Test
    fun `没有组件链接的列表不是投票`() {
        assertNull(reactionPollOf(listBlock("[list][*](bgm38) : 甲[*](bgm124) : 乙[/list]"), COMMON_REACTIONS))
        assertNull(reactionPollOf(listBlock("[list][*](bgm38) : 甲[*][url=https://bgm.tv/dev/app/1]组件[/url][/list]"), COMMON_REACTIONS))
    }

    @Test
    fun `手写的投票：没写表情、表情不是贴贴的项照样列出，只是不能投`() {
        val poll = pollOf(
            "[*]投票：示例排序\n[*](bgm67) : 甲\n[*](bgm63) : 乙\n[*](bgm38) : 丙\n[*] : 未选择\n" +
                "[*](bgm46) : 丁\n[*](bgm27) :戊\n[*](bgm106) :其它\n",
        )
        assertEquals(
            ReactionPoll(
                question = "示例排序",
                options = listOf(
                    ReactionPoll.Option(0, "bgm67", "甲", votable = true),
                    ReactionPoll.Option(79, "bgm63", "乙", votable = true),
                    ReactionPoll.Option(54, "bgm38", "丙", votable = true),
                    ReactionPoll.Option(null, null, "未选择", votable = false),
                    ReactionPoll.Option(62, "bgm46", "丁", votable = true),
                    ReactionPoll.Option(null, "bgm27", "戊", votable = false),
                    ReactionPoll.Option(122, "bgm106", "其它", votable = true),
                ),
            ),
            poll,
        )
    }

    @Test
    fun `组件链接不在最后一项也认`() {
        val poll = reactionPollOf(listBlock("[list]\n$marker[*]投票：问题\n[*](bgm38) : 甲\n[/list]"), COMMON_REACTIONS)
        assertEquals("问题", poll?.question)
        assertEquals(listOf("甲"), poll?.options?.map { it.label })
    }

    @Test
    fun `同一个贴贴出现两次，后一项不计票不能投`() {
        val poll = pollOf("[*](bgm38) : 甲\n[*](bgm38) : 乙\n")
        assertEquals(listOf(54, null), poll?.options?.map { it.reaction })
        assertEquals(listOf(true, false), poll?.options?.map { it.votable })
    }

    @Test
    fun `这里贴不了的贴贴照样计票，只是不能投`() {
        // bgm63（79）不在作品吐槽可贴的表情里
        val poll = pollOf("[*](bgm63) : 甲\n", palette = SUBJECT_COLLECT_REACTIONS)!!
        assertEquals(ReactionPoll.Option(79, "bgm63", "甲", votable = false), poll.options.single())
        assertEquals(listOf(3), poll.tallyOf(listOf(reactionOf(value = 79, count = 3))).counts)
    }

    @Test
    fun `有一项放不进投票卡就不算投票`() {
        assertNull(pollOf("[*](bgm38) : 甲\n[*]随便说一句\n"), "中间夹着不是选项的一项")
        assertNull(pollOf("[*](bgm38) 甲\n"), "表情后没有冒号")
        assertNull(pollOf("[*](bgm38) : \n"), "选项没有文字")
        assertNull(pollOf("[*]投票：只有问题\n"), "没有选项")
    }

    @Test
    fun `选项里的剧透不泄漏`() {
        assertEquals("██", pollOf("[*](bgm38) : [mask]答案[/mask]\n")?.options?.single()?.label)
    }

    @Test
    fun `计票取这楼对应表情的贴贴数，我的贴贴在选项里才算投过`() {
        val poll = pollOf("[*](bgm38) : 甲\n[*](bgm124) : 乙\n[*](bgm67) : 丙\n")!!
        val tally = poll.tallyOf(
            listOf(
                reactionOf(value = 140, count = 33, selected = true),
                reactionOf(value = 54, count = 6),
                reactionOf(value = 104, count = 1),
            ),
        )
        assertEquals(ReactionPollTally(counts = listOf(6, 33, 0), mine = 140), tally)
        assertEquals(39, tally.total)
        assertNull(poll.tallyOf(listOf(reactionOf(value = 104, count = 1, selected = true))).mine, "贴的不是选项里的表情")
    }

    @Test
    fun `没有计票贴贴的项一直零票，也不会因为我没投而算成我投的`() {
        val poll = pollOf("[*](bgm38) : 甲\n[*] : 乙\n")!!
        val tally = poll.tallyOf(listOf(reactionOf(value = 54, count = 2)))
        assertEquals(listOf(2, 0), tally.counts)
        assertEquals(listOf(false, false), poll.options.map(tally::isMine))
    }

    @Test
    fun `点别的项投它，点我投的那项撤票`() {
        assertEquals(54, voteFor(54, mine = null))
        assertEquals(54, voteFor(54, mine = 140))
        assertNull(voteFor(54, mine = 54))
    }

    @Test
    fun `比例按总票数，没人投时为零`() {
        assertEquals(0.25f, shareOf(1, 4))
        assertEquals(0f, shareOf(0, 0))
    }
}
