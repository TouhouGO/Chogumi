package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.FloorReading
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction
import io.github.touhougo.chogumi.core.ui.common.withLast
import io.github.touhougo.chogumi.core.ui.composables.ScrollableModalSheet
import io.github.touhougo.chogumi.core.ui.composables.rememberStickyHeaderEdge
import io.github.touhougo.chogumi.core.ui.composables.stickyHeaderFade

/**
 * 动态详情面板（原型 `openReplies`）：作者头 + 这条动态 + 贴贴与「回复」+ 回复。
 * 动态页、「我的」与别人主页的「动态」tab 共用；逻辑在 [TimelineDetailActions]。
 *
 * - 贴贴在 App 里点（贴贴接口不需要人机验证），与楼层同一套（[ReactionBar]、[ReactionPicker]）：chip 点了贴上 / 换成它 /
 *   取消，「添加贴贴」弹出选择器；贴在哪、能贴哪些看 [TimelineItemBo.reactionTarget]，没有的只读列出已有的贴贴；
 * - 回复在 App 里写：贴贴那一排末尾的「回复」回复这条动态，点一层楼回复那一条（开头 @ 那个人，见 [replyTargetOf]）；
 *   都打开盖在面板上的编辑器（[ComposerSheet]，发送前人机验证），发出后楼层重新拉、落到新回复上；
 * - 回复与目录、日志的留言、单集评论同一个评论区（[floorCommentsSection]）：吸顶排序行、楼层占位、楼号、
 *   楼主、引用跳转、「已经到底了」。动态的回复没有贴贴接口，楼层不能贴。
 *
 * @param item 面板对应的动态，按 [TimelineDetailState.id] 从当前列表里取；取不到时只显示回复
 * @param composer 写回复的编辑器（[TimelineDetailActions.composer]）：面板里的失败提示、关面板、存草稿
 * @param onReply 开编辑器回复谁（[TimelineDetailActions.reply]）
 * @param onSendReply 发出编辑器里写好的回复（[TimelineDetailActions.send]），`land` 落到新回复上
 * @param onModerate 长按别人的回复时的报告、加好友、绝交，与用户卡片里的加好友、解除好友（页面的
 *   [io.github.touhougo.chogumi.core.ui.moderation.ModerationController.perform]）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineDetailSheet(
    item: TimelineItemBo?,
    state: TimelineDetailState,
    onDismiss: () -> Unit,
    onReact: Gated<(TimelineItemBo, Int?) -> Unit>,
    composer: ComposerActions<TimelineReplyTarget, Long>,
    onReply: Gated<(ReplyTarget) -> Unit>,
    onSendReply: Gated<(draft: ComposerDraft, token: String, land: (FloorReading, Int) -> Unit) -> Unit>,
    onModerate: Gated<(ModerationAction) -> Unit>,
    onRetry: () -> Unit,
    onSelectSort: (TopicSort) -> Unit,
    onToggleOpOnly: () -> Unit,
    onUser: (String) -> Unit,
    onSubject: (Int) -> Unit,
) {
    val surface = state.surface ?: return
    val listState = rememberLazyListState()
    val reactions = item?.let(state::shownReactions).orEmpty()
    // 第一层楼之前的列表项：（取得到动态时）作者头与动态、贴贴与「回复」那一行，再加吸顶排序行
    val leadingItems = if (item != null) 2 else 0
    val jump = rememberFloorJump(listState, firstReplyItem = leadingItems + 1)
    // 吸顶排序行用面板底色，否则吸顶时是一条 surface 色带
    val sheetColor = BottomSheetDefaults.ContainerColor
    ScrollableModalSheet(onDismiss = onDismiss, contentKey = state.id) {
        val userCard = rememberUserCardState()
        // 动态自己的贴贴名单只记停在哪个表情（面板只属于这一条动态）；楼层的按楼 id 记
        var itemReactionUsers by rememberSaveable { mutableStateOf<Int?>(null) }
        val floorReactionUsers = rememberReactionUsersState()
        val sortEdge = rememberStickyHeaderEdge(listState, sheetColor)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).stickyHeaderFade(sortEdge),
            overscrollEffect = sortEdge.overscrollEffect,
        ) {
            if (item != null) {
                item(key = "head") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        TimelineGroupHeader(
                            user = item.user,
                            uid = item.uid,
                            createdAt = item.createdAt,
                            count = 1,
                            markFriend = true,
                            onUser = { onUser(userKeyOf(item.user?.username, item.uid)) },
                        )
                        TimelineRow(
                            group = TimelineDisplayGroup(anchor = item, items = listOf(item)),
                            index = 0,
                            count = 1,
                            inDetail = true,
                            onOpen = item.targets.firstNotNullOfOrNull { it.subject }?.let { subject -> { onSubject(subject.id) } },
                            onDelete = null,
                            onSubject = onSubject,
                        )
                    }
                }
                item(key = "reactions") {
                    TimelineReactionRow(
                        item = item,
                        reactions = reactions,
                        onReact = onReact,
                        onReply = { onReply.action(ReplyTarget.Topic) },
                        onShowUsers = { value -> itemReactionUsers = value },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            floorCommentsSection(
                comments = state.comments,
                surface = surface,
                jump = jump,
                emptyText = R.string.timeline_no_replies,
                onSelectSort = onSelectSort,
                onToggleOpOnly = onToggleOpOnly,
                onUserClick = onUser,
                onAvatarClick = userCard::open,
                onReactionUsers = floorReactionUsers::open,
                onRetry = onRetry,
                onReply = onReply,
                onModerate = onModerate,
                sortEdge = sortEdge,
            )
        }
        CommentUserCard(userCard, state.comments.reading.threads, onUser, onModerate)
        CommentReactionUsers(floorReactionUsers, state.comments.reading.threads, onUser)
        itemReactionUsers?.let { value ->
            ReactionUsersSheet(reactions, value, onSelect = { itemReactionUsers = it }, onDismiss = { itemReactionUsers = null }, onUserClick = onUser)
        }
    }
    // 编辑器在详情面板之后登记，盖在它上面；收起后回到详情面板原处
    state.composer.target?.let { target ->
        ComposerSheet(
            title = target.reply.title(surface),
            sending = state.composer.sending,
            sent = state.composer.sent,
            failures = composer.failures,
            onDismiss = composer::close,
            onSend = onSendReply.withLast(jump::jumpToPost),
            initialText = state.composer.text,
            onChange = composer::edit,
            quote = target.reply.preview,
        )
    }
}

/**
 * 动态下面的一行：左边是贴贴，与楼层同一套——能贴（[TimelineItemBo.reactionTarget] 非 null）时 chip 可点、末尾「添加贴贴」
 * 弹出选择器，不能贴时只读列出 [reactions]；右端是「回复」这条动态（与「添加贴贴」同一种按钮）。
 *
 * @param reactions 要画的贴贴（[TimelineDetailState.shownReactions]：在途的已先换上）
 * @param onShowUsers 长按一颗贴贴：看谁贴了（参数是表情）
 */
@Composable
internal fun TimelineReactionRow(
    item: TimelineItemBo,
    reactions: List<CommentReactionBo>,
    onReact: Gated<(TimelineItemBo, Int?) -> Unit>,
    onReply: () -> Unit,
    onShowUsers: (value: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = item.reactionTarget?.palette.orEmpty()
    val react: ((Int?) -> Unit)? = item.reactionTarget?.let { { value: Int? -> onReact.action(item, value) } }
    val picker = rememberReactionPickerState()
    // 选择器按锚点左上角定位，与这一排叠在同一个 Box 里
    Box(modifier.reactionPickerAnchor(picker)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReactionBar(
                reactions = reactions,
                palette = palette,
                onReact = react,
                onShowUsers = onShowUsers,
                onAdd = react?.let { picker::openOn },
                modifier = Modifier.weight(1f),
            )
            ReactionRowButton(R.drawable.reply_24, R.string.reply) { onReply() }
        }
        if (react != null) ReactionPicker(picker, palette, reactions, onPick = react)
    }
}
