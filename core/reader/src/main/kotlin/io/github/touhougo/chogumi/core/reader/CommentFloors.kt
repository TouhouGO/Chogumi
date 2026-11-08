package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import io.github.touhougo.chogumi.core.ui.theme.AppMotion
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CommentSurface
import io.github.touhougo.chogumi.core.model.FloorReading
import io.github.touhougo.chogumi.core.model.TopicFloor
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.ListEndLine
import io.github.touhougo.chogumi.core.ui.composables.ListSortBar
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 讨论版话题页的阅读骨架（原型截图 19–22、42），日志、目录的留言照搬：眉题 → 吸顶排序行 → 楼层 →「已经到底了」。

/** 引用跳转落点闪烁的时长（设计决定，not in M3）。 */
internal const val FLASH_MILLIS = 1_200L

/**
 * 日志的关联作品眉题（原型 `.ovl`）：20×26 小封面圆角 4 + 作品名 + ›，点了去作品；一篇日志每部关联作品一个。
 * 封面尺寸与圆角是原型值（设计决定，not in M3）。话题与单集讨论的上级不画眉题，在顶栏标题（[subjectTitleLink]）。
 */
@Composable
fun SubjectEyebrow(
    cover: String?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PressArea(onClick = onClick, shape = MaterialTheme.shapes.extraSmall, modifier = modifier) {
        EyebrowFrame {
            SubjectPoster(cover, Modifier.size(EyebrowCoverSize), shape = EyebrowCoverShape)
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                painter = painterResource(R.drawable.chevron_right_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 眉题的占位（骨架用）：同一个外框、同尺寸的小封面，作品名画一段 `labelLarge` 字条。字条宽度是设计决定（not in M3）。 */
@Composable
fun SubjectEyebrowSkeleton(modifier: Modifier = Modifier) {
    EyebrowFrame(modifier) {
        SkeletonBox(Modifier.size(EyebrowCoverSize), shape = EyebrowCoverShape)
        SkeletonText(MaterialTheme.typography.labelLarge, Modifier.width(EYEBROW_TITLE_SKELETON_WIDTH))
    }
}

/** 眉题的外框：上下 4、右 8 的点按留白，子项间距 6。真实眉题与占位共用。 */
@Composable
private fun EyebrowFrame(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.padding(top = 4.dp, bottom = 4.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private val EyebrowCoverSize = DpSize(20.dp, 26.dp)
private val EyebrowCoverShape = ShapeDefaults.ExtraSmall
private val EYEBROW_TITLE_SKELETON_WIDTH = 120.dp

/**
 * 楼层的排序行：[ListSortBar] + 「只看楼主」chip（楼主未知时不出）。一层回复都没有时两个 chip 都不出，只留标题。
 * 左侧是「评论区」标题而不是「N 条回复 / 留言」：回复数在列表页与正文头部已有，这里写区块名。
 */
@Composable
fun CommentSortBar(
    reading: FloorReading,
    onSelectSort: (TopicSort) -> Unit,
    onToggleOpOnly: () -> Unit,
    containerColor: () -> Color,
    modifier: Modifier = Modifier,
) {
    ListSortBar(
        heading = stringResource(R.string.comment_section_title),
        sort = reading.sort,
        options = if (reading.replies.isEmpty()) emptyList() else TopicSort.entries,
        label = { stringResource(it.labelRes()) },
        onSelect = onSelectSort,
        modifier = modifier,
        containerColor = containerColor,
    ) {
        if (reading.opCreatorId != null && reading.replies.isNotEmpty()) {
            SelectableChip(
                label = stringResource(R.string.topic_op_only),
                selected = reading.opOnly,
                unselectedLeadingIcon = R.drawable.person_24,
                onClick = onToggleOpOnly,
            )
        }
    }
}

/** 排序 → 文案（全仓唯一）。 */
private fun TopicSort.labelRes(): Int = when (this) {
    TopicSort.FLOOR -> R.string.topic_sort_floor
    TopicSort.HOT -> R.string.topic_sort_hot
    TopicSort.LATEST -> R.string.topic_sort_latest
}

/**
 * 引用跳转：点了可反查的引用条，滚到被引楼并让它闪一下。
 * 楼层下标（[FloorReading.floors]）→ `LazyColumn` 项下标：有帖子正文时第 0 层在 [postItem]，
 * 回复从 [firstReplyItem] 起；没有帖子正文时全部楼层从 [firstReplyItem] 起。两个值由调用页的列表结构决定。
 */
@Stable
class FloorJump internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val motion: AppMotion,
    private val firstReplyItem: Int,
    private val postItem: Int?,
) {
    /** 正在闪烁的楼 id（引用跳转的落点）：每次跳转后亮 [FLASH_MILLIS]，到点清空。 */
    var flashingId by mutableStateOf<Int?>(null)
        private set

    private var flashTimer: Job? = null

    /** 滚到 [postId] 那层楼（主楼或楼中楼）并让它闪一下；它不在 [reading] 显示的楼层里（被「只看楼主」筛掉）时不动。 */
    fun jumpToPost(reading: FloorReading, postId: Int) {
        reading.floorIndexByPost[postId]?.let { jumpTo(it, postId) }
    }

    internal fun jumpTo(floorIndex: Int, targetId: Int) {
        val item = when {
            postItem == null -> firstReplyItem + floorIndex
            floorIndex == 0 -> postItem
            else -> firstReplyItem + floorIndex - 1
        }
        scope.launch { motion.scrollToItem(listState, item) }
        // 闪烁的时长跟着这一次跳转走，不挂在 id 上：亮着时再点同一楼的引用，id 没变，
        // 按 id 计时的话第二次跳转不会重新计时，刚滚到就熄了。
        flashTimer?.cancel()
        flashingId = targetId
        flashTimer = scope.launch {
            delay(FLASH_MILLIS)
            flashingId = null
        }
    }
}

@Composable
fun rememberFloorJump(listState: LazyListState, firstReplyItem: Int, postItem: Int? = null): FloorJump {
    val scope = rememberCoroutineScope()
    val motion = LocalAppMotion.current
    return remember(listState, firstReplyItem, postItem, motion) { FloorJump(listState, scope, motion, firstReplyItem, postItem) }
}

/**
 * 进页就落到 [postId] 那层楼（通知、带楼层锚点的站内链接点进来）：楼层里第一次有它时经 [jump] 滚过去并闪一下，只这一次。
 * 落过没有随页面保存：从子页返回、重进组合都不再跳，之后换排序、点引用照常。它不在楼层里（已删除、被筛掉）时不动。
 * 在楼层列表已经组合出来的地方调用（列表还是骨架时 [reading] 里也没有它）。
 */
@Composable
fun FloorFocusOnArrival(jump: FloorJump, reading: FloorReading, postId: Int?) {
    var landed by rememberSaveable { mutableStateOf(false) }
    if (postId == null || landed || postId !in reading.floorIndexByPost) return
    LaunchedEffect(Unit) {
        jump.jumpToPost(reading, postId)
        landed = true
    }
}

/**
 * 帖子正文（[FloorReading.post]，话题 #1 楼）作为一项进列表：画在标题下方、排序行之上，整篇展开、不显示楼层号
 * （[CommentThreadItem] 的 `asPost`）。没有正文时不加项。
 */
fun LazyListScope.commentPostItem(
    reading: FloorReading,
    jump: FloorJump,
    surface: CommentSurface,
    onUserClick: (String) -> Unit,
    onAvatarClick: (commentId: Int) -> Unit,
    onReact: Gated<(itemId: Int, value: Int?) -> Unit>? = null,
    onReply: Gated<(ReplyTarget) -> Unit>? = null,
    onEdit: Gated<(EditTarget) -> Unit>? = null,
    onDelete: Gated<(postId: Int) -> Unit>? = null,
    onModerate: Gated<(ModerationAction) -> Unit>? = null,
) {
    val post = reading.post ?: return
    item(key = "post") { ReadingFloor(post, reading, jump, surface, onUserClick, onAvatarClick, onReact, onReply, onEdit, onDelete, onModerate, asPost = true) }
}

/**
 * 回复楼层逐条进列表（大帖几千楼也只组合可见的几层）：楼间分割线 + [CommentThreadItem] + 末尾「已经到底了」。
 * 一层回复都没有时只画 [emptyText]。楼主挂「楼主」，引用条可点时经 [jump] 滚到被引楼。
 * 点楼层回复、长按贴贴按 [surface] 响应；[onReact] 为 null 时贴贴只读，[onReply] 是 App 里回复的入口，
 * [onEdit] / [onDelete] 是长按自己的楼时菜单里的编辑、删除，[onModerate] 是长按别人的楼时的报告、加好友、绝交。
 */
fun LazyListScope.commentFloorItems(
    reading: FloorReading,
    @StringRes emptyText: Int,
    jump: FloorJump,
    surface: CommentSurface,
    onUserClick: (String) -> Unit,
    onAvatarClick: (commentId: Int) -> Unit,
    onReact: Gated<(itemId: Int, value: Int?) -> Unit>? = null,
    onReply: Gated<(ReplyTarget) -> Unit>? = null,
    onEdit: Gated<(EditTarget) -> Unit>? = null,
    onDelete: Gated<(postId: Int) -> Unit>? = null,
    onModerate: Gated<(ModerationAction) -> Unit>? = null,
) {
    if (reading.replies.isEmpty()) {
        item(key = "floors-empty") { EmptyLine(stringResource(emptyText)) }
        return
    }
    itemsIndexed(reading.replies, key = { _, floor -> floor.thread.anchor.id }) { index, floor ->
        if (index > 0) CommentFloorDivider()
        ReadingFloor(floor, reading, jump, surface, onUserClick, onAvatarClick, onReact, onReply, onEdit, onDelete, onModerate, asPost = false)
    }
    item(key = "floors-end") { ListEndLine(stringResource(R.string.community_list_end)) }
}

/** 帖子正文与回复楼层共用的一层：楼层号、楼主、引用跳转、闪烁、贴贴、回复、编辑删除、报告与好友绝交的接线只此一份。 */
@Composable
private fun ReadingFloor(
    floor: TopicFloor,
    reading: FloorReading,
    jump: FloorJump,
    surface: CommentSurface,
    onUserClick: (String) -> Unit,
    onAvatarClick: (commentId: Int) -> Unit,
    onReact: Gated<(itemId: Int, value: Int?) -> Unit>?,
    onReply: Gated<(ReplyTarget) -> Unit>?,
    onEdit: Gated<(EditTarget) -> Unit>?,
    onDelete: Gated<(postId: Int) -> Unit>?,
    onModerate: Gated<(ModerationAction) -> Unit>?,
    asPost: Boolean,
) {
    CommentThreadItem(
        thread = floor.thread,
        floorNumber = if (asPost) "" else "#${floor.number}",
        onAvatarClick = onAvatarClick,
        onUserClick = onUserClick,
        opCreatorId = reading.opCreatorId,
        quoteTargets = reading.quoteTargets,
        onQuoteClick = { targetId -> jump.jumpToPost(reading, targetId) },
        flashingId = { jump.flashingId },
        asPost = asPost,
        surface = surface,
        onReact = onReact,
        onReply = onReply,
        onEdit = onEdit,
        onDelete = onDelete,
        onModerate = onModerate,
    )
}
