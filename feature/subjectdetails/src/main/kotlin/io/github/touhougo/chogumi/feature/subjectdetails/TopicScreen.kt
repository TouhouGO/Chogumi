package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.reader.CommentReactionUsers
import io.github.touhougo.chogumi.core.reader.rememberReactionUsersState
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.TopicParent
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.indexCategoryOf
import io.github.touhougo.chogumi.core.model.reactionsOf
import io.github.touhougo.chogumi.core.model.shownUnder
import io.github.touhougo.chogumi.core.reader.CommentSortBar
import io.github.touhougo.chogumi.core.reader.CommentUserCard
import io.github.touhougo.chogumi.core.reader.ComposerSheet
import io.github.touhougo.chogumi.core.reader.FloorFocusOnArrival
import io.github.touhougo.chogumi.core.reader.ReplyComposerSheet
import io.github.touhougo.chogumi.core.reader.ReplyFab
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.ThreadHead
import io.github.touhougo.chogumi.core.reader.ThreadPageSkeleton
import io.github.touhougo.chogumi.core.reader.commentFloorItems
import io.github.touhougo.chogumi.core.reader.commentPostItem
import io.github.touhougo.chogumi.core.reader.groupTitleLink
import io.github.touhougo.chogumi.core.reader.rememberFloorJump
import io.github.touhougo.chogumi.core.reader.rememberUserCardState
import io.github.touhougo.chogumi.core.reader.subjectTitleLink
import io.github.touhougo.chogumi.core.reader.title
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.IndexCollect
import io.github.touhougo.chogumi.core.ui.composables.PageLink
import io.github.touhougo.chogumi.core.ui.composables.PageMoreMenu
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import io.github.touhougo.chogumi.core.ui.composables.TitleLink
import io.github.touhougo.chogumi.core.ui.composables.rememberStickyHeaderEdge
import io.github.touhougo.chogumi.core.ui.composables.stickyHeaderFade
import io.github.touhougo.chogumi.core.ui.moderation.ModerationHost
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** 帖子页 `LazyColumn` 的项：标题（0）、帖子正文（1）、吸顶排序行（2），回复从 3 起。 */
private const val TOPIC_POST_ITEM = 1
private const val TOPIC_FIRST_REPLY_ITEM = 3

/**
 * 帖子页（原型截图 19–22、42，`topicScreen()`）：作品讨论版与小组的话题同一页。顶栏标题是话题的上级（[topicParent]），右上角 ⋮（[PageMoreMenu]）
 * 打开网页、分享这个话题；
 * 正文是标题 → 吸顶排序行 → 楼层 →「已经到底了」。右下「回复」FAB 回复楼主，点楼层回复那一层；都打开编辑器面板（[ComposerSheet]），未登录先去登录。
 * 发出后滚到新楼并让它闪一下。FAB 往下滚时收成图标，往回滚展开（M3 extended FAB）。
 * 长按自己的楼可以编辑（同一个编辑器面板，不要人机验证）、删除（先确认）。
 *
 * @param draftPostId 从「我的帖子」的草稿点进来时，那份草稿回复的楼（0 是话题本身）：进页就打开它接着写。
 * @param focusPostId 从通知、带楼层锚点的链接点进来时要看的那层楼：进页就落到它并闪一下（[FloorFocusOnArrival]）。
 */
@Composable
fun TopicScreen(
    type: TopicType,
    topicId: Int,
    draftPostId: Int?,
    focusPostId: Int?,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onGroupClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    val viewModel: TopicViewModel = koinViewModel { parametersOf(type, topicId, draftPostId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gate = rememberLoginGate(isLoggedIn, onLogin)
    val react = gate.guard(viewModel::react)
    val reply = gate.guard { target: ReplyTarget -> viewModel.composer.open(target) }
    val moderate = gate.guard(viewModel.moderation::perform)
    val listState = rememberLazyListState()
    val jump = rememberFloorJump(listState, firstReplyItem = TOPIC_FIRST_REPLY_ITEM, postItem = TOPIC_POST_ITEM)
    val snackbar = remember { SnackbarHostState() }
    val surface = CommentSurface.Topic(type, topicId)
    var deleting by rememberSaveable { mutableStateOf<Int?>(null) }
    val userCard = rememberUserCardState()
    val reactionUsers = rememberReactionUsersState()
    FailureNoticeEffect(viewModel.failures, snackbar)

    val topic = state.topic.valueOrNull()
    // 楼层与正文同一套骨架：绝交名单还没读到时整页仍是骨架，楼层按名单一次画出来（shownUnder）
    val pageState = sectionStateOf(state.topic.shownUnder(state.reading.blocked)) { false }
    val parent = topic?.parent?.let { topicParent(it, onSubjectClick, onGroupClick) }
    PageScaffold(
        // 上级没到时顶栏画占位；加载失败、没有上级时是通用页名
        title = parent?.first ?: stringResource(R.string.thread_page_title),
        titleLink = parent?.second,
        titleLoading = parent == null && pageState == SectionState.LOADING,
        onBack = onBack,
        actions = {
            PageMoreMenu(
                PageLink(topic?.title.orEmpty(), BgmWeb.topic(type, topicId)),
                IndexCollect(IndexTarget(indexCategoryOf(type), topicId), gate),
            )
        },
        floatingActionButton = {
            if (topic != null) ReplyFab { reply.action(ReplyTarget.Topic) }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // 整页一套骨架：话题与楼层是同一次请求，到了才淡入，不整屏转圈
            SkeletonCrossfade(loading = pageState == SectionState.LOADING, modifier = Modifier.fillMaxSize(), skeleton = { ThreadPageSkeleton(eyebrow = false) }) {
                when {
                    topic == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        RetryError(state.topic.failureOrNull() ?: Failure.Unknown, onRetry = viewModel::retry, Modifier.fillMaxWidth())
                    }
                    else -> {
                        FloorFocusOnArrival(jump, state.reading, focusPostId)
                        val sortEdge = rememberStickyHeaderEdge(listState)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().stickyHeaderFade(sortEdge),
                            contentPadding = PaddingValues(bottom = FabClearance),
                            overscrollEffect = sortEdge.overscrollEffect,
                        ) {
                            item(key = "head") { ThreadHead(title = topic.title, eyebrows = null) }
                            commentPostItem(
                                reading = state.reading,
                                jump = jump,
                                surface = surface,
                                onUserClick = onUserClick,
                                onAvatarClick = userCard::open,
                                onReactionUsers = reactionUsers::open,
                                onReact = react,
                                onReply = reply,
                                onEdit = gate.guard(viewModel::edit),
                                onDelete = gate.guard { id: Int -> deleting = id },
                                onModerate = moderate,
                            )
                            stickyHeader(key = "sort") {
                                CommentSortBar(
                                    reading = state.reading,
                                    onSelectSort = viewModel::selectSort,
                                    onToggleOpOnly = viewModel::toggleOpOnly,
                                    containerColor = sortEdge.containerColor,
                                )
                            }
                            commentFloorItems(
                                reading = state.reading,
                                emptyText = R.string.community_list_end,
                                jump = jump,
                                surface = surface,
                                onUserClick = onUserClick,
                                onAvatarClick = userCard::open,
                                onReactionUsers = reactionUsers::open,
                                onReact = react,
                                onReply = reply,
                                onEdit = gate.guard(viewModel::edit),
                                onDelete = gate.guard { id: Int -> deleting = id },
                                onModerate = moderate,
                            )
                        }
                    }
                }
            }
        }
    }

    CommentUserCard(userCard, state.reading.threads, onUserClick, moderate)
    CommentReactionUsers(reactionUsers, state.reading.threads, onUserClick)

    ReplyComposerSheet(state.composer, viewModel.composer, surface, gate) { postId -> jump.jumpToPost(viewModel.uiState.value.reading, postId) }
    ModerationHost(state.moderation, viewModel.moderation)

    state.editor.target?.let { target ->
        ComposerSheet(
            title = target.title(),
            sending = state.editor.sending,
            sent = state.editor.sent,
            failures = viewModel.editor.failures,
            onDismiss = viewModel.editor::close,
            onSend = gate.guard { draft: ComposerDraft, token: String -> viewModel.editor.send(draft, token) { jump.jumpToPost(viewModel.uiState.value.reading, target.postId) } },
            initialText = state.editor.text,
            onChange = viewModel.editor::edit,
            verify = false,
            quote = target.quote,
            pollPalette = reactionsOf(surface),
        )
    }

    deleting?.let { postId ->
        ConfirmDialog(
            target = postId,
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { id -> gate.run { viewModel.deletePost(id) } },
            onDismiss = { deleting = null },
            text = stringResource(R.string.floor_delete_confirm),
            destructive = true,
        )
    }
}

/**
 * 话题的上级在顶栏标题里的名字和去处：作品讨论版是作品名 + 小封面，点了回作品；小组话题是小组名 + 小组图标，点了去小组。
 */
private fun topicParent(parent: TopicParent, onSubjectClick: (Int) -> Unit, onGroupClick: (String) -> Unit): Pair<String, TitleLink> = when (parent) {
    is TopicParent.Subject -> displayNameOf(parent.subject) to subjectTitleLink(parent.subject.cover) { onSubjectClick(parent.subject.id) }
    is TopicParent.Group -> parent.group.title to groupTitleLink(parent.group.icon) { onGroupClick(parent.group.name) }
}
