package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.touhougo.chogumi.core.common.bbcode.BBCodeParser
import io.github.touhougo.chogumi.core.common.bbcode.excerptOf
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineSegmentBo
import io.github.touhougo.chogumi.core.model.TimelineTargetBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.model.reactionsShownOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.ReaderTypography
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberLinkOpener
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.FriendMark
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.PriorityTextLine
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.StackedAvatars
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.metaGapWidth
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import io.github.touhougo.chogumi.core.ui.composables.friendMarksEnabled

/*
 * 动态行（原型 `row()`）：动态页、社区 tab 与个人「动态」tab 共用。
 *
 * 版式只由 [presentationOf] 的 shape 决定；每行是分段列表的一项（[SegmentedItem]），
 * 所在组的首尾圆角由调用方给的 index / count 决定。
 *
 * 行内不放任何按钮：回复数、贴贴数有才显示成一行小字；点作品 / 吐槽行打开详情面板，
 * 点日志 / 目录 / 人物行去对应页，长按自己的动态删除。
 */

/** 骨架与真实行比对几何用的测试锚点。 */
const val TimelineGroupHeaderTag = "timeline_group_header"
const val TimelineCoverTag = "timeline_cover"

/** 原型 `.trow .cover`：48×64（本项目设计决定，not in M3），比例即海报比例。 */
private val CoverWidth = 48.dp

/** 原型 `.strip .cover`：56×75。 */
private val StripCoverWidth = 56.dp

/** 标题卡图标块、人物头像：48dp（原型 `.trow .ava` 48px）。 */
private val LeadingBlockSize = 48.dp

/** 动词胶囊上下内边距：labelMedium 行高 16 + 2 × 2 = 原型 `.vpill` 的行高 20。 */
private val VerbPillVerticalPadding = 2.dp

/** 作者头头像 40dp（原型 `.tgroup .who`）。 */
private val GroupAvatarSize = 40.dp

/** 作者头名字与「好友」小牌的间距：同楼层头部名字后各个小牌的间距 6dp。 */
private val NameMarkGap = 6.dp

/** 加好友的头像叠放：32dp（原型 `.stack`；压叠与描环见 [StackedAvatars]）。 */
private val StackAvatarSize = 32.dp

/**
 * 一组连续同作者动态的头：40dp 头像、名字、「时间」「N 条」两段用 MetaText 连成一行（原型 `.tgroup .who`）。
 * 点头像或名字去作者主页。[markFriend] 为 true 时名字旁按 [FriendMark] 画「好友」小牌；放不下时姓名先占整行、
 * 小牌下一行。人人都是好友的好友动态传 false。
 */
@Composable
fun TimelineGroupHeader(
    user: UserBo?,
    uid: Int,
    createdAt: Long,
    count: Int,
    markFriend: Boolean,
    onUser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = relativeTimeText(createdAt)
    PressArea(onClick = onUser, shape = MaterialTheme.shapes.small, modifier = modifier) {
        TimelineGroupHeaderLayout(
            modifier = Modifier,
            avatar = { CircleAvatar(user?.avatarUrl, description = null, size = GroupAvatarSize) },
            name = {
                TimelineNameFriendLine(
                    markFriend = markFriend,
                    name = {
                        Text(
                            displayNameOf(user, uid),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    friend = { FriendMark(uid) },
                )
            },
            subline = {
                MetaText(
                    if (count > 1) listOf(time, pluralStringResource(R.plurals.timeline_group_count, count, count)) else listOf(time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

/** 作者头的骨架：与 [TimelineGroupHeader] 共用同一份布局；[markFriend] 与真实页的功能开关同源。 */
@Composable
fun TimelineGroupHeaderSkeleton(markFriend: Boolean, modifier: Modifier = Modifier) {
    val authorSample = stringResource(R.string.timeline_author_skeleton_sample)
    TimelineGroupHeaderLayout(
        modifier = modifier,
        avatar = { SkeletonBox(Modifier.size(GroupAvatarSize), shape = CircleShape) },
        name = {
            TimelineNameFriendLine(
                markFriend = markFriend,
                name = {
                    SkeletonText(
                        MaterialTheme.typography.titleSmall,
                        widthFraction = 0.65f,
                        sample = authorSample,
                        fillWidth = false,
                        maxLines = 1,
                    )
                },
                friend = {
                    SkeletonText(
                        MaterialTheme.typography.labelSmallEmphasized,
                        widthFraction = 0.7f,
                        sample = stringResource(R.string.friend_mark),
                        fillWidth = false,
                        maxLines = 1,
                    )
                },
            )
        },
        subline = { SkeletonText(MaterialTheme.typography.bodySmall, widthFraction = 0.2f) },
    )
}

/** 作者姓名与好友牌的唯一排法：真实行与骨架都从这里取得断行和基线。 */
@Composable
private fun TimelineNameFriendLine(
    markFriend: Boolean,
    name: @Composable () -> Unit,
    friend: @Composable () -> Unit,
) {
    val showFriend = markFriend && friendMarksEnabled()
    PriorityTextLine(
        modifier = Modifier.fillMaxWidth(),
        primary = name,
        trailing = friend.takeIf { showFriend },
        gap = NameMarkGap,
    )
}

@Composable
private fun TimelineGroupHeaderLayout(
    modifier: Modifier,
    avatar: @Composable () -> Unit,
    name: @Composable () -> Unit,
    subline: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TimelineGroupHeaderTag)
            .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        avatar()
        Column(Modifier.weight(1f)) {
            name()
            subline()
        }
    }
}

/**
 * 一条动态的一行。
 *
 * 行里不写时间：动态页由作者头带时间，个人「动态」tab 由日期头带日子。
 *
 * @param index 在所在分段组里的位置，与 [count] 一起决定首尾圆角
 * @param inDetail 在详情面板里：评论写全文，不显示回复数、贴贴数（下面就是贴贴一行与楼层）
 * @param onOpen 作品 / 多部收藏 / 吐槽行的点击（动态页里是打开详情面板）；null 时这几种行不可点
 * @param onDelete 自己的动态长按删除；null 表示不是自己的
 */
@Composable
fun TimelineRow(
    group: TimelineDisplayGroup,
    index: Int,
    count: Int,
    onOpen: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onSubject: (Int) -> Unit,
    modifier: Modifier = Modifier,
    inDetail: Boolean = false,
) {
    val item = group.anchor
    val presentation = presentationOf(item)
    val openLink = rememberLinkOpener()
    val target = item.targets.firstOrNull()
    // 点击去向随版式：标题卡 / 人物去对应页，单行与加好友不可点（名字自己可点）。
    val onClick: (() -> Unit)? = when (presentation.shape) {
        TimelineShape.SUBJECT, TimelineShape.STRIP, TimelineShape.TEXT -> onOpen
        TimelineShape.TITLE_CARD, TimelineShape.MONO -> target?.let { { openLink(it.url) } }
        TimelineShape.LINE, TimelineShape.AVATARS -> null
    }
    val leading: (@Composable () -> Unit)? = when (presentation.shape) {
        TimelineShape.SUBJECT -> target?.subject?.let { subject ->
            { SubjectPoster(subject.coverUrl, Modifier.width(CoverWidth).testTag(TimelineCoverTag)) }
        }
        TimelineShape.TITLE_CARD -> {
            { TitleCardLeading(imageUrl = target?.imageUrl, iconRes = presentation.iconRes ?: R.drawable.forum_24) }
        }
        TimelineShape.MONO -> {
            { CircleAvatar(url = target?.imageUrl, description = null, size = LeadingBlockSize) }
        }
        TimelineShape.LINE -> {
            {
                Icon(
                    painterResource(presentation.iconRes ?: R.drawable.edit_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TimelineShape.STRIP, TimelineShape.TEXT, TimelineShape.AVATARS -> null
    }
    TimelineSegment(
        index = index,
        count = count,
        onClick = onClick,
        onLongClick = onDelete,
        leading = leading,
        modifier = modifier,
    ) {
        // 回复与贴贴接在动作行之后（[TimelineSignals]）；详情面板里不画，那里下面就是贴贴与楼层
        val signals = !inDetail
        when (presentation.shape) {
            TimelineShape.SUBJECT -> SubjectBody(group, presentation, target, inDetail)
            TimelineShape.STRIP -> StripBody(item, presentation, signals, onSubject)
            TimelineShape.TEXT -> {
                TimelineBody(item.text, style = ReaderTypography.comment)
                if (signals) SignalsLine(item)
            }
            TimelineShape.LINE -> LineBody(item, presentation)
            TimelineShape.TITLE_CARD -> TitleCardBody(item, presentation, target, signals)
            TimelineShape.MONO -> {
                VerbLabelLine(item, presentation, signals)
                Text(target?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
            }
            TimelineShape.AVATARS -> {
                AvatarsBody(item, presentation, openLink)
                if (signals) SignalsLine(item)
            }
        }
    }
}

/** 作品行在加载中的样子：与真实作品行共用 [TimelineSegment] 与 [SubjectLines] 的字阶。 */
@Composable
fun TimelineRowSkeleton(index: Int, count: Int, modifier: Modifier = Modifier) {
    TimelineSegment(
        index = index,
        count = count,
        onClick = null,
        onLongClick = null,
        leading = { SubjectPosterSkeletonBox(Modifier.width(CoverWidth).testTag(TimelineCoverTag)) },
        modifier = modifier,
    ) {
        SubjectLines(
            title = { SkeletonText(MaterialTheme.typography.titleMedium, widthFraction = 0.6f) },
            // 与 [VerbPill] 同字阶同上下内边距，骨架行与只有动词的真实行等高
            verbLine = {
                SkeletonText(
                    MaterialTheme.typography.labelMediumEmphasized,
                    modifier = Modifier.padding(vertical = VerbPillVerticalPadding),
                    widthFraction = 0.3f,
                )
            },
        )
    }
}

/**
 * 分段列表里的一项：行底、圆角、内边距都交给 [SegmentedItem]，有前导块时内容顶端对齐、行内间距 4dp
 * （原型 `.trow .tx{gap:4px}`）。
 */
@Composable
private fun TimelineSegment(
    index: Int,
    count: Int,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    leading: (@Composable () -> Unit)?,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        onLongClick = onLongClick,
        leadingContent = leading,
        // 有封面 / 图标块时文字顶齐它（原型 `.trow` align-items:flex-start）；
        // 没有时单行内容在列表项最小高度里居中，否则底下空一截（真机发现）
        verticalAlignment = if (leading != null) Alignment.Top else ListItemDefaults.verticalAlignment(),
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun SubjectLines(title: @Composable () -> Unit, verbLine: @Composable () -> Unit) {
    title()
    verbLine()
}

@Composable
private fun ColumnScope.SubjectBody(
    group: TimelineDisplayGroup,
    presentation: TimelinePresentation,
    target: TimelineTargetBo?,
    inDetail: Boolean,
) {
    val item = group.anchor
    val subject = target?.subject
    val episode = target?.segments?.firstNotNullOfOrNull { it as? TimelineSegmentBo.EpisodeProgress }
    val batch = target?.segments?.firstNotNullOfOrNull { it as? TimelineSegmentBo.ProgressBatch }
    val rating = target?.rating
    SubjectLines(
        title = {
            Text(
                subject?.let { displayNameOf(it) } ?: target?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        verbLine = {
            // 一行到底：放不下时截短可变的那一段（集名、批量进度），动词、集号、评分与后面的回复贴贴保持完整
            Row(horizontalArrangement = Arrangement.spacedBy(VerbLineSpacing), verticalAlignment = Alignment.CenterVertically) {
                VerbPill(presentation, Modifier.alignByBaseline())
                when {
                    batch != null -> Text(
                        timelineSegmentText(batch),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                    )
                    episode != null -> {
                        Text(
                            episodeRangeText(group.episodeStart, group.episodeEnd ?: episode.sort),
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            modifier = Modifier.alignByBaseline(),
                        )
                        if (!group.isMerged && episode.name.isNotBlank()) {
                            Text(
                                episode.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                            )
                        }
                    }
                    rating != null -> RatingStars(rating)
                }
                if (!inDetail) TimelineSignals(item)
            }
        },
    )
    val comment = target?.description.orEmpty().ifBlank { item.text }
    if (comment.isNotBlank()) {
        QuoteText(comment, maxLines = if (inDetail) Int.MAX_VALUE else 2, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun episodeRangeText(start: Double?, end: Double): String =
    if (start != null && start != end) {
        stringResource(R.string.timeline_episode_range, formatEpisodeSort(start), formatEpisodeSort(end))
    } else {
        stringResource(R.string.timeline_episode_single, formatEpisodeSort(end))
    }

@Composable
private fun ColumnScope.StripBody(item: TimelineItemBo, presentation: TimelinePresentation, signals: Boolean, onSubject: (Int) -> Unit) {
    val subjects = item.targets.mapNotNull { it.subject }
    Row(horizontalArrangement = Arrangement.spacedBy(VerbLineSpacing), verticalAlignment = Alignment.CenterVertically) {
        VerbPill(presentation, Modifier.alignByBaseline())
        presentation.batchCountRes?.let {
            Text(pluralStringResource(it, subjects.size, subjects.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.alignByBaseline())
        }
        if (signals) TimelineSignals(item)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        subjects.forEach { subject ->
            SubjectPoster(
                subject.coverUrl,
                modifier = Modifier.width(StripCoverWidth),
                description = displayNameOf(subject),
                onClick = { onSubject(subject.id) },
            )
        }
    }
}

@Composable
private fun ColumnScope.LineBody(item: TimelineItemBo, presentation: TimelinePresentation) {
    val verb = stringResource(presentation.verbRes)
    Text(
        if (item.text.isBlank()) verb else stringResource(R.string.timeline_line_with_text, verb, item.text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ColumnScope.TitleCardBody(item: TimelineItemBo, presentation: TimelinePresentation, target: TimelineTargetBo?, signals: Boolean) {
    VerbLabelLine(item, presentation, signals)
    Text(
        target?.title.orEmpty(),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    // 没有统计片段时是日志摘要：服务端截断的 BBCode，只画纯文字
    val description = target?.description.orEmpty()
    val subline = target?.segments?.firstOrNull()?.let { timelineSegmentText(it) } ?: remember(description) { excerptOf(description) }
    if (subline.isNotBlank()) {
        Text(
            subline,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 标题卡的 48dp 前置块：`tertiaryContainer`、圆角 12（原型 `border-radius:12px` = M3 shape medium）的图标块；
 * 目标自带图片（小组图标等）时图片盖在上面——图标块同时是加载中与取图失败时的样子。
 */
@Composable
private fun TitleCardLeading(imageUrl: String?, iconRes: Int) {
    Box(
        modifier = Modifier
            .size(LeadingBlockSize)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** 加好友：「加了好友 A、B」名字可点去主页，下面头像叠放。 */
@Composable
private fun ColumnScope.AvatarsBody(item: TimelineItemBo, presentation: TimelinePresentation, openLink: (String) -> Unit) {
    val verb = stringResource(presentation.verbRes)
    val separator = stringResource(R.string.list_separator)
    val nameStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    val text: AnnotatedString = buildAnnotatedString {
        append(verb)
        append(' ')
        item.targets.forEachIndexed { i, friend ->
            if (i > 0) append(separator)
            withLink(LinkAnnotation.Clickable(friend.url, TextLinkStyles(nameStyle)) { openLink(friend.url) }) {
                append(friend.title)
            }
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
    StackedAvatars(
        avatars = item.targets.map { it.imageUrl },
        size = StackAvatarSize,
        ringColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * 动词胶囊（原型 `.vpill`）：12sp 粗体（labelMedium emphasized，tokens.md）、行高 20、左右 8、全圆角。
 * 配色档只认 [TimelinePresentation.tone]。
 */
@Composable
private fun VerbPill(presentation: TimelinePresentation, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (presentation.tone) {
        VerbTone.DONE -> colors.primaryContainer to colors.onPrimaryContainer
        VerbTone.MUTED -> colors.surfaceContainerHighest to colors.onSurfaceVariant
        VerbTone.NORMAL -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Text(
        stringResource(presentation.verbRes),
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = content,
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 8.dp, vertical = VerbPillVerticalPadding),
    )
}

/** 评分（原型 `.stars`）：14dp 实心星 `tertiary` + 12sp 粗体分数；动态里自己的评分是 1–10 的整数。 */
@Composable
private fun RatingStars(rating: Double) {
    val label = rating.toInt().toString()
    val description = stringResource(R.string.rating_description, rating.toInt())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
        },
    ) {
        Icon(
            painterResource(R.drawable.star_filled_24),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMediumEmphasized,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** 动作行里各件之间（动词标签、集号、集名）与几种贴贴之间的间距。我的设计决定（not in M3）。 */
private val VerbLineSpacing = 6.dp

/**
 * 回复与贴贴（只读）：接在一条动态的动作行之后，与前面隔一段 [metaGapWidth] 空白、顺着往右排——不推到行尾留出一段空白，
 * 也不另起一行（有引用框时卡片已经比封面高）。没有单行动作行的版式（纯文字吐槽、加好友）用 [SignalsLine]。
 *
 * 每种贴贴画它自己的表情和数（与楼层同一套表情画法 [ReactionGlyph]），按数从多到少最多 [MaxShownReactions] 种；
 * 回复数排最后。读屏念成「贴贴 N」「回复 N」。
 *
 * 这一段永远是一行：放在动作行最后，拿到的是动作行剩下的宽度；不够时从后往前少画几种贴贴（[ReactionsThatFit]），
 * 回复数保持完整；回复数连同前面的空白也放不下时先不留空白，自己也放不下时报告夹进约束的宽度，从起点画起。
 *
 * [afterRowItem]：前面是同一个 `Row` 里的动作行（`Row` 已经隔了 [VerbLineSpacing]），这里补足到 [metaGapWidth]。
 */
@Composable
internal fun TimelineSignals(item: TimelineItemBo, afterRowItem: Boolean = true) {
    if (!hasSignals(item)) return
    val reactions = reactionsShownOf(item)
    // 两段之间、以及与前面动作行之间的空白：与 MetaText 的段间空白同一个宽度，随字号一起变
    val signalGap = metaGapWidth(MetaNumberStyle)
    Layout(
        content = {
            if (reactions.isNotEmpty()) {
                val description = stringResource(R.string.timeline_reaction_like_count, reactions.sumOf { it.count })
                ReactionsThatFit(
                    reactions.take(MaxShownReactions),
                    Modifier.layoutId(SignalPart.REACTIONS).clearAndSetSemantics { contentDescription = description },
                )
            }
            if (item.replies > 0) {
                MetaCount(
                    R.drawable.forum_24,
                    item.replies,
                    stringResource(R.string.replies_count, item.replies),
                    Modifier.layoutId(SignalPart.REPLIES),
                )
            }
        },
    ) { measurables, constraints ->
        val gap = signalGap.roundToPx()
        val lead = if (afterRowItem) (signalGap - VerbLineSpacing).coerceAtLeast(0.dp).roundToPx() else 0
        val loose = Constraints(maxHeight = constraints.maxHeight)
        fun part(id: SignalPart) = measurables.firstOrNull { it.layoutId == id }
        val replies = part(SignalPart.REPLIES)?.measure(loose)
        // 贴贴拿剩下的宽度：前面的空白，再加回复数与它前面的一段空白
        val reactionsWidth = (constraints.maxWidth - lead - (replies?.let { it.width + gap } ?: 0)).coerceAtLeast(0)
        val shownReactions = part(SignalPart.REACTIONS)
            ?.measure(Constraints(maxWidth = reactionsWidth, maxHeight = constraints.maxHeight))
            ?.takeIf { it.width > 0 }
        val shown = listOfNotNull(shownReactions, replies)
        // 回复数连同前面的空白也放不下时，空白先不留
        val start = if (shown.isEmpty() || lead + shown.sumOf { it.width } > constraints.maxWidth) 0 else lead
        val width = if (shown.isEmpty()) 0 else start + shown.sumOf { it.width } + gap * (shown.size - 1)
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(constraints.constrainWidth(width), constraints.constrainHeight(height)) {
            var x = start
            shown.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
        }
    }
}

/** [TimelineSignals] 的两段。 */
private enum class SignalPart { REACTIONS, REPLIES }

/** 按数从多到少排好的几种贴贴，宽度放不下时只画放得下的前几种；一种也放不下时宽度为 0。 */
@Composable
private fun ReactionsThatFit(reactions: List<CommentReactionBo>, modifier: Modifier) {
    Layout(
        modifier = modifier,
        content = {
            reactions.forEach { reaction ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReactionGlyph(reactionDisplayOf(reaction.value), MetaIconSize)
                    MetaNumber(reaction.count)
                }
            }
        },
    ) { measurables, constraints ->
        val gap = VerbLineSpacing.roundToPx()
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(loose) }
        var width = 0
        val fitting = placeables.takeWhile { placeable ->
            val next = if (width == 0) placeable.width else width + gap + placeable.width
            (next <= constraints.maxWidth).also { fits -> if (fits) width = next }
        }
        val height = fitting.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            fitting.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gap
            }
        }
    }
}

/** 有没有回复或贴贴可画：没有时连空行都不出，免得卡片底下多一道行距。 */
private fun hasSignals(item: TimelineItemBo): Boolean = item.replies > 0 || reactionsShownOf(item).isNotEmpty()

/** 没有单行动作行的版式：回复与贴贴在内容下面单独一行，开头不留空白。 */
@Composable
private fun SignalsLine(item: TimelineItemBo) {
    TimelineSignals(item, afterRowItem = false)
}

/** 日志 / 角色卡片的动作行：动词小字，后面接回复与贴贴。 */
@Composable
private fun VerbLabelLine(item: TimelineItemBo, presentation: TimelinePresentation, signals: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(VerbLineSpacing), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(presentation.verbRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (signals) TimelineSignals(item)
    }
}

@Composable
private fun MetaCount(iconRes: Int, count: Int, description: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(MetaIconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MetaNumber(count, Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun MetaNumber(count: Int, modifier: Modifier = Modifier) {
    Text(count.toString(), style = MetaNumberStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** 回复数与贴贴数的字阶，两段之间的空白（[metaGapWidth]）也按它量。 */
private val MetaNumberStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelMedium

/** 回复图标与贴贴表情的边长（原型 `.meta` 的 16dp 图标）。 */
private val MetaIconSize = 16.dp

/** 卡片上最多画几种贴贴：小屏一行放得下，其余的在详情面板里看（我的设计决定，not in M3）。 */
private const val MaxShownReactions = 3

/**
 * 时间线正文：**纯文本 + 行内表情**；带剧透标签（[mask]）的正文走 BBCode 渲染，剧透默认遮住、点一下揭开。
 *
 * 数据里没有 HTML（服务端 escape → unescape 成环，回复是用户原样输入），
 * 表情只以纯文本 `(bgmNN)` 出现，切分交给 [segmentTimelineBody]，渲染走 BBCode 的 [InlineFlow]（全仓一份）。
 */
@Composable
fun TimelineBody(body: String, modifier: Modifier = Modifier, style: TextStyle = ReaderTypography.compact) {
    // 带剧透标签（[mask]）的正文交给 BBCode 渲染：遮罩、点一下揭开都是它的行内处理，全仓只有这一份
    if (body.contains("[mask", ignoreCase = true)) {
        val ast = remember(body) { BBCodeParser.parse(body) }
        BBCodeText(ast = ast, style = style, modifier = modifier)
        return
    }
    val blocks = remember(body) {
        segmentTimelineBody(body).map { segment ->
            when (segment) {
                is BodySegment.Text -> Block.Inline(AnnotatedString(segment.value))
                is BodySegment.Smiley -> Block.Smiley(segment.code, segment.code)
            }
        }
    }
    InlineFlow(items = blocks, style = style, onUserClick = null, revealSpoilers = false, modifier = modifier)
}
