package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.reader.CollapsibleSynopsis
import io.github.touhougo.chogumi.core.reader.CommentReactionUsers
import io.github.touhougo.chogumi.core.reader.FloorFocusOnArrival
import io.github.touhougo.chogumi.core.reader.ReplyComposerSheet
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import io.github.touhougo.chogumi.core.reader.CommentUserCard
import io.github.touhougo.chogumi.core.reader.floorCommentsSection
import io.github.touhougo.chogumi.core.reader.rememberReactionUsersState
import io.github.touhougo.chogumi.core.reader.rememberUserCardState
import io.github.touhougo.chogumi.core.reader.rememberFloorJump
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LoginGate
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.episodeNumberLabel
import io.github.touhougo.chogumi.core.ui.composables.rememberStickyHeaderEdge
import io.github.touhougo.chogumi.core.ui.composables.stickyHeaderFade
import io.github.touhougo.chogumi.core.ui.moderation.ModerationHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 章节网格长按的单集面板（原型对照网页 `ep/{id}`）：集号 → 标题 / 原名 → 播出日 → 正文（简介，折叠 3 行）→ 评论区。
 * 不放「看到这一集」按钮——正片点格子、特别篇点格子都能标记，长按只用来看这一集。
 * 内容就是 [EpisodeThread]，与单集讨论页同一份；面板里没有 FAB，写评论的「回复」在头部右上角。
 * 放在调用方的 `ScrollableModalSheet` 里：点头像进用户主页不关面板，返回原样还在。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.EpisodeDetailSheet(episode: EpisodeBo, loginGate: LoginGate, onUserClick: (String) -> Unit) {
    // 吸顶排序行用面板底色，否则吸顶时是一条 surface 色带（与面板外壳同一个默认色）
    EpisodeThread(
        episode = episode,
        viewModel = episodeCommentsViewModel(episode.id),
        listState = rememberLazyListState(),
        loginGate = loginGate,
        onUserClick = onUserClick,
        focusPostId = null,
        replyInHead = true,
        barColor = BottomSheetDefaults.ContainerColor,
        modifier = Modifier.fillMaxWidth().weight(1f),
    )
}

/** 一集的评论区（[EpisodeCommentsViewModel]）：面板与单集讨论页（它的「回复」FAB 也要）按集取同一份。 */
@Composable
internal fun episodeCommentsViewModel(episodeId: Int): EpisodeCommentsViewModel =
    koinViewModel(key = "episode-comments-$episodeId") { parametersOf(episodeId) }

/**
 * 一集的阅读内容（单集面板与单集讨论页共用）：集号、标题、原名、播出日 → 正文（简介，与作品简介同一个
 * [CollapsibleSynopsis]）→ 评论区（与目录、日志留言同一个 [floorCommentsSection]，能贴贴，[CommentSurface.Episode]）。
 * 点楼层回复那一层，写新的一条在面板里是头部的「回复」（[replyInHead]），在讨论页是页面的「回复」FAB；
 * 都打开与讨论版同一个编辑器面板（[ReplyComposerSheet]），发出后落到新楼上。
 * 贴贴提示画在自己的 snackbar 上：放在面板里时，页面的 snackbar 在面板下面看不见。
 *
 * @param listState 评论区所在的列表：讨论页的「回复」FAB 随它的滚动收起展开，所以由调用方持有。
 * @param focusPostId 进来就要落到的那层评论（[FloorFocusOnArrival]）；面板里没有。
 * @param replyInHead 头部右上角画「回复」（面板里没有 FAB 的位置）。
 * @param barColor 吸顶排序行的固定底色（底部面板里是面板色）；null（单集讨论页）时吸住后随顶栏填色（[rememberStickyHeaderEdge]）。
 */
@Composable
internal fun EpisodeThread(
    episode: EpisodeBo,
    viewModel: EpisodeCommentsViewModel,
    listState: LazyListState,
    loginGate: LoginGate,
    onUserClick: (String) -> Unit,
    focusPostId: Int?,
    replyInHead: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val comments = state.comments
    val snackbar = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbar)
    var descExpanded by rememberSaveable(episode.id) { mutableStateOf(false) }
    val hasDesc = episode.desc.isNotBlank()
    // 第一层楼之前的列表项：头部 +（有正文时）正文 + 吸顶排序行
    val jump = rememberFloorJump(listState, firstReplyItem = if (hasDesc) 3 else 2)
    val surface = CommentSurface.Episode(episode.id)
    val reply = loginGate.guard { target: ReplyTarget -> viewModel.composer.open(target) }
    val moderate = loginGate.guard(viewModel.moderation::perform)
    val userCard = rememberUserCardState()
    val reactionUsers = rememberReactionUsersState()

    Box(modifier) {
        val sortEdge = rememberStickyHeaderEdge(listState, barColor)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().stickyHeaderFade(sortEdge),
            contentPadding = PaddingValues(bottom = if (replyInHead) 0.dp else FabClearance),
            overscrollEffect = sortEdge.overscrollEffect,
        ) {
            item(key = "episode-head") {
                EpisodeHead(episode, onReply = if (replyInHead) ({ reply.action(ReplyTarget.Topic) }) else null)
            }
            if (hasDesc) {
                item(key = "episode-desc") {
                    CollapsibleSynopsis(
                        text = episode.desc,
                        expanded = descExpanded,
                        onToggle = { descExpanded = !descExpanded },
                    )
                }
            }
            floorCommentsSection(
                comments = comments,
                surface = surface,
                jump = jump,
                emptyText = R.string.episode_comments_empty,
                onSelectSort = viewModel::selectSort,
                // 单集评论没有楼主（FloorComments 的楼主恒为 null），「只看楼主」不会出现
                onToggleOpOnly = {},
                onUserClick = onUserClick,
                onAvatarClick = userCard::open,
                onReactionUsers = reactionUsers::open,
                onRetry = viewModel::retry,
                onReact = loginGate.guard(viewModel::react),
                onReply = reply,
                onModerate = moderate,
                sortEdge = sortEdge,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    CommentUserCard(userCard, comments.reading.threads, onUserClick, moderate)
    CommentReactionUsers(reactionUsers, comments.reading.threads, onUserClick)
    FloorFocusOnArrival(jump, comments.reading, focusPostId)
    ReplyComposerSheet(state.composer, viewModel.composer, surface, loginGate) { postId ->
        jump.jumpToPost(viewModel.uiState.value.comments.reading, postId)
    }
    ModerationHost(state.moderation, viewModel.moderation)
}

/**
 * 头部：集号（正片「第 N 集」/ 特别篇类型）→ 标题 → 原名（与标题不同时）→ 播出日；给了 [onReply] 时右上角是「回复」
 * （写新的一条评论）。
 */
@Composable
private fun EpisodeHead(episode: EpisodeBo, onReply: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ChogumiDimens.ScreenPadding).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EpisodeHeadText(episode, Modifier.weight(1f))
        if (onReply != null) {
            FilledTonalButton(onClick = onReply) {
                Icon(painterResource(R.drawable.reply_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.reply))
            }
        }
    }
}

@Composable
private fun EpisodeHeadText(episode: EpisodeBo, modifier: Modifier) {
    val title = displayNameOf(episode)
    Column(modifier) {
        Text(
            text = episodeNumberLabel(episode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        if (episode.name.isNotBlank() && episode.name != title) {
            Text(text = episode.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            // 播出日缺失 / 不合法 → 「播出日未知」，不说「尚未播出」：网格对未知播出日是按已播出显示的
            text = if (episode.airdate.isBlank()) {
                stringResource(R.string.episode_detail_airdate_unknown)
            } else {
                stringResource(R.string.episode_detail_airdate, episode.airdate)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
