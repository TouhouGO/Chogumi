package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.CommentRepository
import io.github.touhougo.chogumi.core.data.repository.SubjectCommunityRepository
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.reader.ComposerState
import io.github.touhougo.chogumi.core.reader.FloorComments
import io.github.touhougo.chogumi.core.reader.FloorReactions
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.commentFloorsOf
import io.github.touhougo.chogumi.core.reader.replyComposer
import io.github.touhougo.chogumi.core.ui.moderation.ModerationController
import io.github.touhougo.chogumi.core.ui.moderation.ModerationState
import kotlinx.coroutines.launch

/** 一集的评论区：楼层（[comments]）与写评论的编辑器面板（[composer]）。 */
data class EpisodeCommentsUiState(
    val comments: FloorComments = FloorComments(),
    val composer: ComposerState<ReplyTarget> = ComposerState(),
    /** 楼层长按的报告面板与绝交确认，只由 [EpisodeCommentsViewModel.moderation] 改。 */
    val moderation: ModerationState = ModerationState(),
)

/**
 * 单集的评论（章节长按面板与单集讨论页是同一份）：楼层一次取全，排序在本地（[FloorComments]）；
 * 单集评论没有楼主，所以不出「只看楼主」。贴贴走与讨论版同一个 [FloorReactions]（乐观更新 + 失败退回）；
 * 写评论、回复一层楼走与讨论版同一个编辑器（[replyComposer]），发出后重拉楼层。
 */
class EpisodeCommentsViewModel(
    private val episodeId: Int,
    private val repository: SubjectCommunityRepository,
    comments: CommentRepository,
    userModeration: UserModeration,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<EpisodeCommentsUiState>(EpisodeCommentsUiState()) {

    /** 面板的失败提示：贴贴失败、发出后重拉楼层失败。 */
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
        viewModelScope.launch { blockedUsers.current().collect { list -> updateState { it.copy(comments = it.comments.withBlocked(list)) } } }
    }

    private val surface = CommentSurface.Episode(episodeId)

    private val reactions = FloorReactions(
        scope = viewModelScope,
        threads = { currentState.comments.reading.threads },
        updateThreads = { transform -> updateState { it.copy(comments = it.comments.withThreads(transform)) } },
        send = repository::setEpisodeCommentReaction,
        failures = failures,
    )

    /** 写评论（新的一条）或回复一层楼。 */
    val composer = replyComposer(
        scope = viewModelScope,
        state = { currentState.composer },
        update = { transform -> updateState { it.copy(composer = transform(it.composer)) } },
        send = { content, replyTo, token -> comments.reply(surface, content, replyTo, token) },
        reload = {
            load()
            joinSection(COMMENTS_SECTION)
        },
        drafts = comments.replyDrafts(surface, ReplyTarget::postId),
    )

    init {
        load()
    }

    fun retry() = load()

    fun selectSort(sort: TopicSort) = updateState { it.copy(comments = it.comments.withSort(sort)) }

    fun react(itemId: Int, value: Int?) = reactions.react(itemId, value)

    private fun load() = launchSection(
        key = COMMENTS_SECTION,
        failures = failures,
        source = { loadOnce { commentFloorsOf(repository.getEpisodeComments(episodeId)) } },
        get = { it.comments.floors },
    ) { copy(comments = comments.copy(floors = it)) }
}

private const val COMMENTS_SECTION = "comments"
