package io.github.touhougo.chogumi.feature.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.AiringMinute
import io.github.touhougo.chogumi.core.model.CalendarDaySections
import io.github.touhougo.chogumi.core.model.CalendarEntry
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.nowLineIndex
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkHost
import io.github.touhougo.chogumi.core.ui.collection.posterCoverShape
import io.github.touhougo.chogumi.core.ui.collection.posterMarkGesture
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.ChogumiPullToRefreshBox
import io.github.touhougo.chogumi.core.ui.composables.CollapsibleHeader
import io.github.touhougo.chogumi.core.ui.composables.ConnectedButtonGroup
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeInset
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PageHeaderBar
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.composables.PosterCardWidth
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.TextPill
import io.github.touhougo.chogumi.core.ui.composables.calendarWatchersLabel
import io.github.touhougo.chogumi.core.ui.composables.collectionProgressLabel
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.composables.weekdayNarrowLabel
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import org.koin.compose.viewmodel.koinViewModel

/**
 * 每日放送页：
 * - 顶栏：M3 顶栏（返回 + 标题「每日放送」），与七天选择器连成一块，见 [CalendarTopBar]；
 * - 七天选择器：连通按钮组，今天（放送日，北京时间 4 点翻页）带 4×4dp primary 圆点；
 * - 左右滑动翻页：[TabPager] 在七天之间切换，与选择器共用 selectedDay 单一真源；每一页独立滚动状态；
 * - 「我在追」独立灰底分组卡（圆角 20dp，横滑海报卡 + 进度，封面左下角是播出时刻）；
 * - 其余是一天的时间线（[CalendarDayPage]）：左边一列时刻，右边同一时刻播出的作品连成一段分段列表；今天那页在第一个
 *   还没到的时刻之前画「现在」线；没有时刻的作品在最后一组「时刻未知」。
 */
@Composable
fun CalendarScreen(
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CalendarViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)

    // 「我在追」的卡和时间线里的行都能长按标记
    PosterMarkHost(uiState.posterMarks, viewModel.posterMarks, isLoggedIn, onLogin, snackbarHostState, modifier.fillMaxSize()) {
        CalendarContent(
            uiState = uiState,
            selectedDay = uiState.selectedDay,
            onSelectDay = viewModel::onSelectDay,
            onBack = onBack,
            onSubjectClick = onSubjectClick,
            onRefresh = viewModel::refresh,
        )
        // 收藏面板开着时提示条画在面板上
        if (!uiState.posterMarks.sheet.isShown) {
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** 放送可视内容（stateless）：可直接用固定 [CalendarUiState] 测试与截图 */
@Composable
internal fun CalendarContent(
    uiState: CalendarUiState,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 七天各一页（周一 = 第 0 页）。选中哪天是唯一真源，翻页与选择器的同步在 core:ui 的 TabPager 里
    val pager = rememberTabPagerState(
        selected = (selectedDay - 1).coerceIn(0, 6),
        pageCount = 7,
        onSelect = { page -> onSelectDay(page + 1) },
    )

    // 七个页各自拥有独立的滚动状态，翻页互不干扰、各自保留滚动位置
    val listStates = listOf(
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
        rememberLazyListState(),
    )
    // 顶栏、七天选择器连同状态栏那一截是同一块页面底色 `surface`，内容滚到下面时只靠柔边分层（PinnedEdge.kt）
    Column(modifier = modifier.fillMaxSize()) {
        Spacer(Modifier.fillMaxWidth().statusBarsPadding())
        // 顶栏随列表往上滚让位、一往回滚就出现，七天选择器紧贴其下、收起后独自吸在最上面（[CollapsibleHeader]）
        CollapsibleHeader(
            modifier = Modifier.weight(1f),
            collapsing = { CalendarTopBar(onBack = onBack) },
            pinned = {
                CalendarDaySelector(
                    today = uiState.today,
                    selectedDay = pager.shownPage + 1,
                    onSelectDay = onSelectDay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                )
            },
        ) {
            ChogumiPullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                TabPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val day = page + 1
                    when (sectionStateOf(uiState.calendar) { days -> days[day]?.isEmpty != false }) {
                        SectionState.LOADING -> CalendarTimelineSkeleton(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding(),
                        )

                        SectionState.ERROR -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            uiState.calendar.failureOrNull()?.let { failure ->
                                RetryError(failure, onRetry = onRefresh, Modifier.fillMaxWidth())
                            }
                        }

                        SectionState.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            EmptyLine(stringResource(R.string.no_content))
                        }

                        SectionState.CONTENT -> CalendarDayPage(
                            sections = uiState.calendar.valueOrNull()?.get(day) ?: CalendarDaySections.EMPTY,
                            // 「现在」线只画在今天那页：别的日子没有「此刻」可标
                            now = uiState.now.minute.takeIf { uiState.now.weekday == day },
                            progressCounts = uiState.progressCounts,
                            state = listStates[page],
                            onSubjectClick = onSubjectClick,
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .then(if (day == selectedDay) Modifier.testTag("calendar_list") else Modifier),
                        )
                    }
                }
            }
        }
    }
}

/** 放送日历的顶栏：页面底色，与七天选择器、状态栏那一截同一块（见 [CalendarContent]）。 */
@Composable
private fun CalendarTopBar(
    onBack: () -> Unit,
) {
    // 只留主标题：排序固定按时刻与在看人数，不需要副标题解释（产品决定）。
    PageHeaderBar(
        title = stringResource(R.string.calendar_title),
        modifier = Modifier.testTag("calendar_top_bar"),
        navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("calendar_back")) {
                Icon(painterResource(R.drawable.arrow_back_24), contentDescription = stringResource(R.string.back))
            }
        },
    )
}

/**
 * 七天选择器：连通按钮组（[ConnectedButtonGroup]，形状 / 配色 / 按压弹性与全站分段一致），
 * 今天带 4×4dp primary 圆点，无论选中与否都显示。
 */
@Composable
private fun CalendarDaySelector(
    today: Int,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 星期全名全仓只有 weekday_names 一份；读屏念全名，今天后面跟「（今天）」
    val dayNames = stringArrayResource(R.array.weekday_names)
    val dayDescriptions = dayNames.mapIndexed { index, name ->
        if (index + 1 == today) stringResource(R.string.calendar_day_today_accessibility, name) else name
    }
    ConnectedButtonGroup(
        options = (1..7).toList(),
        selected = selectedDay,
        onSelect = onSelectDay,
        modifier = modifier.testTag("calendar_day_selector"),
        contentPadding = PaddingValues(0.dp),
        itemModifier = { day ->
            Modifier
                .height(40.dp)
                .semantics { contentDescription = dayDescriptions[day - 1] }
                .testTag("calendar_day_${day - 1}")
        },
    ) { day, checked ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = weekdayNarrowLabel(day),
                // 星期格是按钮组里的标签：label large，选中用它的 emphasized
                style = if (checked) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
            )
            if (day == today) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 5.dp)
                        .size(4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        )
                        .testTag("calendar_today_dot"),
                )
            }
        }
    }
}

/**
 * 单日内容页：「我在追」分区，然后是按时刻分组的时间线，最后「时刻未知」。一行一个懒加载项；
 * 「现在」线（[now] 不为 null 时）画在第一个还没到的时刻之前，都开播了画在最后一组之后（[nowLineIndex]）。
 */
@Composable
private fun CalendarDayPage(
    sections: CalendarDaySections,
    now: AiringMinute?,
    progressCounts: Map<Int, ProgressCount>,
    state: LazyListState,
    onSubjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowIndex = now?.let { sections.slots.nowLineIndex(it) }
    CalendarPageList(state = state, modifier = modifier) {
        if (sections.following.isNotEmpty()) {
            item(key = "following_section") {
                CalendarFollowingSection(count = sections.following.size) {
                    items(sections.following, key = { it.subject.id }) { entry ->
                        CalendarFollowingCard(
                            entry = entry,
                            progressCount = progressCounts[entry.subject.id] ?: ProgressCount.None,
                            onClick = { onSubjectClick(entry.subject.id) },
                        )
                    }
                }
            }
        }
        sections.slots.forEachIndexed { slotIndex, slot ->
            if (slotIndex == nowIndex) {
                item(key = "now_line") { CalendarNowLine(Modifier.padding(top = if (slotIndex == 0) 0.dp else GroupGap)) }
            }
            val timeStyle = slotTimeStyle(slotIndex, nowIndex)
            slot.entries.forEachIndexed { index, entry ->
                item(key = entry.subject.id) {
                    CalendarEntryRow(
                        entry = entry,
                        index = index,
                        count = slot.entries.size,
                        timeLabel = slot.minute.label().takeIf { index == 0 },
                        timeStyle = timeStyle,
                        progressCount = progressCounts[entry.subject.id] ?: ProgressCount.None,
                        onClick = { onSubjectClick(entry.subject.id) },
                        modifier = Modifier.padding(top = rowTopGap(index, first = slotIndex == 0)),
                    )
                }
            }
        }
        if (nowIndex != null && nowIndex == sections.slots.size && sections.slots.isNotEmpty()) {
            item(key = "now_line") { CalendarNowLine(Modifier.padding(top = GroupGap)) }
        }
        if (sections.unknown.isNotEmpty()) {
            item(key = "unknown_header") { CalendarUnknownHeader(count = sections.unknown.size) }
            sections.unknown.forEachIndexed { index, entry ->
                item(key = entry.subject.id) {
                    CalendarEntryRow(
                        entry = entry,
                        index = index,
                        count = sections.unknown.size,
                        timeLabel = null,
                        timeStyle = null,
                        progressCount = progressCounts[entry.subject.id] ?: ProgressCount.None,
                        onClick = { onSubjectClick(entry.subject.id) },
                        modifier = Modifier.padding(top = rowTopGap(index, first = true)),
                    )
                }
            }
        }
    }
}

/** 一组之间留 [GroupGap]，组内的行之间是分段列表的 2dp；第一组（或刚画完「现在」线、标题行之后）上面不留。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun rowTopGap(index: Int, first: Boolean): Dp = when {
    index > 0 -> ListItemDefaults.SegmentedGap
    first -> 0.dp
    else -> GroupGap
}

/** 一组的时刻怎么画：已开播的退成次要色，下一组（「现在」线正下方）主色加强调，没到的正文色；不是今天时都是正文色。 */
private data class SlotTimeStyle(val emphasized: Boolean, val past: Boolean, val next: Boolean)

private fun slotTimeStyle(slotIndex: Int, nowIndex: Int?): SlotTimeStyle = when {
    nowIndex == null -> SlotTimeStyle(emphasized = false, past = false, next = false)
    slotIndex < nowIndex -> SlotTimeStyle(emphasized = false, past = true, next = false)
    slotIndex == nowIndex -> SlotTimeStyle(emphasized = true, past = false, next = true)
    else -> SlotTimeStyle(emphasized = false, past = false, next = false)
}

@Composable
private fun SlotTimeStyle.textStyle(): TextStyle =
    if (emphasized) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium

@Composable
private fun SlotTimeStyle.color(): Color = when {
    next -> MaterialTheme.colorScheme.primary
    past -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

/** 时刻列的宽：「24:20」在 title medium 下的宽加一点余量，随字号缩放（两倍字号下时刻也是两倍宽），所以按 sp 给。 */
private val TimeColumnWidth = 52.sp

/** 时刻列与右边列表之间的距离。 */
private val TimeColumnGap = 8.dp

/** 两组时刻之间的距离（设计决策，not in M3）。 */
private val GroupGap = 12.dp

/** 「现在」线那一项的高度：线与圆点居中其中。 */
private val NowLineHeight = 16.dp

@Composable
private fun timeColumnWidth(): Dp = with(LocalDensity.current) { TimeColumnWidth.toDp() }

/** 骨架与真内容共用的列表框架：上下留白、横向由每一项自己留（时刻列要顶到屏幕边距）。 */
@Composable
private fun CalendarPageList(
    modifier: Modifier,
    state: LazyListState = rememberLazyListState(),
    loading: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        userScrollEnabled = !loading,
        content = content,
    )
}

@Composable
private fun CalendarSectionHeader(title: String, count: Int?, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        val style = MaterialTheme.typography.bodySmall
        if (count != null) {
            Text(
                pluralStringResource(R.plurals.work_count, count, count),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 数量未知：SkeletonText 走真实 Text 的排版路径，行高随字号缩放；这里只定占位比例。
            SkeletonText(style = style, widthFraction = 0.4f)
        }
    }
}

/** 「时刻未知」的标题行，与右边那一列对齐（时刻列空着）。 */
@Composable
private fun CalendarUnknownHeader(count: Int?) {
    CalendarSectionHeader(
        title = stringResource(R.string.calendar_unknown_time),
        count = count,
        modifier = Modifier
            .padding(start = ChogumiDimens.ScreenPadding + timeColumnWidth() + TimeColumnGap, end = ChogumiDimens.ScreenPadding)
            .padding(top = GroupGap + 4.dp, bottom = 4.dp)
            .testTag("calendar_unknown_header"),
    )
}

@Composable
private fun CalendarFollowingSection(count: Int?, content: LazyListScope.() -> Unit) {
    // 「我在追」整块的底：圆角取 M3 Expressive 的 Large increased（20dp）
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, top = 4.dp, bottom = 8.dp)
            .testTag("calendar_following_section"),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
            CalendarSectionHeader(
                title = stringResource(R.string.calendar_following_section),
                count = count,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                userScrollEnabled = count != null,
                modifier = Modifier.fillMaxWidth().testTag("calendar_following_row"),
                content = content,
            )
        }
    }
}

/** 我在追横滑卡片：横排的海报卡（[PosterCardStyle.CAROUSEL]），封面左下角是播出时刻，标题下是追番进度。 */
@Composable
private fun CalendarFollowingCard(
    entry: CalendarEntry,
    progressCount: ProgressCount,
    onClick: () -> Unit,
) {
    val subject = entry.subject
    val title = displayNameOf(subject)
    val followingDesc = stringResource(R.string.calendar_following)
    val progressLabel = collectionProgressLabel(progressCount)
    val timeLabel = entry.airing?.label()
    val separator = stringResource(R.string.list_separator)
    PosterCard(
        imageUrl = subject.coverUrl,
        title = title,
        style = PosterCardStyle.CAROUSEL,
        onClick = onClick,
        mark = rememberPosterMark(subject.id, subject.type),
        modifier = Modifier
            .width(PosterCardWidth)
            .semantics { contentDescription = listOfNotNull(title, timeLabel, followingDesc, progressLabel).joinToString(separator) }
            .testTag("calendar_following_card_${subject.id}"),
        supporting = progressLabel,
    ) {
        if (timeLabel != null) {
            TextPill(
                text = timeLabel,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.align(Alignment.BottomStart).padding(CoverBadgeInset),
            )
        }
    }
}

/**
 * 时间线里的一行：左边时刻列（只有一组的第一行写时刻，其余行空着对齐），右边分段列表项——封面、标题两行、在看人数，
 * 在追的作品右侧是进度。整行可长按标记（封面右上角是「我」的收藏状态）；行的按下由列表项自己画，封面只跟着标记环的开合变形。
 */
@Composable
private fun CalendarEntryRow(
    entry: CalendarEntry,
    index: Int,
    count: Int,
    timeLabel: String?,
    timeStyle: SlotTimeStyle?,
    progressCount: ProgressCount,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subject = entry.subject
    val title = displayNameOf(subject)
    val watchersLabel = calendarWatchersLabel(entry.watchers)
    val watchersA11y = stringResource(R.string.calendar_watchers, entry.watchers)
    val progressLabel = collectionProgressLabel(progressCount)
    val separator = stringResource(R.string.list_separator)
    val mark = rememberPosterMark(subject.id, subject.type)
    val interaction = remember { MutableInteractionSource() }
    val coverShape = posterCoverShape(mark, interaction, ListThumbCorner)
    CalendarRowFrame(
        modifier = modifier,
        time = {
            if (timeLabel != null && timeStyle != null) {
                Text(timeLabel, style = timeStyle.textStyle(), color = timeStyle.color(), maxLines = 1)
            }
        },
    ) {
        SegmentedItem(
            index = index,
            count = count,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .posterMarkGesture(mark)
                .semantics { contentDescription = listOfNotNull(title, timeLabel, watchersA11y, progressLabel).joinToString(separator) }
                .testTag("calendar_row_${subject.id}"),
            verticalAlignment = Alignment.Top,
            leadingContent = { SubjectPoster(subject.coverUrl, Modifier.width(ChogumiDimens.ListThumbWidth), shape = coverShape, mark = mark) },
            trailingContent = progressLabel?.let { label ->
                {
                    TextPill(
                        text = label,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            },
            details = { Text(watchersLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        ) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 列表缩略图的圆角：M3 的 Small（8dp），与海报卡一致。 */
private val ListThumbCorner = 8.dp

/** 时刻列 + 列表项：时刻顶着列表项的上内边距，与标题第一行同一条线。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CalendarRowFrame(modifier: Modifier, time: @Composable () -> Unit, item: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(horizontal = ChogumiDimens.ScreenPadding)) {
        Box(Modifier.width(timeColumnWidth()).padding(top = ListItemDefaults.ContentPadding.calculateTopPadding())) { time() }
        Spacer(Modifier.width(TimeColumnGap))
        Box(Modifier.weight(1f)) { item() }
    }
}

/** 「现在」线：时刻列里写「现在」，右边一个圆点加一条线横过整个列表列，都是主色。 */
@Composable
private fun CalendarNowLine(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ChogumiDimens.ScreenPadding)
            .height(NowLineHeight)
            .testTag("calendar_now_line"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.calendar_now),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.width(timeColumnWidth()),
        )
        Spacer(Modifier.width(TimeColumnGap))
        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Box(Modifier.weight(1f).height(2.dp).background(MaterialTheme.colorScheme.primary))
    }
}

/** 骨架行的 testTag 前缀：与真行的 `calendar_row_<id>` 一样标在分段列表项上，几何测试据此对位。 */
internal const val CalendarSkeletonRowTagPrefix = "calendar_skeleton_row_"

/** 骨架里每组几行：两组，第一组两行、第二组三行（设计决定，不在 M3）；内容到位时允许上收。 */
private val SkeletonGroups = listOf(2, 3)

/** 时间线骨架：追番分区三张卡，然后是与真行同一个框架的占位行（时刻列的色条只在每组第一行）。 */
@Composable
private fun CalendarTimelineSkeleton(
    modifier: Modifier = Modifier,
) {
    CalendarPageList(modifier = modifier, loading = true) {
        item(key = "following_section") {
            CalendarFollowingSection(count = null) {
                items(3) { index ->
                    PosterCardSkeleton(
                        PosterCardStyle.CAROUSEL,
                        Modifier.width(PosterCardWidth).testTag("calendar_skeleton_following_card_$index"),
                        supporting = true,
                    )
                }
            }
        }
        var row = 0
        SkeletonGroups.forEachIndexed { groupIndex, count ->
            repeat(count) { index ->
                val tag = CalendarSkeletonRowTagPrefix + row++
                item(key = tag) {
                    CalendarEntryRowSkeleton(
                        index = index,
                        count = count,
                        withTime = index == 0,
                        tag = tag,
                        modifier = Modifier.padding(top = rowTopGap(index, first = groupIndex == 0)),
                    )
                }
            }
        }
    }
}

/** 真行的占位：同一个 [CalendarRowFrame] 与 [SegmentedItem]，封面框同宽，标题与在看人数各一段字条。 */
@Composable
private fun CalendarEntryRowSkeleton(index: Int, count: Int, withTime: Boolean, tag: String, modifier: Modifier = Modifier) {
    CalendarRowFrame(
        modifier = modifier,
        time = { if (withTime) SkeletonText(MaterialTheme.typography.titleMedium, widthFraction = 0.9f) },
    ) {
        SegmentedItem(
            index = index,
            count = count,
            onClick = null,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            verticalAlignment = Alignment.Top,
            leadingContent = { SubjectPosterSkeletonBox(Modifier.width(ChogumiDimens.ListThumbWidth)) },
            details = { SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.4f) },
        ) {
            SkeletonText(MaterialTheme.typography.bodyLarge, widthFraction = 0.7f)
        }
    }
}
