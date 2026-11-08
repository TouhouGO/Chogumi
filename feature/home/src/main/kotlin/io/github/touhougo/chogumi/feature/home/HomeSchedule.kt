package io.github.touhougo.chogumi.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.ScheduleDayGroup
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.ui.composables.CoverBadge
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeInset
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeTextPadding
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.dayOffsetLabel
import io.github.touhougo.chogumi.core.ui.composables.episodeProgressLabel
import io.github.touhougo.chogumi.core.ui.composables.formatMonthDay

/**
 * 追更时间表每天之间的横向间距。
 * 设计决定（非 M3）：原型 phase1.html 中每天右外边距为 16px（.ttday { padding-right: 16px }）。
 */
internal val ScheduleTimelineDaySpacing = 16.dp


/**
 * 追更时间表横向轴线的粗细。
 * 设计决定（非 M3）：原型 phase1.html 中贯穿轴线高度为 2px（.ttline::before { height: 2px }）。
 */
private val ScheduleTimelineAxisThickness = 2.dp


/**
 * 追更时间表圆点直径。
 * 设计决定（非 M3）：原型 phase1.html 中时间节点圆点为 10px（.ttline i { width: 10px; height: 10px }）。
 */
private val ScheduleTimelineDotSize = 10.dp


/**
 * 追更时间表圆点边框粗细。
 * 设计决定（非 M3）：原型 phase1.html 中非今日圆点内描边 2px（box-shadow: inset 0 0 0 2px var(--outline)）。
 */
private val ScheduleTimelineDotBorder = 2.dp


/**
 * 追更时间表海报之间的横向间距。
 * 设计决定（非 M3）：原型 phase1.html 中海报行间距为 8px（Row spacedBy 8dp）。
 */
private val ScheduleTimelinePosterSpacing = 8.dp


/**
 * 追更时间表海报宽度。
 * 设计决定（非 M3）：原型 phase1.html 中海报条目宽度为 100px（.ttitem { width: 100px }）。
 */
private val ScheduleTimelinePosterWidth = 100.dp


/**
 * 追更时间表的横滑行配置——真实时间表（[ScheduleTimeline]）与骨架共用的唯一出处：
 * 首尾内边距、上下内边距（天与天的间距在 [ScheduleDayColumn] 里）。骨架传 [userScrollEnabled] = false，其余完全一致。
 */
@Composable
internal fun ScheduleTimelineRow(
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}


/**
 * 追更时间表里「一天」的框架——真实内容与骨架共用的唯一出处：头部（天数在上、日期在下，大字号时随字长高）、
 * 轴线与圆点、海报行的结构与尺寸都在这里。[header] 画头部的字，[posters] 画这一天的海报。
 */
@Composable
internal fun ScheduleDayColumn(
    index: Int,
    isToday: Boolean,
    isLast: Boolean,
    header: @Composable () -> Unit,
    posters: @Composable RowScope.() -> Unit,
) {
    val daySpacing = if (isLast) 0.dp else ScheduleTimelineDaySpacing
    Column(
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .testTag("schedule_day_column_$index"),
    ) {
        // 日期叠在「X 天后」下面：头部只比海报行宽出天数与日期里较长的那一行，单部作品的一天不被头部撑长。
        Column(Modifier.padding(end = daySpacing).testTag("schedule_header_$index")) {
            header()
        }

        // 轴线与圆点
        // 天与天的间距算在每一天自己的宽度里（头部和海报行末尾留白，轴线不留），
        // 轴线铺满整列即贯穿到下一天的起点，最后一天没有间距、轴线止于列尾。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .padding(vertical = 2.dp)
                .testTag("schedule_axis_box_$index"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ScheduleTimelineAxisThickness)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .testTag("schedule_axis_line_$index"),
            )
            Box(
                modifier = Modifier
                    .size(ScheduleTimelineDotSize)
                    .clip(CircleShape)
                    .background(
                        if (isToday) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.surface,
                    )
                    .border(
                        ScheduleTimelineDotBorder,
                        if (isToday) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .testTag("schedule_axis_dot_$index"),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(ScheduleTimelinePosterSpacing),
            modifier = Modifier
                .padding(top = 4.dp, end = daySpacing)
                .testTag("schedule_posters_row_$index"),
            content = posters,
        )
    }
}


/** 一天的头部文字（上「3 天后」、下「10 月 19 日 周一」）；两个文字都为 null 画骨架——字阶只在这里写一份。 */
@Composable
internal fun ScheduleDayHeader(dayWord: String?, dateText: String?, index: Int, isToday: Boolean) {
    val dayStyle = MaterialTheme.typography.titleMedium
    val dateStyle = MaterialTheme.typography.bodySmall
    if (dayWord != null && dateText != null) {
        Text(
            text = dayWord,
            style = dayStyle,
            maxLines = 1,
            softWrap = false,
            color = if (isToday) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("schedule_day_word_$index"),
        )
        Text(
            text = dateText,
            style = dateStyle,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("schedule_date_text_$index"),
        )
    } else {
        SkeletonText(dayStyle, Modifier.width(ScheduleSkeletonDayWordWidth))
        SkeletonText(dateStyle, Modifier.width(ScheduleSkeletonDateWidth))
    }
}


/** 骨架头部两段占位的宽度（只影响横向，高度由 [SkeletonText] 跟字阶走）。设计决定（非 M3）：约「3 天后」「10月19日」的宽度。 */
private val ScheduleSkeletonDayWordWidth = 44.dp

private val ScheduleSkeletonDateWidth = 52.dp


/** 首页海报圆角 12（`CornerMedium`）：追更时间表与好友在看的海报本身、及其按下水波纹（[PressArea]）共用。 */
internal val HomePosterShape = ShapeDefaults.Medium


/**
 * 一张时间表海报卡的框架——真实卡与骨架共用的唯一出处：海报宽、海报与标题的间距、标题字阶与最多两行。
 * [poster] 拿到海报宽：真卡交给 [SubjectPoster]、骨架交给 [SubjectPosterSkeletonBox]，都是 3:4、[HomePosterShape]。
 * [title] 为 null 画骨架（标题取上界两行）。
 */
@Composable
internal fun SchedulePosterFrame(
    title: String?,
    poster: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(ScheduleTimelinePosterWidth)) {
        poster(Modifier.width(ScheduleTimelinePosterWidth))
        val titleModifier = Modifier.padding(top = 6.dp)
        val titleStyle = MaterialTheme.typography.bodySmall
        if (title != null) {
            Text(
                text = title,
                style = titleStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier,
            )
        } else {
            SkeletonText(titleStyle, titleModifier, lines = 2, widthFraction = 0.9f)
        }
    }
}


/** 追更时间表：按下一个未来播出日的正片分组的海报时间轴。 */
@Composable
internal fun ScheduleTimeline(
    groups: List<ScheduleDayGroup<HomeScheduleItem>>,
    onSubjectClick: (Int) -> Unit,
) {
    ScheduleTimelineRow {
        itemsIndexed(groups, key = { _, it -> "${it.daysUntil}_${it.date}" }) { index, group ->
            val isToday = group.daysUntil == 0L
            ScheduleDayColumn(
                index = index,
                isToday = isToday,
                isLast = index == groups.lastIndex,
                header = {
                    ScheduleDayHeader(
                        dayWord = dayOffsetLabel(group.daysUntil),
                        dateText = formatMonthDay(group.date, LocalConfiguration.current.locales[0]),
                        index = index,
                        isToday = isToday,
                    )
                },
                posters = {
                    group.items.forEachIndexed { cardIndex, item ->
                        PressArea(
                            onClick = { onSubjectClick(item.subject.id) },
                            shape = HomePosterShape,
                            modifier = Modifier.testTag(HomeScheduleCardTagPrefix + index + "_" + cardIndex),
                        ) {
                            SchedulePosterFrame(
                                title = displayNameOf(item.subject),
                                poster = { size ->
                                    SubjectPoster(item.subject.coverUrl, size, shape = HomePosterShape) {
                                        ScheduleCoverTopBadges(
                                            time = item.airing?.label(),
                                            unwatchedCount = item.airedUnwatchedCount,
                                            timeTag = "schedule_time_badge_${index}_$cardIndex",
                                            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(CoverBadgeInset),
                                        )
                                        ScheduleEpisodeBadge(
                                            item.nextUpcomingSort,
                                            Modifier.align(Alignment.BottomStart).testTag("schedule_episode_badge_${index}_$cardIndex"),
                                        )
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}


/** 封面顶边两枚小牌之间至少留的距离（设计决策，not in M3）。 */
private val CoverTopBadgeGap = 4.dp

/**
 * 封面顶边的两枚小牌：左上是播出时刻（[CoverBadge]，没有时刻就没有这枚），右上是未看集数（[UnwatchedCountBadge]）。
 * 宽度是预算：两枚并排放不下时（大字号）不画时刻，未看集数留着——它说的是用户自己的进度；都量过才决定，不按字数猜。
 * 条件内容各包一层 Box，子节点的下标才稳定。
 */
@Composable
private fun ScheduleCoverTopBadges(time: String?, unwatchedCount: Int, timeTag: String, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Box { if (time != null) CoverBadge(time, Modifier.testTag(timeTag)) }
            Box { UnwatchedCountBadge(unwatchedCount) }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val timeBadge = measurables[0].measure(loose)
        val countBadge = measurables[1].measure(loose)
        val gap = CoverTopBadgeGap.roundToPx()
        val width = constraints.constrainWidth(
            if (constraints.hasBoundedWidth) constraints.maxWidth else timeBadge.width + gap + countBadge.width,
        )
        val timeFits = timeBadge.width > 0 && timeBadge.width + (if (countBadge.width > 0) gap + countBadge.width else 0) <= width
        val height = constraints.constrainHeight(maxOf(if (timeFits) timeBadge.height else 0, countBadge.height))
        layout(width, height) {
            if (timeFits) timeBadge.placeRelative(0, 0)
            countBadge.placeRelative(width - countBadge.width, 0)
        }
    }
}

/**
 * 压在时间表封面左下角的集数小牌（[CoverBadge]）。
 *
 * 一行写完：放得下写「第 25 集」，放不下（大字号）只写集号，读屏照样念「第 25 集」。小牌宽受封面宽（常量）限制，
 * 能用的宽度在组合时就量得出——这一天的列宽要查固有尺寸，不能用按约束换内容的布局。
 */
@Composable
private fun ScheduleEpisodeBadge(sort: Double, modifier: Modifier = Modifier) {
    val label = episodeProgressLabel(sort)
    val number = formatEpisodeSort(sort)
    val style = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val shown = remember(label, number, style, density) {
        val room = with(density) { (ScheduleTimelinePosterWidth - (CoverBadgeInset + CoverBadgeTextPadding) * 2).roundToPx() }
        if (measurer.measure(label, style, maxLines = 1, softWrap = false).size.width <= room) label else number
    }
    CoverBadge(shown, modifier.padding(CoverBadgeInset).clearAndSetSemantics { contentDescription = label })
}
