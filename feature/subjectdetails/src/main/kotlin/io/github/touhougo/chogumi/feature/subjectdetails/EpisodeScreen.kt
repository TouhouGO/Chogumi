package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.EpisodeThreadBo
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.reader.ReplyFab
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.ThreadPageSkeleton
import io.github.touhougo.chogumi.core.reader.episodeLabel
import io.github.touhougo.chogumi.core.reader.subjectTitleLink
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.IndexCollect
import io.github.touhougo.chogumi.core.ui.composables.PageLink
import io.github.touhougo.chogumi.core.ui.composables.PageMoreMenu
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 单集讨论页（超展开的单集、站内 `/ep/{id}` 链接进来）：与帖子页同一套阅读骨架。顶栏标题是这一集的作品（小封面 + 作品名，
 * 点了回作品，[subjectTitleLink]），正文是集号、标题、播出日、简介与评论楼层，内容就是章节长按面板的那一份（[EpisodeThread]）。
 * 右上角 ⋮（[PageMoreMenu]）打开网页、分享这一集。右下「回复」FAB 写新的一条评论，点楼层回复那一层（与帖子页同一个编辑器面板），未登录先去登录。
 * [focusPostId] 是从通知、带楼层锚点的链接点进来时要看的那层评论：进页就落到它。
 */
@Composable
fun EpisodeScreen(
    episodeId: Int,
    focusPostId: Int?,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onUserClick: (String) -> Unit,
) {
    val viewModel: EpisodeViewModel = koinViewModel { parametersOf(episodeId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val loginGate = rememberLoginGate(isLoggedIn, onLogin)
    val section = sectionStateOf(state.head) { false }

    val head = state.head.valueOrNull()
    val subject = head?.subject
    val comments = episodeCommentsViewModel(episodeId)
    val listState = rememberLazyListState()
    PageScaffold(
        // 作品没到时顶栏画占位；加载失败、这一集不知道作品时写页名
        title = subject?.let { displayNameOf(it) } ?: stringResource(R.string.episode_page_title),
        titleLink = subject?.let { subjectTitleLink(it.cover) { onSubjectClick(it.id) } },
        titleLoading = subject == null && section == SectionState.LOADING,
        onBack = onBack,
        actions = {
            PageMoreMenu(
                PageLink(head?.let(::episodeShareTitle).orEmpty(), BgmWeb.episode(episodeId)),
                IndexCollect(IndexTarget(IndexCategory.EPISODE, episodeId), loginGate),
            )
        },
        floatingActionButton = {
            if (head != null) ReplyFab(loginGate.guard { comments.composer.open(ReplyTarget.Topic) }.action)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // 整页一套骨架（标题、正文、排序行、楼层），头部到了才淡入；楼层自己有骨架
            SkeletonCrossfade(loading = section == SectionState.LOADING, modifier = Modifier.fillMaxSize(), skeleton = { ThreadPageSkeleton(eyebrow = false) }) {
                if (head == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        RetryError(state.head.failureOrNull() ?: Failure.Unknown, onRetry = viewModel::retry, Modifier.fillMaxWidth())
                    }
                } else {
                    EpisodeThread(
                        episode = head.episode,
                        viewModel = comments,
                        listState = listState,
                        loginGate = loginGate,
                        onUserClick = onUserClick,
                        focusPostId = focusPostId,
                        replyInHead = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** 分享这一集时链接上面那一行：作品名（不知道时不写）+ 集号与集名（[episodeLabel]）。 */
private fun episodeShareTitle(head: EpisodeThreadBo): String {
    val episode = episodeLabel(head.episode.sort, displayNameOf(head.episode))
    return head.subject?.let { "${displayNameOf(it)} $episode" } ?: episode
}
