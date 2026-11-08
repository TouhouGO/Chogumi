package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.draft.ComposerDraftOwner
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.repository.TestComposerDrafts
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.TimelineDetailActions
import io.github.touhougo.chogumi.core.reader.TimelineDetailState
import io.github.touhougo.chogumi.core.reader.TimelineReplyTarget
import io.github.touhougo.chogumi.core.reader.draftKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** 动态详情面板里写回复：发出的正文、楼层回读和草稿落盘共用实际编辑器流程。 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineReplyFlowTest {
    private class Sent(val id: Long, val text: String, val replyTo: Int, val token: String)

    private class Harness(
        scope: CoroutineScope,
        replyResult: Outcome<Long> = Outcome.Success(501L),
        blocked: Flow<BlockedUsers> = flowOf(BlockedUsers.NONE),
    ) {
        var state = TimelineDetailState()
        val sent = mutableListOf<Sent>()
        var floors = listOf(thread(1))
        var repliesRequests = 0
        val savedDrafts = mutableMapOf<String, String>()
        val actions = TimelineDetailActions(
            scope = scope,
            state = { state },
            update = { state = it(state) },
            replies = { repliesRequests++; Outcome.Success(floors) },
            react = { _, _ -> Outcome.Success(Unit) },
            reply = { id, text, replyTo, token ->
                sent += Sent(id, text, replyTo, token)
                replyResult
            },
            drafts = object : TestComposerDrafts<TimelineReplyTarget>(scope) {
                override fun keyOf(target: TimelineReplyTarget): Any = target.draftKey()
                override suspend fun read(owner: ComposerDraftOwner, target: TimelineReplyTarget) =
                    Outcome.Success(savedDrafts[target.draftKey()]?.let { ComposerDraft("", it) })
                override suspend fun write(owner: ComposerDraftOwner, target: TimelineReplyTarget, draft: ComposerDraft): Outcome<Unit> {
                    savedDrafts[target.draftKey()] = draft.text
                    return Outcome.Success(Unit)
                }
                override suspend fun remove(owner: ComposerDraftOwner, target: TimelineReplyTarget): Outcome<Unit> {
                    savedDrafts.remove(target.draftKey())
                    return Outcome.Success(Unit)
                }
            },
            failures = FailureNotices(),
            blocked = blocked,
        )
    }

    @Test
    fun `回复一层楼：开头 @ 那个人发到这条动态，发出后楼层重拉并落到新回复`() = runTest {
        val h = Harness(backgroundScope)
        h.actions.open(item(42))
        runCurrent()
        h.actions.reply(ReplyTarget(postId = 1, replyTo = 0, quote = "[user=replier]回复的人[/user] "))
        runCurrent()
        assertEquals(42L, h.state.composer.target?.timelineId, "编辑器写给面板里这条动态")

        h.floors = listOf(thread(1), thread(501))
        var landed: Pair<List<Int>, Int>? = null
        h.actions.send(ComposerDraft("", "同意"), "fake-turnstile") { reading, id -> landed = reading.floorIndexByPost.keys.sorted() to id }
        runCurrent()

        val request = h.sent.single()
        assertEquals(42L, request.id)
        assertEquals("[user=replier]回复的人[/user] 同意", request.text)
        assertEquals(0, request.replyTo, "动态的回复只有一层")
        assertEquals("fake-turnstile", request.token)
        assertEquals(2, h.repliesRequests, "发出后重拉一次楼层")
        assertEquals(listOf(1, 501), h.state.comments.floors.valueOrNull()!!.threads.map { it.anchor.id })
        assertEquals(listOf(1, 501), landed?.first, "落点用重拉之后的楼层")
        assertEquals(501, landed?.second)
        assertTrue(h.state.composer.sent, "新回复已在楼层里，面板收起")
    }

    @Test
    fun `面板里绝交了一个人，这个人的回复当场不显示且不重拉，关掉再开别的动态也不显示`() = runTest {
        val list = MutableStateFlow(BlockedUsers.NONE)
        val h = Harness(backgroundScope, blocked = list)
        h.floors = listOf(thread(1), thread(2, creatorId = 8))
        fun shown() = h.state.comments.reading.replies.map { it.thread.anchor.id }
        h.actions.open(item(42))
        runCurrent()
        assertEquals(listOf(1, 2), shown())

        list.value = BlockedUsers(setOf(8))
        runCurrent()
        assertEquals(listOf(1), shown())
        assertEquals(1, h.repliesRequests, "名单变了只重排已显示的楼层")

        h.actions.close()
        h.actions.open(item(43))
        runCurrent()
        assertEquals(listOf(1), shown(), "名单跟账号走，换一条动态照样按它藏")
    }

    @Test
    fun `回复动态本身是新开一条，不带 @`() = runTest {
        val h = Harness(backgroundScope)
        h.actions.open(item(42))
        h.actions.reply(ReplyTarget.Topic)
        runCurrent()
        h.actions.composer.send(ComposerDraft("", "好"), "t")
        runCurrent()
        assertEquals("好", h.sent.single().text)
        assertEquals(0, h.sent.single().replyTo)
    }

    @Test
    fun `发送失败面板与正文都留着，不重拉也不落楼`() = runTest {
        val h = Harness(backgroundScope, replyResult = Outcome.Failed(Failure.Offline))
        h.actions.open(item(42))
        h.actions.reply(ReplyTarget.Topic)
        runCurrent()
        h.actions.composer.send(ComposerDraft("", "没发出去"), "t")
        runCurrent()
        assertEquals(ReplyTarget.Topic, h.state.composer.target?.reply)
        assertEquals(false, h.state.composer.sent)
        assertEquals(1, h.repliesRequests)
        assertEquals("没发出去", h.savedDrafts["42/0"], "没发出去的字按这条动态、回复谁存成草稿")
    }

    @Test
    fun `关掉详情面板时编辑器一起关，没落下的字存进草稿`() = runTest {
        val h = Harness(backgroundScope)
        h.actions.open(item(42))
        h.actions.reply(ReplyTarget.Topic)
        runCurrent()
        h.actions.composer.edit(ComposerDraft("", "写了一半"))
        h.actions.close()
        runCurrent()
        assertNull(h.state.composer.target)
        assertEquals("写了一半", h.savedDrafts["42/0"])
    }

    private companion object {
        fun item(id: Long) = TimelineItemBo(id = id, uid = 9, user = null, cat = 5, type = 1, createdAt = 0, text = "")

        fun thread(id: Int, creatorId: Int = 7) = CommentThreadBo(
            anchor = CommentItemBo(
                id = id,
                mainId = 42,
                relatedId = 0,
                creatorId = creatorId,
                creatorNickname = "回复的人",
                creatorUsername = "replier",
                creatorAvatar = null,
                content = "第 $id 条",
                createdAt = 0,
                state = CommentState.NORMAL,
            ),
            replies = emptyList(),
        )
    }
}
