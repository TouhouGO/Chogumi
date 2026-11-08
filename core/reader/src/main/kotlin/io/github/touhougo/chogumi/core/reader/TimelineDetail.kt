package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.hiddenFailureOf
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.toLoad
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.draft.ComposerDrafts
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.FloorReading
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.model.withMyReaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 动态详情面板的状态：属于哪条动态、它的回复、在途的贴贴、回复编辑器。它是宿主 ViewModel 页面状态里的一个字段。
 *
 * 动态本身不存在这里——它在仓库的动态缓存里，界面按 [id] 去当前列表取，
 * 贴贴成功后仓库回写缓存，列表行与面板同时变。
 *
 * 状态迁移写成显式方法：[id] 是面板属于哪条动态的唯一标识，回复取失败时保留它，界面才能重试。
 *
 * @property surface 回复所在的评论区，打开时按这条动态定下
 * @property comments 回复：与目录、日志的留言同一套楼层（[FloorComments]），楼主是发这条动态的人
 * @property composer 写回复的编辑器面板（[TimelineDetailActions.composer]），盖在详情面板上
 */
data class TimelineDetailState(
    val id: Long? = null,
    val surface: CommentSurface.Timeline? = null,
    val comments: FloorComments = FloorComments(),
    /** 在途的贴贴（乐观显示）；null 表示以缓存里的动态为准。 */
    val pendingReaction: PendingReaction? = null,
    val composer: ComposerState<TimelineReplyTarget> = ComposerState(),
) {
    /** 打开 [item]：面板换成这条动态的，只带过绝交名单（名单跟账号走，不跟面板）。 */
    fun opened(item: TimelineItemBo) = TimelineDetailState(
        id = item.id,
        surface = timelineSurfaceOf(item),
        comments = FloorComments(blocked = comments.blocked).withOp(item.uid),
    )

    /** 关上面板：同 [opened]，只留绝交名单。 */
    fun closed() = TimelineDetailState(comments = FloorComments(blocked = comments.blocked))

    fun withBlocked(blocked: BlockedUsers) = copy(comments = comments.withBlocked(blocked))
    fun repliesReloading() = copy(comments = comments.advance(Load.Pending))
    fun repliesSettled(result: Load<CommentFloors>) = copy(comments = comments.advance(result))
    fun withSort(sort: TopicSort) = copy(comments = comments.withSort(sort))
    fun toggleOpOnly() = copy(comments = comments.toggleOpOnly())
    fun reacting(value: Int?) = copy(pendingReaction = PendingReaction(value))
    fun reactionSettled() = copy(pendingReaction = null)

    /**
     * 面板上画的贴贴：在途时按 [withMyReaction] 先换上，否则就是缓存里的。换算是幂等的：仓库已回写、在途还没收起的那一刻
     * 再换一次结果不变，不会多算。
     */
    fun shownReactions(item: TimelineItemBo): List<CommentReactionBo> =
        pendingReaction?.let { item.reactions.withMyReaction(it.value) } ?: item.reactions
}

/** 一次在途的贴贴：[value] 是贴上的表情，null 是取消。 */
data class PendingReaction(val value: Int?)

/** 一条动态的回复所在的评论区。 */
fun timelineSurfaceOf(item: TimelineItemBo) = CommentSurface.Timeline(item.id)

/** 动态回复的编辑器写给谁：[timelineId] 那条动态里的 [reply]（[ReplyTarget.Topic] 是回复动态本身）。 */
data class TimelineReplyTarget(val timelineId: Long, val reply: ReplyTarget)

/** 动态回复草稿的键（种类内）：哪条动态、回复哪一条，同一条只留一份。 */
fun TimelineReplyTarget.draftKey(): String = "$timelineId/${reply.postId}"

/**
 * 动态详情面板的操作（动态页与个人「动态」tab 共用，流程只此一份，同 [FloorReactions]）：
 * 打开即拉回复；贴贴乐观更新，结束后收起在途态，失败提示走宿主的 [failures]；点楼层、点「回复」开编辑器（[composer]），
 * 发出后重新拉楼层并落到新回复上。
 * 面板状态由宿主持有：[state] 读、[update] 以变换函数原子地写进宿主的页面状态。
 *
 * @param replies 取一条动态的回复（两层楼）。
 * @param react 贴贴：动态 id、表情（null = 取消）。
 * @param reply 发回复：动态 id、正文、被回复的那条（0 = 回复动态本身）、人机验证令牌 → 新回复的 id。
 * @param drafts 回复的草稿存在哪（按 [TimelineReplyTarget.draftKey]）。
 * @param blocked 当前账号的绝交名单（`BlockedUsersFeed.current`）：跟着它走，面板里绝交了谁，那个人的楼当场不显示。
 */
class TimelineDetailActions(
    private val scope: CoroutineScope,
    private val state: () -> TimelineDetailState,
    private val update: ((TimelineDetailState) -> TimelineDetailState) -> Unit,
    private val replies: suspend (id: Long) -> Outcome<List<CommentThreadBo>>,
    private val react: suspend (id: Long, value: Int?) -> Outcome<Unit>,
    private val reply: suspend (id: Long, text: String, replyTo: Int, token: String) -> Outcome<Long>,
    drafts: ComposerDrafts<TimelineReplyTarget>,
    private val failures: FailureNotices,
    blocked: Flow<BlockedUsers>,
) {
    private var repliesJob: Job? = null

    init {
        scope.launch { blocked.collect { list -> update { it.withBlocked(list) } } }
    }

    /** 写回复的编辑器：失败留在面板里说（[ComposerActions.failures]）；发出后重新拉楼层，新回复已在里面再收起。 */
    val composer = ComposerActions<TimelineReplyTarget, Long>(
        scope = scope,
        state = { state().composer },
        update = { change -> update { it.copy(composer = change(it.composer)) } },
        submit = { target, draft, token -> reply(target.timelineId, target.reply.contentOf(draft.text), target.reply.replyTo, token) },
        afterSend = sent@{
            // 面板此刻还开着（收起在这之后），它写给的就是刚发出的那条动态
            val id = state().composer.target?.timelineId ?: return@sent
            reloadReplies(id)
        },
        afterUnsure = { state().id?.let { reloadReplies(it) } },
        drafts = drafts,
    )

    /**
     * 发出编辑器里写好的回复；成功且楼层重拉完后经 [land] 落到新回复上（与话题页同一件事：发出的那一刻触发，跳一次就结束）。
     * 新回复不在显示的楼层里（被「只看楼主」筛掉）时 [FloorJump.jumpToPost] 不动。
     */
    fun send(draft: ComposerDraft, token: String, land: (FloorReading, Int) -> Unit) {
        composer.send(draft, token) { replyId -> land(state().comments.reading, replyId.toInt()) }
    }

    fun open(item: TimelineItemBo) {
        update { it.opened(item) }
        loadReplies(item.id)
    }

    /** 回复取失败后重试：面板还是这条动态，楼主与排序不变。 */
    fun retry() {
        val id = state().id ?: return
        update { it.repliesReloading() }
        loadReplies(id)
    }

    fun selectSort(sort: TopicSort) = update { it.withSort(sort) }

    fun toggleOpOnly() = update { it.toggleOpOnly() }

    /** 开编辑器回复 [target]（点了一层楼，或「回复」这条动态本身）。 */
    fun reply(target: ReplyTarget) {
        val id = state().id ?: return
        composer.open(TimelineReplyTarget(id, target))
    }

    /** 重新拉楼层并等它落定（已显示的楼层留着，不回到占位）。 */
    private suspend fun reloadReplies(id: Long) {
        loadReplies(id)
        repliesJob?.join()
    }

    private fun loadReplies(id: Long) {
        repliesJob?.cancel()
        repliesJob = scope.launch {
            val result = replies(id).toLoad().map(::commentFloorsOf)
            var hidden: Failure? = null
            update { state ->
                if (state.id != id) return@update state
                hidden = state.comments.floors.hiddenFailureOf(result)
                state.repliesSettled(result)
            }
            hidden?.let { failures.post(it, retry = ::retry) }
        }
    }

    fun close() {
        repliesJob?.cancel()
        composer.close()
        update { it.closed() }
    }

    /** 贴贴（[value] 为 null 是取消）；在途时再点不发第二个请求。 */
    fun react(item: TimelineItemBo, value: Int?) {
        val current = state()
        if (current.id != item.id || current.pendingReaction != null) return
        update { it.reacting(value) }
        scope.launch {
            val result = react(item.id, value)
            update { if (it.id == item.id) it.reactionSettled() else it }
            if (result is Outcome.Failed) failures.post(result.failure)
        }
    }
}
