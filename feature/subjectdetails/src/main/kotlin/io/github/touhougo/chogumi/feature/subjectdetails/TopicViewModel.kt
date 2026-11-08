package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.DraftRepository
import io.github.touhougo.chogumi.core.data.repository.TopicRepository
import io.github.touhougo.chogumi.core.data.repository.replyDrafts
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.FloorReading
import io.github.touhougo.chogumi.core.model.TopicDetailBo
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.reader.ComposerActions
import io.github.touhougo.chogumi.core.reader.ComposerState
import io.github.touhougo.chogumi.core.reader.EditTarget
import io.github.touhougo.chogumi.core.reader.FloorReactions
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.commentQuoteTargets
import io.github.touhougo.chogumi.core.reader.draftOf
import io.github.touhougo.chogumi.core.reader.replyComposer
import io.github.touhougo.chogumi.core.reader.replyTarget
import io.github.touhougo.chogumi.core.ui.moderation.ModerationController
import io.github.touhougo.chogumi.core.ui.moderation.ModerationState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 帖子页状态。显示楼层、引用落点、可点的引用都由 [reading]（[FloorReading]）派生；[composer] 是回复面板，
 * [editor] 是改自己楼的面板（同一个编辑器，两份状态：改楼不留草稿、不要人机验证）。
 */
data class TopicUiState(
    val topic: Load<TopicDetailBo> = Load.Pending,
    val reading: FloorReading = FloorReading(),
    val composer: ComposerState<ReplyTarget> = ComposerState(),
    val editor: ComposerState<EditTarget> = ComposerState(),
    /** 楼层长按的报告面板与绝交确认，只由 [TopicViewModel.moderation] 改。 */
    val moderation: ModerationState = ModerationState(),
)

/**
 * 帖子页（作品讨论版与小组的话题同一页，所以只依赖 core 模块、不用 subjectdetails 的内部内容，留在这里不搬）：服务端一次给全部楼层（实测，不分页），排序与「只看楼主」在本地做
 * （[FloorReading]）。引用反查依赖「更早的楼」，所以在完整楼层上按时间顺序算一次；大帖（几千楼）放到 [computeDispatcher]。
 * 回复、改楼、删楼成功后重新拉一次楼层（不回到骨架），新的楼层已在列表里，面板才收起。
 *
 * 回复留草稿（[DraftRepository]）：关掉面板、发送失败都不丢，下次回复同一层楼接着写。[draftPostId] 非 null 时是从
 * 「我的帖子」的草稿点进来的：进页就打开那份草稿（回复的是 [draftPostId] 那层楼，0 是话题本身）。
 */
class TopicViewModel(
    private val type: TopicType,
    private val topicId: Int,
    draftPostId: Int?,
    private val repository: TopicRepository,
    private val drafts: DraftRepository,
    private val computeDispatcher: CoroutineDispatcher,
    private val clock: AppClock,
    userModeration: UserModeration,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<TopicUiState>(TopicUiState()) {

    /** 帖子页的失败提示：贴贴失败、删楼失败、发出后重新拉楼层失败。 */
    val failures = FailureNotices()

    /** 楼层长按菜单里的报告、加好友、绝交（[ModerationController]），失败经 [failures] 提示。 */
    val moderation = ModerationController(
        scope = viewModelScope,
        read = { currentState.moderation },
        write = { change -> updateState { it.copy(moderation = change(it.moderation)) } },
        failures = failures,
        moderation = userModeration,
    )

    init {
        // 绝交名单变了（这一页长按绝交、别处绝交或取消），已显示的楼层当场跟上
        viewModelScope.launch { blockedUsers.current().collect { list -> updateState { it.copy(reading = it.reading.copy(blocked = list)) } } }
    }

    /** 楼层的贴贴（乐观更新 + 失败退回，见 [FloorReactions]）。 */
    private val reactions = FloorReactions(
        scope = viewModelScope,
        threads = { currentState.reading.threads },
        updateThreads = { transform ->
            updateState { it.copy(reading = it.reading.copy(threads = transform(it.reading.threads))) }
        },
        send = { postId, value -> repository.setPostReaction(type, postId, value) },
        failures = failures,
    )

    fun react(itemId: Int, value: Int?) = reactions.react(itemId, value)

    /** 回复面板：回复话题或某层楼（[ReplyTarget]），提交后重新拉楼层；草稿按回复的那层楼存。 */
    val composer = replyComposer(
        scope = viewModelScope,
        state = { currentState.composer },
        update = { transform -> updateState { it.copy(composer = transform(it.composer)) } },
        send = { content, replyTo, token -> repository.reply(type, topicId, content, replyTo, token) },
        reload = { refresh() },
        drafts = drafts.replyDrafts(type, topicId, clock, ReplyTarget::postId) { target, text ->
            target.draftOf(type, topicId, currentState.topic.valueOrNull()?.title.orEmpty(), text)
        },
    )

    /** 改自己的楼（[EditTarget]）：不要人机验证，提交后重新拉楼层。 */
    val editor = ComposerActions<EditTarget, Unit>(
        scope = viewModelScope,
        state = { currentState.editor },
        update = { transform -> updateState { it.copy(editor = transform(it.editor)) } },
        submit = { target, draft, _ -> repository.editPost(type, target.postId, target.contentOf(draft.text)) },
        afterSend = { refresh() },
        afterUnsure = { refresh() },
    )

    init {
        load()
        if (draftPostId != null) {
            composer.openSaved(
                loadTarget = { owner -> outcomeOf { drafts.get(owner, type, topicId, draftPostId)?.replyTarget() } },
                onFailure = { failures.post(it) },
            )
        }
    }

    fun edit(target: EditTarget) = editor.open(target, target.body)

    /** 删除自己的楼（确认在界面上做过）：成功后重新拉楼层，那层楼收成「已删除」；失败说原因。 */
    fun deletePost(postId: Int) {
        viewModelScope.launch {
            when (val result = repository.deletePost(type, postId)) {
                is Outcome.Success -> refresh()
                is Outcome.Failed -> failures.post(result.failure)
            }
        }
    }

    /** 整页重试：同一区块的上一轮先取消，不会有两轮交替写入。 */
    fun retry() = load()

    fun selectSort(sort: TopicSort) = updateState { it.copy(reading = it.reading.copy(sort = sort)) }

    fun toggleOpOnly() = updateState { it.copy(reading = it.reading.copy(opOnly = !it.reading.opOnly)) }

    /** 整页只有一份数据：话题与楼层是同一次请求。楼层的阅读状态在话题就绪之前落下，界面不会有一拍「有话题、没楼层」。 */
    private fun load() = launchSection(
        TOPIC_SECTION,
        failures = null,
        source = { loadOutcome { fetchWithThreads() } },
        get = { it.topic },
    ) { copy(topic = it) }

    private suspend fun fetchWithThreads(): Outcome<TopicDetailBo> {
        val result = fetch()
        if (result is Outcome.Success) {
            val quoteTargets = quoteTargetsOf(result.value)
            updateState { it.withThreadsOf(result.value, quoteTargets) }
        }
        return result
    }

    /** 内容已在屏幕上时重新拉（回复发出后）：失败时内容留着，提示原因并可重试。 */
    private suspend fun refresh() {
        when (val result = fetch()) {
            is Outcome.Success -> show(result.value)
            is Outcome.Failed -> failures.post(result.failure, retry = { viewModelScope.launch { refresh() } })
        }
    }

    private suspend fun fetch(): Outcome<TopicDetailBo> = outcomeOf { repository.getTopic(type, topicId) }

    private suspend fun show(topic: TopicDetailBo) {
        val quoteTargets = quoteTargetsOf(topic)
        updateState { it.withThreadsOf(topic, quoteTargets).copy(topic = Load.Ready(topic)) }
    }

    private suspend fun quoteTargetsOf(topic: TopicDetailBo) = withContext(computeDispatcher) { commentQuoteTargets(topic.threads) }

    private fun TopicUiState.withThreadsOf(topic: TopicDetailBo, quoteTargets: Map<Int, Int>) =
        copy(reading = reading.copy(threads = topic.threads, allQuoteTargets = quoteTargets, opCreatorId = topic.creatorId))
}

private const val TOPIC_SECTION = "topic"
