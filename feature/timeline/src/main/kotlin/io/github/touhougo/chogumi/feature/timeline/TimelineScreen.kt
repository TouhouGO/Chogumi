package io.github.touhougo.chogumi.feature.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.FloorReading
import io.github.touhougo.chogumi.core.model.TIMELINE_TEXT_LIMIT
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineMode
import io.github.touhougo.chogumi.core.model.TimelineQuery
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.reader.ComposerSheet
import io.github.touhougo.chogumi.core.reader.TimelineDetailSheet
import io.github.touhougo.chogumi.core.reader.TimelineFilter
import io.github.touhougo.chogumi.core.reader.TimelineGroupHeader
import io.github.touhougo.chogumi.core.reader.TimelineGroupHeaderSkeleton
import io.github.touhougo.chogumi.core.reader.TimelineRow
import io.github.touhougo.chogumi.core.reader.TimelineRowSkeleton
import io.github.touhougo.chogumi.core.reader.groupConsecutiveByUser
import io.github.touhougo.chogumi.core.reader.mergeConsecutiveTimelineItems
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.rememberLoginGate
import io.github.touhougo.chogumi.core.ui.composables.ChogumiPullToRefreshBox
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.MenuChip
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.PinnedRowFrame
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.loadMoreFooter
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.moderation.ModerationHost
import org.koin.compose.viewmodel.koinViewModel

/**
 * 动态页的「好友 / 全站」（原型 `.cbg`）。
 *
 * @property endRes 翻到底时的页脚；全站时间线服务端只留最近一千条左右，到底时说清楚。
 * @property emptyRes 按类别筛、翻到底也没有一条时整页的那句话（`%1$s` 是类别名）。
 */
private enum class FeedScope(
    val labelRes: Int,
    val iconRes: Int,
    val selectedIconRes: Int,
    val mode: TimelineMode,
    val endRes: Int,
    val emptyRes: Int,
) {
    FRIENDS(R.string.friends, R.drawable.group_24, R.drawable.group_filled_24, TimelineMode.FRIENDS, R.string.load_more_end_timeline, R.string.timeline_empty_friends),
    ALL(
        R.string.everyone, R.drawable.explore_24, R.drawable.explore_filled_24, TimelineMode.ALL,
        R.string.load_more_end_timeline_everyone, R.string.timeline_empty_everyone,
    ),
}

/** 一个范围的动态查询。 */
private fun queryOf(scope: FeedScope) = TimelineQuery(scope.mode)

/** 一屏首次加载时的骨架：两组，每组一个作者头 + 两行作品行（真实动态绝大多数是作品行）。 */
private const val SKELETON_GROUPS = 2
private const val SKELETON_ROWS_PER_GROUP = 2

/**
 * 动态页（原型「首页 → 动态」）：首页「好友在看 ›」进入，是动态唯一的入口；右下角发动态。
 *
 * - 顶部「好友 / 全站」连通按钮组，两者各一页、左右滑切换（[TabPager]）；未登录只有全站一页，好友不可点；
 * - 同一行尾端是类别下拉 chip（[MenuChip]，与网页端动态的分类相同）：十个类别排成一行 chip 放不下，
 *   常驻一整行又占地方；两页共用一个类别，左右滑对比好友与全站的同一类；
 *   接口不能按类别查，筛的是已取到的列表，不够一屏就往前翻页找（[TimelineViewModel.loadMore]）；
 * - 同一人连续的动态只出一次作者头，下面是分段列表；
 * - 点一条打开详情面板（贴贴 / 楼层回复），自己的动态长按删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onUserClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TimelineViewModel = koinViewModel()
    val loginGate = rememberLoginGate(isLoggedIn, onLogin)
    var scope by rememberSaveable { mutableStateOf(if (isLoggedIn) FeedScope.FRIENDS else FeedScope.ALL) }
    // 未登录只有「全站」一页；选中哪一页的唯一真源是 scope，按钮组与左右翻页的同步在 core:ui 的 TabPager 里
    val scopes = if (isLoggedIn) FeedScope.entries else listOf(FeedScope.ALL)
    val shownScope = if (isLoggedIn) scope else FeedScope.ALL
    val selectScope: (FeedScope) -> Unit = { scope = it }
    val pager = rememberTabPagerState(
        selected = scopes.indexOf(shownScope),
        pageCount = scopes.size,
        onSelect = { selectScope(scopes[it]) },
    )
    val query = queryOf(shownScope)
    val state by remember(query) { viewModel.feed(query) }.collectAsStateWithLifecycle()
    val page by viewModel.uiState.collectAsStateWithLifecycle()
    val detail = page.detail

    val snackbar = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(query, page.filter) { viewModel.open(query) }
    FailureNoticeEffect(viewModel.failures, snackbar)

    PageScaffold(
        title = stringResource(R.string.timeline_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { loginGate.run { viewModel.composer.open(Unit) } }) {
                Icon(painterResource(R.drawable.add_24), stringResource(R.string.publish))
            }
        },
        contentWindowInsets = WindowInsets(0),
        modifier = modifier,
    ) { padding ->
        PinnedRowFrame(
            modifier = Modifier.fillMaxSize().padding(padding),
            pinned = {
                // 范围与类别同一行，类别 chip 在尾端；两者间距 8（我的设计决定，not in M3）
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConnectedChoiceGroup(
                        options = FeedScope.entries,
                        selected = scopes[pager.shownPage],
                        label = { stringResource(it.labelRes) },
                        onSelect = selectScope,
                        icon = { option, selected -> if (selected) option.selectedIconRes else option.iconRes },
                        enabled = { it != FeedScope.FRIENDS || isLoggedIn },
                        modifier = Modifier.weight(1f),
                    )
                    MenuChip(
                        selected = page.filter,
                        options = TimelineFilter.entries,
                        label = { stringResource(it.labelRes) },
                        icon = R.drawable.filter_list_24,
                        onSelect = viewModel::selectFilter,
                    )
                }
            },
        ) {
            // 每个范围一页：各自的动态流、各自的滚动位置；没加载过的那页翻过去先是骨架，落定后才去取
            TabPager(pager, Modifier.fillMaxSize(), key = { scopes[it].name }) { index ->
                val pageScope = scopes[index]
                val pageQuery = queryOf(pageScope)
                val feed by remember(pageQuery) { viewModel.feed(pageQuery) }.collectAsStateWithLifecycle()
                ChogumiPullToRefreshBox(
                    isRefreshing = feed.isRefreshing,
                    onRefresh = { viewModel.refresh(pageQuery) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    FeedList(
                        state = feed,
                        filter = page.filter,
                        markFriends = !pageQuery.mode.onlyFriends,
                        scanning = pageQuery.mode in page.scanning,
                        paused = scanPaused(feed.items, page.filter, page.resumeAfter[pageQuery.mode]),
                        endText = stringResource(pageScope.endRes),
                        emptyText = if (page.filter == TimelineFilter.ALL) {
                            stringResource(R.string.no_content)
                        } else {
                            stringResource(pageScope.emptyRes, stringResource(page.filter.labelRes))
                        },
                        onUser = onUserClick,
                        onSubject = onSubjectClick,
                        onOpen = viewModel.detail::open,
                        onDelete = { deleting = it },
                        onLoadMore = { viewModel.loadMore(pageQuery) },
                        onRetry = { viewModel.refresh(pageQuery) },
                    )
                }
            }
        }
    }

    detail.id?.let { id ->
        TimelineDetailSheet(
            item = state.items.firstOrNull { it.id == id },
            state = detail,
            onDismiss = viewModel.detail::close,
            onReact = loginGate.guard(viewModel.detail::react),
            composer = viewModel.detail.composer,
            onReply = loginGate.guard(viewModel.detail::reply),
            onSendReply = loginGate.guard { draft: ComposerDraft, token: String, land: (FloorReading, Int) -> Unit -> viewModel.detail.send(draft, token, land) },
            onModerate = loginGate.guard(viewModel.moderation::perform),
            onRetry = viewModel.detail::retry,
            onSelectSort = viewModel.detail::selectSort,
            onToggleOpOnly = viewModel.detail::toggleOpOnly,
            onUser = onUserClick,
            onSubject = onSubjectClick,
        )
    }
    ModerationHost(page.moderation, viewModel.moderation)

    if (page.composer.target != null) {
        ComposerSheet(
            title = stringResource(R.string.composer_status_title),
            sending = page.composer.sending,
            sent = page.composer.sent,
            failures = viewModel.composer.failures,
            onDismiss = viewModel.composer::close,
            onSend = loginGate.guard { draft: ComposerDraft, token: String -> viewModel.composer.send(draft, token) },
            initialText = page.composer.text,
            onChange = viewModel.composer::edit,
            maxLength = TIMELINE_TEXT_LIMIT,
        )
    }

    deleting?.let { id ->
        ConfirmDialog(
            target = id,
            confirmLabel = stringResource(R.string.delete),
            onConfirm = { timelineId -> loginGate.run { viewModel.delete(timelineId) } },
            onDismiss = { deleting = null },
            text = stringResource(R.string.delete_activity_confirm),
            destructive = true,
            confirmEnabled = !page.pending,
        )
    }
}


/**
 * 动态流（原型 `.feed`：上 4、左右 16、组间 16；组内分段项间 2）。
 *
 * 只出一个加载指示器：首屏是骨架，翻页是统一页脚。当前类别还一条都没列出、仍在往前找时也是骨架
 * （内容没到画占位，不在空页正中转圈）。
 */
@Composable
private fun FeedList(
    state: PagedList<TimelineItemBo>,
    filter: TimelineFilter,
    /** 作者名旁画不画「好友」小牌：好友动态里人人都是好友，不画（[TimelineMode.onlyFriends]）。 */
    markFriends: Boolean,
    /** 正在按类别往前找（一轮里连取多页，页脚一直是加载中）。 */
    scanning: Boolean,
    /** 按类别往前找了一大段没找到，停下等用户点「继续往前找」（[scanPaused]）。 */
    paused: Boolean,
    endText: String,
    emptyText: String,
    onUser: (String) -> Unit,
    onSubject: (Int) -> Unit,
    onOpen: (TimelineItemBo) -> Unit,
    onDelete: (Long) -> Unit,
    onLoadMore: () -> Unit,
    /** 列表为空时取失败的重试（整页错误）。 */
    onRetry: () -> Unit,
) {
    val shown = remember(state.items, filter) { state.items.filter(filter::accepts) }
    val userGroups = remember(shown) { groupConsecutiveByUser(mergeConsecutiveTimelineItems(shown)) }
    // 还没列出一条、也没有停下的理由（到底、出错、该停）：内容还在路上
    val searching = shown.isEmpty() && state.hasMore && !paused && state.error == null && state.loadMoreError == null
    val pausedText = if (paused) {
        pluralStringResource(R.plurals.timeline_scan_paused, state.items.size, state.items.size, stringResource(filter.labelRes))
    } else null
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // 底部让出发动态的 FAB
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = FabClearance),
    ) {
        if ((state.isLoading && state.items.isEmpty()) || searching) {
            repeat(SKELETON_GROUPS) { group ->
                item(key = "skeleton-head-$group") {
                    TimelineGroupHeaderSkeleton(markFriend = markFriends, modifier = Modifier.groupTop(group))
                }
                items(SKELETON_ROWS_PER_GROUP) { row ->
                    TimelineRowSkeleton(row, SKELETON_ROWS_PER_GROUP, modifier = Modifier.segmentTop(row))
                }
            }
            return@LazyColumn
        }
        state.error?.let { error ->
            item(key = "error") { RetryError(error, onRetry = onRetry, Modifier.fillMaxWidth()) }
            return@LazyColumn
        }
        userGroups.forEachIndexed { groupIndex, userGroup ->
            val head = userGroup.items.first().anchor
            item(key = "head-${head.id}") {
                TimelineGroupHeader(
                    user = userGroup.user,
                    uid = userGroup.uid,
                    createdAt = userGroup.latestTime,
                    count = userGroup.items.size,
                    markFriend = markFriends,
                    onUser = { onUser(userKeyOf(userGroup.user?.username, userGroup.uid)) },
                    modifier = Modifier.groupTop(groupIndex),
                )
            }
            itemsIndexed(userGroup.items, key = { _, group -> "row-${group.anchor.id}" }) { index, group ->
                val item = group.anchor
                TimelineRow(
                    group = group,
                    index = index,
                    count = userGroup.items.size,
                    onOpen = { onOpen(item) },
                    onDelete = if (item.isOwn) ({ onDelete(item.id) }) else null,
                    onSubject = onSubject,
                    modifier = Modifier.segmentTop(index),
                )
            }
        }
        if (shown.isEmpty() && !state.isLoading && !state.hasMore) {
            item(key = "empty") { EmptyLine(emptyText) }
        }
        loadMoreFooter(
            loadingMore = state.isLoadingMore || scanning,
            hasMore = state.hasMore,
            error = state.loadMoreError,
            endText = endText,
            hasItems = shown.isNotEmpty(),
            pausedText = pausedText,
            onLoadMore = onLoadMore,
        )
    }
}

/** 组与组之间 16（原型 `.feed{gap:16px}`），第一组贴顶。 */
private fun Modifier.groupTop(groupIndex: Int): Modifier = if (groupIndex == 0) this else padding(top = 16.dp)

/** 组内分段项之间 [ListItemDefaults.SegmentedGap]，第一项贴着作者头。 */
private fun Modifier.segmentTop(index: Int): Modifier = if (index == 0) this else padding(top = ListItemDefaults.SegmentedGap)
