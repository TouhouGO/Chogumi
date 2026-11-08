package io.github.touhougo.chogumi.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.glass.LocalGlassContentBottom
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetHost
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.navigation.ScrollToTopOnTabReselect
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.CollectionListItem
import io.github.touhougo.chogumi.core.ui.composables.CollectionListItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.EpButtonPlaceholder
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SectionHeaderSkeleton
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.formatMonthDay
import org.koin.compose.viewmodel.koinViewModel


/**
 * 今日首页。
 */
@Composable
fun HomeScreen(
    onSubjectClick: (Int) -> Unit,
    /** 去进度页：类型（null 为全部类型），进度页据此切类型并回到「在看」。 */
    onProgress: (SubjectType?) -> Unit,
    onFeedClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)

    val dateStr = formatMonthDay(state.today, LocalConfiguration.current.locales[0])

    PageScaffold(
        title = stringResource(R.string.home_today_title),
        subtitle = dateStr,
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        contentWindowInsets = WindowInsets(0),
        modifier = modifier,
        // 收藏面板开着时提示条画在面板上（页面的在面板下面，看不见）：同一个提示条状态，只换画的位置
        snackbarHost = {
            if (!state.collectionSheet.isShown) SnackbarHost(snackbarHostState, Modifier.padding(bottom = LocalGlassContentBottom.current))
        },
    ) { padding ->
        TodayHomeContent(
            state = state,
            onRefresh = viewModel::refresh,
            topPadding = padding.calculateTopPadding(),
            onSubjectClick = onSubjectClick,
            onEdit = viewModel::openCollectionSheet,
            onOpenProgress = onProgress,
            onContinueFilterChange = viewModel::onContinueFilterChange,
            onEpButtonTap = viewModel::onEpButtonTap,
            onMarkFinished = viewModel::markDone,
            onFeedClick = onFeedClick,
        )
    }

    // 长按「继续」一行：与详情页 FAB 同一个收藏面板
    CollectionSheetHost(
        state = state.collectionSheet,
        work = state.collectionSheet.fetched.valueOrNull(),
        today = state.today,
        snackbar = snackbarHostState,
        onRetryEpisodes = viewModel::retryCollectionEpisodes,
        onSave = viewModel::saveCollection,
        onRemove = viewModel::removeCollection,
        onClose = viewModel::closeCollectionSheet,
    )
}


@Composable
internal fun TodayHomeContent(
    state: HomeUiState,
    onRefresh: () -> Unit,
    /** 顶栏（下拉刷新由外层脚手架包着）的高度：列表在顶栏下面滚，内容滚到它下面时顶栏填色。 */
    topPadding: Dp,
    onSubjectClick: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onOpenProgress: (SubjectType?) -> Unit,
    onContinueFilterChange: (SubjectType?) -> Unit,
    onEpButtonTap: (HomeProgressCard) -> Unit,
    onMarkFinished: (HomeProgressCard) -> Unit,
    onFeedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    ScrollToTopOnTabReselect(listState)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = topPadding, bottom = 24.dp + LocalGlassContentBottom.current),
        modifier = modifier.fillMaxSize(),
    ) {
        // 首个 /p1/home 结果到达前只画骨架：各区块数据到达时间不同，
        // 若先画「也在进行」再在它上面插入待看等区块，列表会锚定在「也在进行」而停在中间。
        when (sectionStateOf(state.progress) { false }) {
            SectionState.LOADING -> {
                item(key = "skeleton", contentType = "skeleton") { HomeSkeleton() }
                return@LazyColumn
            }
            SectionState.ERROR -> {
                item(key = "error", contentType = "error") {
                    state.progress.failureOrNull()?.let { RetryError(it, onRetry = onRefresh, Modifier.fillParentMaxWidth()) }
                }
                return@LazyColumn
            }
            SectionState.CONTENT, SectionState.EMPTY -> Unit
        }

        // 1. 待看 (TO_WATCH)
        if (state.toWatch.isNotEmpty()) {
            item(key = "section_to_watch", contentType = "section_to_watch") {
                SectionHeader(
                    modifier = Modifier.testTag(HomeToWatchHeaderTag),
                    title = stringResource(R.string.home_to_watch_section),
                    // 待看只是「马上要标记的前几部」，全量在进度页——右侧固定给「全部」入口，
                    // 复用「继续」区块同一文案与跳转（参数 null = 全部类型）。
                    trailingText = stringResource(R.string.home_section_all),
                    onTrailingClick = { onOpenProgress(null) },
                )
                ToWatchRow(
                    items = state.toWatch,
                    pendingVisuals = state.pendingVisuals,
                    onEpButtonTap = onEpButtonTap,
                    onMarkFinished = onMarkFinished,
                    onSubjectClick = onSubjectClick,
                )
            }
        }

        // 2. 追更时间表
        if (state.scheduleGroups.isNotEmpty()) {
            item(key = "section_schedule", contentType = "section_schedule") {
                SectionHeader(
                    modifier = Modifier.testTag(HomeScheduleHeaderTag),
                    title = stringResource(R.string.home_schedule_section),
                )
                ScheduleTimeline(groups = state.scheduleGroups, onSubjectClick = onSubjectClick)
            }
        }

        // 3. 继续 (CONTINUE)：已在 ViewModel 里过滤并截断
        if (state.continueItems.isNotEmpty()) {
            item(key = "section_continue", contentType = "section_continue") {
                SectionHeader(
                    modifier = Modifier.testTag(HomeContinueHeaderTag),
                    title = stringResource(R.string.home_continue_section),
                    trailingText = stringResource(R.string.home_section_all),
                    // 「全部」带的是**实际生效**的过滤值（选中的分类消失时已退回 null）。
                    onTrailingClick = { onOpenProgress(state.continueFilter) },
                )
                // chip 清单由实际条目派生（[HomeUiState.continueChips]）；只剩一个分类时为空，不画这一行。
                if (state.continueChips.isNotEmpty()) {
                    ContinueFilterChips(
                        chips = state.continueChips,
                        selectedFilter = state.continueFilter,
                        onFilterSelected = onContinueFilterChange,
                    )
                }
            }
            // 「继续」的行是同一种、互相复用槽位；其余区块各只有一个，各自一类，滚动时不拿别的区块的槽位去拼
            itemsIndexed(
                state.continueItems,
                key = { _, it -> "continue_${it.collection.subjectId}" },
                contentType = { _, _ -> "continue_row" },
            ) { index, item ->
                CollectionListItem(
                    modifier = Modifier.testTag(HomeContinueRowTagPrefix + index),
                    item = item.collection,
                    onClick = { onSubjectClick(item.collection.subjectId) },
                    onEdit = { onEdit(item.collection.subjectId) },
                    progressCount = item.card.progressCount,
                    epButtonState = item.card.epButtonState,
                    onNextEpisode = { onEpButtonTap(item.card) },
                    onMarkFinished = { onMarkFinished(item.card) },
                    pendingVisual = state.pendingVisuals[item.collection.subjectId],
                )
            }
        } else if (state.continueCollapsed) {
            // 候选全被顶部覆盖：不重复顶部作品、不摆空占位，收成一行完整进度入口（null = 全部类型）。
            item(key = "section_continue_collapsed", contentType = "section_continue_collapsed") {
                SectionHeader(
                    title = stringResource(R.string.home_continue_all_progress),
                    onArrowClick = { onOpenProgress(null) },
                )
            }
        }

        // 4. 也在进行
        if (state.doingItems.isNotEmpty()) {
            item(key = "section_doing", contentType = "section_doing") {
                SectionHeader(title = stringResource(R.string.home_doing_section))
                DoingList(items = state.doingItems, onSubjectClick = onSubjectClick)
            }
        }

        // 5. 好友在看：专注模式不画，就绪空结果隐藏；等待与失败按同一个区块三态判定。
        if (state.showFriends) {
            val friendsState = sectionStateOf(state.friends) { it.isEmpty() }
            if (friendsState != SectionState.EMPTY) {
                item(key = "section_friends", contentType = "section_friends") {
                    SectionHeader(
                        title = stringResource(R.string.home_friends_section),
                        onArrowClick = onFeedClick,
                    )
                    when (friendsState) {
                        SectionState.LOADING -> FriendsActivitiesSkeleton()
                        SectionState.ERROR -> state.friends.failureOrNull()?.let {
                            RetryError(it, onRetry = onRefresh, modifier = Modifier.fillMaxWidth(), compact = true)
                        }
                        SectionState.CONTENT -> FriendsActivities(groups = state.friends.valueOrNull().orEmpty(), onSubjectClick = onSubjectClick)
                        SectionState.EMPTY -> Unit
                    }
                }
            }
        }

        // 空列表（continueItems 为空时 continueFilter 必为 null，无需再判）
        if (state.toWatch.isEmpty() && state.scheduleGroups.isEmpty() &&
            state.continueItems.isEmpty() && state.doingItems.isEmpty()
        ) {
            item(key = "empty", contentType = "empty") {
                EmptyLine(stringResource(R.string.empty_doing))
            }
        }
    }
}


/** 骨架里「待看」卡片标题占位的 testTag（测试据此算标题条的像素位置）。 */
internal const val HomeSkeletonTitleTag = "home_skeleton_title"


/** 几何锚点的 testTag：骨架与真实内容用**同一个** tag，测试在翻转 state 前后各读一次位置。 */
internal const val HomeToWatchHeaderTag = "home_to_watch_header"

internal const val HomeScheduleHeaderTag = "home_schedule_header"

internal const val HomeContinueHeaderTag = "home_continue_header"


/** 「待看」卡片内 EP 按钮（或占位）的 testTag 前缀，后接卡片序号。 */
internal const val HomeToWatchEpTagPrefix = "home_to_watch_ep_"


/** 追更时间表海报卡的 testTag 前缀，后接「第几天_第几张」。 */
internal const val HomeScheduleCardTagPrefix = "home_schedule_card_"


/** 「继续」列表行的 testTag 前缀，后接 0 起的序号。 */
internal const val HomeContinueRowTagPrefix = "home_continue_row_"


/** 真实「待看」横滑卡片的 testTag 前缀，后接 0 起的序号（供测试与骨架对位）。 */
internal const val HomeToWatchCardTagPrefix = "home_to_watch_card_"


/** 骨架「待看」行占位卡片的 testTag 前缀，后接 0 起的序号。 */
internal const val HomeSkeletonCardTagPrefix = "home_skeleton_card_"


/** 骨架「待看」行的占位卡片数：三张足以铺满手机首屏，超出屏幕的部分被 carousel 裁掉。 */
private const val HomeSkeletonCardCount = 3


/** 骨架「追更时间表」的天数：三天足以铺满手机首屏的宽度，超出的部分被裁掉。 */
private const val HomeSkeletonScheduleDayCount = 3


/** 骨架「继续」chip 行的占位 chip 数（「全部」加两个分类）。 */
private const val HomeSkeletonChipCount = 3


/** 骨架「继续」的占位列表行数。 */
private const val HomeSkeletonContinueRowCount = 2


/**
 * 首页骨架：按真实首屏的**同一套框架**画，区块顺序与 [TodayHomeContent] 一致——
 * 待看 → 追更时间表 → 继续。数据到达时原位换成内容，骨架里已画出的区块不会向下移
 * （某个区块真实数据里没有，下面的区块往上收是数据决定的，允许）。
 *
 * - 区块标题走 [SectionHeaderSkeleton]（与 [SectionHeader] 同框架，有行尾「全部」的区块预留按钮空间）；
 * - 待看：[ToWatchCarousel] + [ToWatchCardFrame]，卡片内的字走 [SkeletonText]、按钮走 [EpButtonPlaceholder]；
 * - 追更时间表：[ScheduleTimelineRow] + [ScheduleDayColumn] + [SchedulePosterFrame]；
 * - 继续：chip 行取 [ContinueFilterChipRow] 的框架，列表行是 [CollectionListItemSkeleton]。
 *
 * 数据决定的高度，骨架取**上界**：海报标题画满两行（真实最多两行，一行时下面的区块只会往上收），
 * 「继续」画出 chip 行（真实只剩一个分类时没有这一行）。骨架不可滑动，超出屏幕的部分直接被裁掉。
 */
@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxWidth()) {
        SectionHeaderSkeleton(Modifier.testTag(HomeToWatchHeaderTag), hasTrailingAction = true)
        ToWatchCarousel(itemCount = HomeSkeletonCardCount, userScrollEnabled = false) { index ->
            ToWatchCardFrame(
                modifier = Modifier.testTag(HomeSkeletonCardTagPrefix + index),
                // 海报占位只给矩形色块，圆角由框架里的 maskClip 画
                poster = { SkeletonBox(Modifier.fillMaxSize()) },
                details = {
                    ToWatchTitle(null)
                    EpButtonPlaceholder(showTarget = true, Modifier.testTag(HomeToWatchEpTagPrefix + index))
                },
            )
        }

        SectionHeaderSkeleton(Modifier.testTag(HomeScheduleHeaderTag))
        ScheduleTimelineRow(userScrollEnabled = false) {
            items(HomeSkeletonScheduleDayCount) { index ->
                ScheduleDayColumn(
                    index = index,
                    isToday = false,
                    isLast = index == HomeSkeletonScheduleDayCount - 1,
                    header = { ScheduleDayHeader(dayWord = null, dateText = null, index = index, isToday = false) },
                    posters = {
                        SchedulePosterFrame(
                            title = null,
                            modifier = Modifier.testTag(HomeScheduleCardTagPrefix + index + "_0"),
                            poster = { size -> SubjectPosterSkeletonBox(size, shape = HomePosterShape) },
                        )
                    },
                )
            }
        }

        SectionHeaderSkeleton(Modifier.testTag(HomeContinueHeaderTag), hasTrailingAction = true)
        ContinueFilterChipRow(userScrollEnabled = false) {
            items(HomeSkeletonChipCount) { ContinueFilterChipSkeleton() }
        }
        repeat(HomeSkeletonContinueRowCount) {
            CollectionListItemSkeleton(SubjectType.ANIME, CollectionType.DOING, Modifier.testTag(HomeContinueRowTagPrefix + it))
        }
    }
}
