package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineReactionTarget
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.model.reactionOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 动态详情面板的纯数据部分：状态迁移与贴贴的乐观显示。
 * 走真实调用链的部分（打开、串台、贴贴回写）见 feature:profile 的 `ProfileTimelineActionsPipelineTest`。
 */
class TimelineDetailStateTest {

    @Test
    fun `初始为空且回复未开始取`() {
        val state = TimelineDetailState()
        assertNull(state.id)
        assertNull(state.surface)
        assertEquals(FloorComments(), state.comments)
        assertNull(state.pendingReaction)
    }

    /** 打开时立刻带上这条动态：界面据此立刻弹层、画楼层占位；上一条的回复与在途贴贴都不带过来，楼主是发动态的人。 */
    @Test
    fun `打开时立即带目标ID与楼主并清掉上一条的内容`() {
        val previous = TimelineDetailState().opened(item(id = 1)).repliesSettled(ready(listOf(thread(1)))).reacting(79)
        val opened = previous.opened(item(id = 42))
        assertEquals(42L, opened.id)
        assertEquals(CommentSurface.Timeline(timelineId = 42L), opened.surface)
        assertEquals(Load.Pending, opened.comments.floors)
        assertTrue(opened.comments.reading.threads.isEmpty())
        assertEquals(9, opened.comments.reading.opCreatorId)
        assertNull(opened.pendingReaction)
    }

    @Test
    fun `回复到达后楼层到位`() {
        val loaded = TimelineDetailState().opened(item(id = 42)).repliesSettled(ready(listOf(thread(1), thread(2))))
        assertEquals(42L, loaded.id)
        assertEquals(2, loaded.comments.reading.threads.size)
    }

    /** 失败**保留 ID**：界面据此给出重试入口，而不是把面板关掉让用户重新去找那条动态。 */
    @Test
    fun `回复取失败保留目标ID以便重试`() {
        val failed = TimelineDetailState().opened(item(id = 42)).repliesSettled(Load.Failed(Failure.Offline))
        assertEquals(42L, failed.id)
        assertEquals(Load.Failed(Failure.Offline), failed.comments.floors)
    }

    @Test
    fun `重试回到未取回且楼主不变`() {
        val retrying = TimelineDetailState().opened(item(id = 42)).repliesSettled(Load.Failed(Failure.Offline)).repliesReloading()
        assertEquals(Load.Pending, retrying.comments.floors)
        assertEquals(9, retrying.comments.reading.opCreatorId)
    }

    /** 已有楼层时重取失败：楼层留在屏上（失败另外提示，见 `TimelineDetailActions`），不变成整块的错误。 */
    @Test
    fun `已有楼层时重取失败楼层留着`() {
        val loaded = TimelineDetailState().opened(item(id = 42)).repliesSettled(ready(listOf(thread(1))))
        val failed = loaded.repliesReloading().repliesSettled(Load.Failed(Failure.Offline))
        assertEquals(loaded.comments.floors, failed.comments.floors)
    }

    @Test
    fun `排序与只看楼主只改评论区的读法`() {
        val loaded = TimelineDetailState().opened(item(id = 42)).repliesSettled(ready(listOf(thread(1))))
        val sorted = loaded.withSort(TopicSort.LATEST)
        assertEquals(TopicSort.LATEST, sorted.comments.reading.sort)
        assertEquals(loaded.comments.reading.threads, sorted.comments.reading.threads)
        assertTrue(loaded.toggleOpOnly().comments.reading.opOnly)
    }

    @Test
    fun `贴贴在途与落定只动在途字段`() {
        val loaded = TimelineDetailState().opened(item(id = 42)).repliesSettled(ready(listOf(thread(1))))
        val reacting = loaded.reacting(79)
        assertEquals(PendingReaction(79), reacting.pendingReaction)
        assertEquals(PendingReaction(null), loaded.reacting(null).pendingReaction)
        assertEquals(loaded, reacting.reactionSettled())
    }

    // ---- 面板上画的贴贴 ----

    /** 各种表情都是贴贴：面板原样画出缓存里的每一种，不只认某一个值（卡片上有 7 个、面板上却是「贴贴 0」）。 */
    @Test
    fun `没有在途时画缓存里的每一种贴贴`() {
        val reactions = listOf(reactionOf(value = 79, count = 7), reactionOf(value = 104, count = 1))
        val item = item(reactions = reactions)
        assertEquals(reactions, TimelineDetailState().opened(item).shownReactions(item))
    }

    @Test
    fun `在途贴上别人贴过的表情时乐观加一并标成我的`() {
        val item = item(reactions = listOf(reactionOf(value = 79, count = 7)))
        assertEquals(
            listOf(reactionOf(value = 79, count = 8, selected = true)),
            TimelineDetailState().opened(item).reacting(79).shownReactions(item),
        )
    }

    /** 每人每条只留一个：换成别的表情时，原来那个减一（减到 0 整项去掉），新的追加在末尾。 */
    @Test
    fun `在途换表情时撤掉我原来那个`() {
        val item = item(reactions = listOf(reactionOf(value = 0, count = 1, selected = true), reactionOf(value = 79, count = 2)))
        assertEquals(
            listOf(reactionOf(value = 79, count = 3, selected = true)),
            TimelineDetailState().opened(item).reacting(79).shownReactions(item),
        )
    }

    @Test
    fun `在途取消时撤掉我的那个`() {
        val item = item(reactions = listOf(reactionOf(value = 79, count = 3, selected = true)))
        assertEquals(
            listOf(reactionOf(value = 79, count = 2)),
            TimelineDetailState().opened(item).reacting(null).shownReactions(item),
        )
    }

    /** 仓库已回写缓存、在途还没收起的那一刻：再换一次结果不变，不会多算一次。 */
    @Test
    fun `缓存已回写而在途未收起时不重复加减`() {
        val written = item(reactions = listOf(reactionOf(value = 79, count = 8, selected = true)))
        assertEquals(written.reactions, TimelineDetailState().opened(written).reacting(79).shownReactions(written))
        val cancelled = item(reactions = listOf(reactionOf(value = 79, count = 7)))
        assertEquals(cancelled.reactions, TimelineDetailState().opened(cancelled).reacting(null).shownReactions(cancelled))
    }

    private fun ready(threads: List<CommentThreadBo>) = Load.Ready(commentFloorsOf(threads))

    private fun item(id: Long = 42, reactions: List<CommentReactionBo> = emptyList()) = TimelineItemBo(
        id = id,
        uid = 9,
        user = null,
        cat = 5,
        type = 1,
        createdAt = 0,
        text = "",
        reactions = reactions,
        reactionTarget = TimelineReactionTarget.Status,
    )

    private fun thread(id: Int) = CommentThreadBo(
        anchor = CommentItemBo(
            id = id,
            mainId = 42,
            relatedId = 0,
            creatorId = 7,
            creatorNickname = "回复的人",
            creatorUsername = "replier",
            creatorAvatar = null,
            content = "内容",
            createdAt = id.toLong(),
            state = CommentState.NORMAL,
        ),
    )
}
