package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.reader.TimelineDisplayGroup
import io.github.touhougo.chogumi.core.reader.TimelineRow
import io.github.touhougo.chogumi.core.reader.TimelineRowSkeleton
import io.github.touhougo.chogumi.core.reader.mergeConsecutiveTimelineItems
import io.github.touhougo.chogumi.core.reader.presentationOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.GridSpacing
import io.github.touhougo.chogumi.core.ui.composables.LocalNow
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.formatPastDate
import io.github.touhougo.chogumi.core.ui.composables.loadMoreFooter

/**
 * 动态的显示分组：可见的动态合并连续进度后按天分组（唯一一份）。O(n) 的计算，由页面按动态列表 `remember`，
 * 不在 LazyGrid 的内容构建器里做：构建器随每次重组重跑，合并 / 分组因此和动态有没有变化无关地重算。
 */
internal fun timelineDaysOf(items: List<TimelineItemBo>): List<TimelineDayGroup<TimelineDisplayGroup>> =
    groupTimelineByDay(
        mergeConsecutiveTimelineItems(items.filter { presentationOf(it).visible }),
        timestamp = { it.anchor.createdAt },
    )

/**
 * 动态 tab：与动态页共用 core:ui 的 [TimelineRow]，按天分组成分段列表（原型 `renderProfileActivity`）。
 *
 * 主页顶栏已有大头像，所以不画作者头；日子写在日期头上，行里不再写时间（同一天的几行写同一个日子是重复）。
 * 一天拆成日期头一项、每行一项（[timelineDay]）：一天几十行时也只组合看得见的那几行。
 *
 * 点击出口：作品 / 吐槽行 → 详情面板；条目 → 条目详情；自己的动态长按 → 确认后删除。
 * 能不能删只看 [io.github.touhougo.chogumi.core.model.TimelineItemBo.isOwn]（取数时按当前账号判定，删除校验读同一个字段）。
 */
internal fun LazyGridScope.timelineItems(
    user: UserBo?,
    /** 看的是不是自己：空态说「还没有动态」还是「某某 还没有动态」。 */
    isSelf: Boolean,
    state: PagedList<TimelineItemBo>,
    /** [state] 的动态经 [timelineDaysOf] 算出的显示分组。 */
    days: List<TimelineDayGroup<TimelineDisplayGroup>>,
    endText: String,
    onLoadMore: () -> Unit,
    /** 列表为空时取失败的重试（整块错误）。 */
    onRetry: () -> Unit,
    onOpen: (TimelineItemBo) -> Unit,
    onDelete: (Long) -> Unit,
    onSubject: (Int) -> Unit,
) {
    if (user == null || (state.isLoading && state.items.isEmpty())) {
        timelineDay(
            key = "profile-timeline-skeleton-day",
            label = null,
            count = SKELETON_ROWS,
            rowKey = null,
            rowContentType = "profile-timeline-row-skeleton",
        ) { index, modifier ->
            TimelineRowSkeleton(index, SKELETON_ROWS, modifier = modifier.testTag(ProfileTimelineRowTag))
        }
        item(span = { GridItemSpan(maxLineSpan) }) { ProfileTimelineBottomSpacer() }
        return
    }
    state.error?.let { error ->
        item(span = { GridItemSpan(maxLineSpan) }) { RetryError(error, onRetry = onRetry, Modifier.fillMaxWidth().padding(bottom = GridSpacing)) }
        item(span = { GridItemSpan(maxLineSpan) }) { ProfileTimelineBottomSpacer() }
        return
    }

    days.forEach { dayGroup ->
        val rows = dayGroup.items
        timelineDay(
            key = "profile-timeline-day-${dayGroup.day}",
            label = { profileTimelineDayLabel(rows.first().anchor.createdAt) },
            count = rows.size,
            rowKey = { "profile-timeline-row-${rows[it].anchor.id}" },
            rowContentType = "profile-timeline-row",
        ) { index, modifier ->
            val group = rows[index]
            val item = group.anchor
            TimelineRow(
                group = group,
                index = index,
                count = rows.size,
                onOpen = { onOpen(item) },
                onDelete = if (item.isOwn) ({ onDelete(item.id) }) else null,
                onSubject = onSubject,
                modifier = modifier.testTag(ProfileTimelineRowTag),
            )
        }
    }
    if (days.isEmpty() && !state.isLoading && !state.hasMore) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(bottom = GridSpacing)) {
                EmptyLine(
                    if (isSelf) stringResource(R.string.profile_timeline_empty_title)
                    else stringResource(R.string.profile_timeline_empty_other, displayNameOf(user)),
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { ProfileTimelineBottomSpacer() }
        return
    }
    loadMoreFooter(
        loadingMore = state.isLoadingMore,
        hasMore = state.hasMore,
        error = state.loadMoreError,
        endText = endText,
        hasItems = days.isNotEmpty(),
        onLoadMore = onLoadMore,
    )
    item(span = { GridItemSpan(maxLineSpan) }) { ProfileTimelineBottomSpacer() }
}


/** 首屏骨架画几行（我的设计决定：约半屏，not in M3）。 */
private const val SKELETON_ROWS = 3


/** 个人页动态的 testTag：真实日期头 / 动态行与骨架共用，几何测试据此对位。 */
internal const val ProfileTimelineDayHeaderTag = "profile_timeline_day_header"

internal const val ProfileTimelineRowTag = "profile_timeline_row"


/** 日期头文案：今天 / 昨天 / 日子（全 App 统一的写法 [formatPastDate]：今年的不写年份）。 */
@Composable
private fun profileTimelineDayLabel(timestampMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val now = LocalNow.current.value
    return when (timelineDayLabelKind(timestampMillis, now)) {
        TimelineDayLabelKind.TODAY -> stringResource(R.string.day_offset_today)
        TimelineDayLabelKind.YESTERDAY -> stringResource(R.string.day_offset_yesterday)
        TimelineDayLabelKind.DATE -> formatPastDate(timestampMillis, now, locale)
    }
}


/**
 * 一天：日期头一项，后面这一天的分段行每行一项（行多的日子也只组合看得见的那几行）。
 *
 * 这一页的网格行距是 0（[profileGridRowSpacingOf]），间距由各项自带：日期头与第一行之间只要日期头自带的下边距，
 * 分段行之间只要 [ListItemDefaults.SegmentedGap]，一天的最后一行下面留一格 [GridSpacing]，接下一个日期头或页脚。
 * 骨架与真实内容走这同一份几何，[label] 为 null 时是加载中。
 *
 * @param row 第 index 行；`modifier` 带着这一行的上下间距，必须接到行的最外层
 */
private fun LazyGridScope.timelineDay(
    key: String,
    label: (@Composable () -> String)?,
    count: Int,
    rowKey: ((Int) -> Any)?,
    rowContentType: String,
    row: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "profile-timeline-day") {
        ProfileTimelineDayHeader(label?.invoke())
    }
    items(count = count, key = rowKey, span = { GridItemSpan(maxLineSpan) }, contentType = { rowContentType }) { index ->
        val top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap
        val bottom = if (index == count - 1) GridSpacing else 0.dp
        row(index, Modifier.padding(top = top, bottom = bottom))
    }
}


/**
 * 日期头（原型 `.daylbl`：label-large、onSurfaceVariant、后接一条 outlineVariant 细线）。
 * [label] 为 null 时是加载中，日期换成同字阶的色条。
 */
@Composable
private fun ProfileTimelineDayHeader(label: String?) {
    Row(
        modifier = Modifier
            .testTag(ProfileTimelineDayHeaderTag)
            .fillMaxWidth()
            // 原型 padding 16 20 8：上边减去网格项间距 12，左右减去网格内边距 16
            .padding(start = 4.dp, end = 4.dp, top = 16.dp - GridSpacing, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val style = MaterialTheme.typography.labelLarge
        if (label != null) {
            Text(text = label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            // 色条宽度是我的设计决定（not in M3），文字高度沿用真实日期头。
            SkeletonText(style = style, modifier = Modifier.width(64.dp))
        }
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}


@Composable
private fun ProfileTimelineBottomSpacer() {
    Spacer(
        modifier = Modifier.height(
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
    )
}
