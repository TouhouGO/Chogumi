package io.github.touhougo.chogumi.core.reader

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.common.bbcode.QuotablePost
import io.github.touhougo.chogumi.core.common.bbcode.QuoteHeader
import io.github.touhougo.chogumi.core.common.bbcode.resolveQuoteTargets
import io.github.touhougo.chogumi.core.common.bbcode.splitLeadingQuote
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommentCollectionBo
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.OwnFloorActions
import io.github.touhougo.chogumi.core.model.ReplyStyle
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.floorReportTargetOf
import io.github.touhougo.chogumi.core.model.ownFloorActionsOf
import io.github.touhougo.chogumi.core.model.reactionsOf
import io.github.touhougo.chogumi.core.model.replyStyleOf
import io.github.touhougo.chogumi.core.model.userDisplayNameOf
import io.github.touhougo.chogumi.core.reader.reactionpoll.ReactionPollPlugin
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.LocalFriends
import io.github.touhougo.chogumi.core.ui.common.ReaderTypography
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.CollectionTypeBadge
import io.github.touhougo.chogumi.core.ui.composables.CommentFloorSkeleton
import io.github.touhougo.chogumi.core.ui.composables.FloorGeometry
import io.github.touhougo.chogumi.core.ui.composables.FriendMark
import io.github.touhougo.chogumi.core.ui.composables.RATING_STAR_COUNT
import io.github.touhougo.chogumi.core.ui.composables.TextPill
import io.github.touhougo.chogumi.core.ui.composables.othersRatingStars
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion

/**
 * 通用社区楼层组件：话题楼层、条目吐槽、角色 / 人物吐槽、日志与目录留言共用，**全仓只有这一个评论组件**；
 * 话题 #1 楼与日志正文也用它画成「帖子正文」（[asPost]）。
 *
 * 版式（设计决定 not in M3）：
 * 1. 头部一行：头像（主楼 32、楼中楼 24）+ 昵称、楼主徽标、收藏状态角标（吐槽）... 楼层号；主楼在昵称下另起一行时间与星级，
 *    楼中楼把时间并进同一行。签名**不在楼层里**，点头像看用户卡片；
 * 2. 正文在头部下方**通栏**（与头像左缘对齐），头像不独占一整列左边距；字号行高取 [ReaderTypography]；
 * 3. 正文开头的引用拆成引用条，能反查到被引楼时可点（[onQuoteClick]）；楼层正文超过 6 行折叠成「展开全文」；
 * 4. 已有贴贴画成一排 chip（点自己贴过的取消、点别的换成它，计数与选中都有动画）；没有贴贴就不占行；
 * 5. **没有动作行**：点楼层 = 回复（在 App 里的编辑器写，能不能回复、分不分两层见 [replyStyleOf]），长按楼层 = 弹出这层楼的菜单
 *    （分组，组间是 M3 菜单的 gap）：贴贴两行铺开（能贴哪些表情只看 [reactionsOf]）→ 复制、网页（[BgmWeb.floor]）、报告
 *    （[floorReportTargetOf]）→ 别人的楼是加好友、绝交，自己的楼是编辑、删除（给不给只看 [ownFloorActionsOf]）。
 *    没有回复去处的楼点了不响应；
 * 6. 楼中楼挂在正文下方，左侧 2dp `outlineVariant` 细线，默认露 2 条（引用跳转落在折叠的楼上时自动展开）；
 * 7. 已删除的楼收成一行。
 *
 * @param floorNumber 楼层号（如 `#6`），楼中楼按 `#6-1` 派生；空串表示不显示楼层号（吐槽、帖子正文）。
 * @param opCreatorId 楼主的用户 id；楼主的楼挂「楼主」徽标。null 表示没有楼主概念（吐槽箱）。
 * @param subjectType 吐槽的收藏状态角标用它选动词（看过 / 读过…）。
 * @param quoteTargets 引用反查结果（楼 id → 被引楼 id），由 [commentQuoteTargets] 对整页楼层算一次。
 * @param onQuoteClick 点了可反查的引用条：参数是被引楼的 id，调用方负责滚过去并设 [flashingId]。
 * @param flashingId 正在闪烁的楼 id（引用跳转的落点）。传读取函数而不是值：闪烁开始与结束只让落点那一层楼重组，屏上别的楼不动。
 * @param onAvatarClick 点了头像：参数是这层楼的 id。用户卡片由屏幕层持有（[UserCardState]、[CommentUserCard]），不在楼层这一行里：
 *   行滚出列表或重建后还是骨架时，行里的状态会丢。
 * @param onReactionUsers 长按了一颗贴贴（或贴贴投票的一个选项）：参数是这层楼的 id 与表情。名单面板同样由屏幕层持有
 *   （[ReactionUsersState]、[CommentReactionUsers]）。
 * @param onUserClick 打开某个用户的主页（参数是 username）：正文里的 `[user]` 链接走它。
 * @param asPost 画成帖子正文（话题 #1、日志）：不折叠，正文用 [ReaderTypography.article]。
 * @param surface 楼层所在的评论区：决定点楼层回复去哪、长按能贴哪些表情；null 时两个手势都不响应。
 * @param onReact 贴贴：楼 id + 表情（null = 取消）。null 时贴贴只读。
 * @param onReply 回复（[replyStyleOf] 非 null 的评论区）：参数是点中的楼要回复谁（[replyTargetOf]）。null 时点楼层不响应。
 * @param onEdit 改自己的楼：参数是改哪层、怎么改（[editTargetOf]）。null 时菜单里没有「编辑」。
 * @param onDelete 删除自己的楼：参数是楼 id（确认由调用方做）。null 时菜单里没有「删除」。
 * @param onModerate 对别人的楼：报告、加好友、绝交（[io.github.touhougo.chogumi.core.ui.moderation.ModerationController.perform]，
 *   报告面板与绝交确认由页面画）。null 时菜单里没有这三项。
 */
@Composable
fun CommentThreadItem(
    thread: CommentThreadBo,
    floorNumber: String,
    onAvatarClick: (commentId: Int) -> Unit,
    onReactionUsers: (commentId: Int, value: Int) -> Unit,
    modifier: Modifier = Modifier,
    onUserClick: ((String) -> Unit)? = null,
    opCreatorId: Int? = null,
    subjectType: SubjectType = SubjectType.UNKNOWN,
    quoteTargets: Map<Int, Int> = emptyMap(),
    onQuoteClick: ((Int) -> Unit)? = null,
    flashingId: () -> Int? = { null },
    asPost: Boolean = false,
    surface: CommentSurface? = null,
    onReact: Gated<(itemId: Int, value: Int?) -> Unit>? = null,
    onReply: Gated<(ReplyTarget) -> Unit>? = null,
    onEdit: Gated<(EditTarget) -> Unit>? = null,
    onDelete: Gated<(postId: Int) -> Unit>? = null,
    onModerate: Gated<(ModerationAction) -> Unit>? = null,
) {
    val context = FloorContext(
        opCreatorId = opCreatorId,
        subjectType = subjectType,
        quoteTargets = quoteTargets,
        flashingId = flashingId,
        asPost = asPost,
        surface = surface,
        reactionPalette = remember(surface) { surface?.let(::reactionsOf).orEmpty() },
        onReact = onReact?.action,
        anchorId = thread.anchor.id,
        onReply = onReply?.action,
        onEdit = onEdit?.action,
        onDelete = onDelete?.action,
        onModerate = onModerate?.action,
        onQuoteClick = onQuoteClick,
        onUserClick = onUserClick,
        onAvatarClick = onAvatarClick,
        onReactionUsers = onReactionUsers,
    )

    CommentFloor(
        comment = thread.anchor,
        floorNumber = floorNumber,
        isReply = false,
        repliesBelow = thread.replies.size,
        context = context,
        modifier = modifier,
    ) {
        if (thread.replies.isNotEmpty()) {
            ReplyThread(replies = thread.replies, floorNumber = floorNumber, context = context)
        }
    }
}

/**
 * 一页楼层的引用反查（[resolveQuoteTargets] 的唯一接线）：按楼层顺序（主楼后紧跟它的楼中楼）摊平后算一次。
 */
fun commentQuoteTargets(threads: List<CommentThreadBo>): Map<Int, Int> = resolveQuoteTargets(
    threads.flatMap { thread ->
        (listOf(thread.anchor) + thread.replies).map { item ->
            QuotablePost(id = item.id, threadId = thread.anchor.id, author = userDisplayNameOf(item.creatorUsername, item.creatorNickname, item.creatorId), content = item.content)
        }
    },
)

/** 楼层间分割线（原型 `.divider`）：1dp `outlineVariant` 60% 不透明、左右缩进 16——设计决定（not in M3，原型值）。 */
@Composable
fun CommentFloorDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = ChogumiDimens.ScreenPadding)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = FLOOR_DIVIDER_ALPHA)),
    )
}

/** 几层楼的占位（楼间同样画分割线）：评论区还没到时画它，帖子页整页骨架的楼层部分也是它。 */
@Composable
fun CommentFloorsSkeleton(modifier: Modifier = Modifier) {
    Column(modifier) {
        repeat(FLOORS_SKELETON_COUNT) { index ->
            if (index > 0) CommentFloorDivider()
            CommentFloorSkeleton()
        }
    }
}

private const val FLOORS_SKELETON_COUNT = 3

/** 一串楼共享的上下文：同一串里主楼与楼中楼的判定口径一致。 */
private class FloorContext(
    val opCreatorId: Int?,
    val subjectType: SubjectType,
    val quoteTargets: Map<Int, Int>,
    val flashingId: () -> Int?,
    val asPost: Boolean,
    val surface: CommentSurface?,
    /** 这里能贴的表情（[reactionsOf]）；空 = 没有贴贴。 */
    val reactionPalette: List<Int>,
    val onReact: ((Int, Int?) -> Unit)?,
    /** 这一串的主楼 id：楼中楼的回复挂在它下面。 */
    val anchorId: Int,
    val onReply: ((ReplyTarget) -> Unit)?,
    val onEdit: ((EditTarget) -> Unit)?,
    val onDelete: ((Int) -> Unit)?,
    val onModerate: ((ModerationAction) -> Unit)?,
    val onQuoteClick: ((Int) -> Unit)?,
    val onUserClick: ((String) -> Unit)?,
    val onAvatarClick: (Int) -> Unit,
    val onReactionUsers: (commentId: Int, value: Int) -> Unit,
)

/** 楼中楼树枝连接线宽度（2dp 实体线） */
private val ReplyTreeLineWidth = 2.dp
private val HeaderGap = 6.dp
private val ThreadTopGap = 4.dp
private val ThreadStartPadding = 12.dp
private val ThreadLineInset = 4.dp
private const val VISIBLE_REPLIES = 2
private const val FOLDED_BODY_LINES = 6
private const val FLASH_ALPHA = 0.14f
private const val FLOOR_DIVIDER_ALPHA = 0.6f

/**
 * 一层楼：头部（头像 + 昵称行）→ 通栏正文 → 贴贴 → [below]（楼中楼）。
 * 点楼层回复、长按开菜单（见 [bodyGestures]）；正文里的链接、图片、引用条、「展开全文」与头像自己消费点击，不会误触回复。
 *
 * @param repliesBelow 挂在这层楼下的楼中楼条数（楼中楼自己是 0）：楼下有回复的楼不能再改（[ownFloorActionsOf]）。
 */
/** 跳转闪烁的底色：熄着时是同一色相的全透明，渐变不经过半透明的黑（`Color.Transparent` 会让淡入淡出发灰）。 */
internal fun floorFlashColor(primary: Color, flashing: Boolean): Color =
    primary.copy(alpha = if (flashing) FLASH_ALPHA else 0f)

@Composable
private fun CommentFloor(
    comment: CommentItemBo,
    floorNumber: String,
    isReply: Boolean,
    repliesBelow: Int,
    context: FloorContext,
    modifier: Modifier = Modifier,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val flashing by remember(comment.id, context.flashingId) { derivedStateOf { context.flashingId() == comment.id } }
    val flashColor by animateColorAsState(
        targetValue = floorFlashColor(MaterialTheme.colorScheme.primary, flashing = flashing),
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "floorFlash",
    )
    val floorModifier = modifier
        .fillMaxWidth()
        .background(flashColor)

    if (comment.state == CommentState.DELETED) {
        Column(floorModifier.padding(if (isReply) FloorGeometry.ReplyPadding else FloorGeometry.Padding)) {
            DeletedFloorRow(floorNumber)
            below()
        }
        return
    }

    val reply: (() -> Unit)? = context.surface?.let(::replyStyleOf)?.let { style ->
        context.onReply?.let { open ->
            { open(replyTargetOf(comment, floorNumber, context.anchorId, context.asPost && !isReply, style == ReplyStyle.THREADED)) }
        }
    }
    val react = context.onReact?.takeIf { context.reactionPalette.isNotEmpty() }
    // 给这一楼贴贴（null = 取消）：贴贴条、长按菜单与正文里的投票卡共用
    val vote = remember(comment.id, react) { react?.let { send -> { value: Int? -> send(comment.id, value) } } }
    val isPost = context.asPost && !isReply
    val own = context.surface?.let { ownFloorActionsOf(it, comment, repliesBelow, isPost) } ?: OwnFloorActions.None
    val edit = context.onEdit?.takeIf { own.edit }?.let { open -> { open(editTargetOf(comment, floorNumber)) } }
    val delete = context.onDelete?.takeIf { own.delete }?.let { remove -> { remove(comment.id) } }
    val menu = floorMenuActions(comment, isPost, context, edit, delete)
    val picker = rememberReactionPickerState()
    val interaction = remember { MutableInteractionSource() }

    Box(
        floorModifier
            .reactionPickerAnchor(picker)
            .bodyGestures(
                interaction = interaction,
                onTap = reply,
                onLongPress = if (menu != null) picker::openAt else null,
                tapLabel = stringResource(R.string.reply),
                longPressLabel = stringResource(R.string.floor_menu),
            ),
    ) {
        Column(Modifier.padding(if (isReply) FloorGeometry.ReplyPadding else FloorGeometry.Padding)) {
            FloorHeader(comment, floorNumber, isReply, context)
            // 长按贴贴、长按投票选项都打开这层楼的贴贴名单
            val showUsers = remember(comment.id, context.onReactionUsers) { { value: Int -> context.onReactionUsers(comment.id, value) } }
            // 正文里的贴贴投票画成投票卡，票就是这楼的贴贴；这里没有贴贴（[reactionPalette] 为空）时投票块照常是列表
            val pollPlugin = remember(context.reactionPalette, comment.reactions, vote, showUsers) {
                context.reactionPalette.takeIf { it.isNotEmpty() }?.let { ReactionPollPlugin(it, comment.reactions, vote, showUsers) }
            }
            CompositionLocalProvider(LocalBBCodeBlockPlugin provides pollPlugin) {
                FloorBody(comment, context, isReply)
            }
            // 帖子正文是主内容、空间也够：给一个看得见的「添加贴贴」（网页同位置也有贴贴按钮）；
            // 楼层仍只靠长按，不占垂直空间。
            val postReact = react != null && context.asPost && !isReply
            if (comment.reactions.isNotEmpty() || postReact) {
                ReactionBar(
                    reactions = comment.reactions,
                    palette = context.reactionPalette,
                    onReact = vote,
                    onShowUsers = showUsers,
                    onAdd = if (postReact) picker::openOn else null,
                    modifier = Modifier.padding(top = FloorGeometry.ReactionsTopGap),
                )
            }
            below()
        }
        if (menu != null) {
            ReactionPicker(
                state = picker,
                palette = context.reactionPalette,
                reactions = comment.reactions,
                onPick = vote,
                actionGroups = menu,
            )
        }
    }
}

/**
 * 一层楼长按菜单里贴贴之下的两组（null = 这层楼没有长按菜单：不在评论区里，[FloorContext.surface] 为 null）：
 * - 这层楼的内容：复制（[rememberBodyCopier]）、网页（[BgmWeb.floor]，网页上没有这层楼的地址时不出）、报告（[floorReportTargetOf]）；
 * - 自己的楼：编辑、删除；别人的楼：加为好友或解除好友（按当前账号的好友名单 [LocalFriends] 二选一，与用户卡片的关注状态按钮同一份；
 *   解除先弹确认，见 [ModerationAction.Unfriend]）、绝交（页面先弹确认，见 [ModerationAction.Block]）。
 * 报告、加好友、解除好友、绝交只对别人的楼，且页面接了 [FloorContext.onModerate] 才有。项名两到四个字，菜单宽度由贴贴两行定下（280dp），不会被某一项撑宽。
 */
@Composable
private fun floorMenuActions(
    comment: CommentItemBo,
    isPost: Boolean,
    context: FloorContext,
    edit: (() -> Unit)?,
    delete: (() -> Unit)?,
): List<List<FloorMenuAction>>? {
    val surface = context.surface ?: return null
    val copy = rememberBodyCopier()
    val browser = LocalUriHandler.current
    val moderate = context.onModerate?.takeIf { !comment.isOwn && comment.creatorUsername.isNotBlank() }
    val web = BgmWeb.floor(surface, comment.id, isPost)
    val report = moderate?.let { floorReportTargetOf(surface, comment.id, isPost) }
    val name = userDisplayNameOf(comment.creatorUsername, comment.creatorNickname, comment.creatorId)
    val content = listOfNotNull(
        FloorMenuAction(R.string.copy, R.drawable.content_copy_24) { copy(comment.content) },
        web?.let { url -> FloorMenuAction(R.string.page_more_web, R.drawable.open_in_new_24) { browser.openUri(url) } },
        report?.let { target -> FloorMenuAction(R.string.report, R.drawable.flag_24) { moderate(ModerationAction.Report(target, name)) } },
    )
    val person = listOfNotNull(
        edit?.let { FloorMenuAction(R.string.floor_edit, R.drawable.edit_24, it) },
        delete?.let { FloorMenuAction(R.string.delete, R.drawable.delete_24, it) },
        moderate?.let { act ->
            // 关注状态与用户卡片同一份名单：已是好友就给「解除好友」（页面先弹确认），否则「加为好友」
            if (LocalFriends.current.includes(comment.creatorId)) {
                FloorMenuAction(R.string.unfriend, R.drawable.person_remove_24) { act(ModerationAction.Unfriend(comment.creatorUsername)) }
            } else {
                FloorMenuAction(R.string.add_friend, R.drawable.person_add_24) { act(ModerationAction.AddFriend(comment.creatorUsername)) }
            }
        },
        moderate?.let { act -> FloorMenuAction(R.string.block, R.drawable.block_24) { act(ModerationAction.Block(comment.creatorUsername, name)) } },
    )
    return listOf(content, person)
}

/**
 * 头部：头像 + 昵称、楼主、好友、收藏状态角标 ... 楼层号。
 * 主楼的时间与星级在昵称下另起一行（与 32 的头像等高）；楼中楼头像 24，时间并进昵称行，省一行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FloorHeader(comment: CommentItemBo, floorNumber: String, isReply: Boolean, context: FloorContext) {
    val timeText = relativeTimeText(comment.createdAt)
    val avatarSize = if (isReply) FloorGeometry.ReplyAvatarSize else FloorGeometry.AvatarSize
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircleAvatar(comment.creatorAvatar, userDisplayNameOf(comment.creatorUsername, comment.creatorNickname, comment.creatorId), avatarSize, onClick = { context.onAvatarClick(comment.id) })
        Spacer(Modifier.width(FloorGeometry.AvatarGap))
        Column(Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(HeaderGap),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = userDisplayNameOf(comment.creatorUsername, comment.creatorNickname, comment.creatorId),
                    style = ReaderTypography.author,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (context.opCreatorId != null && comment.creatorId == context.opCreatorId) {
                    OpBadge()
                }
                FriendMark(comment.creatorId)
                // 发言人的状态是别人的，不用状态色（[CollectionTypeBadge]）
                comment.collection?.let { CollectionTypeBadge(it.type, context.subjectType, tinted = false, labeled = true) }
                if (isReply) FloorTime(timeText)
            }
            if (!isReply) {
                Row(horizontalArrangement = Arrangement.spacedBy(HeaderGap), verticalAlignment = Alignment.CenterVertically) {
                    FloorTime(timeText)
                    comment.collection?.let { CollectionStars(it) }
                }
            }
        }
        if (floorNumber.isNotEmpty()) {
            Text(
                text = floorNumber,
                style = ReaderTypography.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = HeaderGap),
            )
        }
    }
}

@Composable
private fun FloorTime(text: String) {
    if (text.isBlank()) return
    Text(text = text, style = ReaderTypography.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/** 楼主徽标（原型 `.badge.op`）：[TextPill] 的 `primary` / `onPrimary` 一档。 */
@Composable
private fun OpBadge() {
    TextPill(stringResource(R.string.comment_op_badge), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
}

/** 吐槽的星级行（原型 `.stars-s`）：公共评分关掉或没打分时整行不画，判定见 [othersRatingStars]。 */
@Composable
private fun CollectionStars(collection: CommentCollectionBo) {
    val filled = othersRatingStars(collection.rate, LocalAppFeatures.current) ?: return
    val description = stringResource(R.string.rating_description, collection.rate)
    Row(Modifier.padding(top = 2.dp).clearAndSetSemantics { contentDescription = description }) {
        repeat(RATING_STAR_COUNT) { index ->
            Icon(
                painter = painterResource(if (index < filled) R.drawable.star_filled_24 else R.drawable.star_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/**
 * 正文：开头的引用画成引用条，其后按 BBCode 渲染。帖子正文（[FloorContext.asPost] 的主楼）整篇展开、用
 * [ReaderTypography.article]；楼层正文用 [ReaderTypography.comment]，超过 6 行折叠。
 */
@Composable
private fun FloorBody(comment: CommentItemBo, context: FloorContext, isReply: Boolean) {
    val split = remember(comment.content) { splitLeadingQuote(comment.content) }
    val topGap = if (isReply) FloorGeometry.ReplyBodyTopGap else FloorGeometry.BodyTopGap
    split.quote?.let { quote ->
        val target = context.quoteTargets[comment.id]
        QuoteBar(
            quote = quote,
            onClick = target?.let { id -> context.onQuoteClick?.let { jump -> { jump(id) } } },
            modifier = Modifier.padding(top = topGap),
        )
    }
    if (split.body.isBlank()) return
    val bodyModifier = Modifier.padding(top = if (split.quote != null) 6.dp else topGap)
    val ast = remember(split.body) { BBCodeParser.parse(split.body) }

    if (context.asPost && !isReply) {
        BBCodeText(ast = ast, style = ReaderTypography.article, onUserClick = context.onUserClick, modifier = bodyModifier)
        return
    }
    var expanded by rememberSaveable(comment.id) { mutableStateOf(false) }
    FoldableText(
        ast = ast,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        collapsedLines = FOLDED_BODY_LINES,
        expandLabel = R.string.comment_expand_full,
        collapseLabel = R.string.comment_collapse,
        style = ReaderTypography.comment,
        modifier = bodyModifier,
        onUserClick = context.onUserClick,
    )
}

/** 引用条（原型 `.quote`）：[quoteBar]、`surfaceContainer` 底、圆角 8；昵称加粗，摘要最多 2 行。 */
@Composable
internal fun QuoteBar(quote: QuoteHeader, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val separator = stringResource(R.string.comment_quote_separator)
    val authorColor = MaterialTheme.colorScheme.onSurface
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = authorColor)) { append(quote.author) }
            append(separator)
            append(quote.excerpt)
        },
        style = ReaderTypography.quote,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .quoteBar(MaterialTheme.colorScheme.outlineVariant)
            .padding(end = 10.dp, top = 6.dp, bottom = 6.dp),
    )
}

/** 楼中楼：左侧细线 + 子楼，默认露 [VISIBLE_REPLIES] 条。子楼之间没有分割线（原型截图 21）。 */
@Composable
private fun ReplyThread(replies: List<CommentItemBo>, floorNumber: String, context: FloorContext) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hiddenCount = replies.size - VISIBLE_REPLIES
    // 引用跳转落在折叠起来的楼中楼上：展开，否则闪烁的是一条看不见的楼。
    val flashingId by rememberUpdatedState(context.flashingId)
    LaunchedEffect(replies) {
        snapshotFlow { flashingId() }.collect { id ->
            if (id != null && replies.drop(VISIBLE_REPLIES).any { it.id == id }) expanded = true
        }
    }
    val visibleReplies = if (expanded || hiddenCount <= 0) replies else replies.take(VISIBLE_REPLIES)
    // 左侧竖线画在列上（x=0，宽 2dp），线与列同高：正文是「超出才折叠」的 [FoldableText]（SubcomposeLayout），
    // 外层不能查固有尺寸（docs/dev/guide.md §2.3）。
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .padding(top = ThreadTopGap)
            .drawBehind {
                val lineWidth = ReplyTreeLineWidth.toPx()
                val inset = ThreadLineInset.toPx()
                drawRoundRect(
                    color = lineColor,
                    topLeft = Offset(0f, inset),
                    size = Size(lineWidth, size.height - inset * 2),
                    cornerRadius = CornerRadius(lineWidth / 2f),
                )
            }
            // 收起时不回弹：animateContentSize 按动画途中的高度裁剪，缩过头会裁掉底下的「展开」按钮
            .animateContentSize(animationSpec = LocalAppMotion.current.scheme.fastSpatialSpec())
            .padding(start = ThreadStartPadding),
    ) {
        visibleReplies.forEachIndexed { replyIndex, reply ->
            CommentFloor(
                comment = reply,
                floorNumber = if (floorNumber.isEmpty()) "" else "$floorNumber-${replyIndex + 1}",
                isReply = true,
                repliesBelow = 0,
                context = context,
            )
        }
        if (hiddenCount > 0) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(vertical = 6.dp)) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.comment_collapse)
                    } else {
                        pluralStringResource(R.plurals.comment_expand_replies, hiddenCount, hiddenCount)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    painter = painterResource(if (expanded) R.drawable.expand_less_24 else R.drawable.expand_more_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 已删除的楼收成一行（原型 `.deleted`）：图标 16 + 「#6 该回复不可见」，`onSurfaceVariant`。 */
@Composable
private fun DeletedFloorRow(floorNumber: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.visibility_off_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        val deleted = stringResource(R.string.comment_deleted)
        Text(
            text = if (floorNumber.isEmpty()) deleted else "$floorNumber $deleted",
            style = ReaderTypography.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
