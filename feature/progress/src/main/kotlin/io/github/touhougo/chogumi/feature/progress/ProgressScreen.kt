package io.github.touhougo.chogumi.feature.progress

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.CollectionSheetHost
import io.github.touhougo.chogumi.core.ui.common.navigation.ScrollToTopOnTabReselect
import io.github.touhougo.chogumi.core.ui.composables.ChogumiPullToRefreshBox
import io.github.touhougo.chogumi.core.ui.composables.CollapsibleHeader
import io.github.touhougo.chogumi.core.ui.composables.CollectionListItem
import io.github.touhougo.chogumi.core.ui.composables.CollectionListItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.ContainedSearchField
import io.github.touhougo.chogumi.core.ui.composables.CountBadgedIcon
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SelectionFabMenu
import io.github.touhougo.chogumi.core.ui.composables.SubjectTypeTabs
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.iconRes
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.composables.rememberOrderedGridState
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.theme.LocalWindowWidthSizeClass
import org.koin.compose.viewmodel.koinViewModel

/**
 * 进度页：个人收藏管理。
 * 主分类用指示条 tab，收藏状态收敛为右下角 FAB 菜单；列表复用 [CollectionListItem] 与首页共享同一收藏真源，
 * 点一行进详情、长按打开收藏面板（[CollectionSheetHost]）。
 * 搜索框尾部是筛选入口（[ProgressFilterSheet]，带已选几项的数字徽标）与排序菜单（[ProgressSortAction]）。
 *
 * 搜索栏与分类栏的滚动行为交给 [CollapsibleHeader]（core:ui 唯一实现）：搜索栏随网格滚走、
 * 一开始往上滚就浮现，分类栏紧贴其下、收起后固定在最上面；正在打字时不收起（规则在组件里）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    onSubjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProgressViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var statusFiltering by remember { mutableStateOf(false) }

    // 写入只在失败时提示（本地已回滚）；成功不打扰。
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)

    // 选中哪一页是唯一真源（state.selectedSubjectType），左右翻页与分类栏的同步在 core:ui 的 TabPager 里。
    // 默认分类读到（opened）时翻页状态重建、直接停在那一页，不从「全部」滑过去。
    val pager = key(state.opened) {
        rememberTabPagerState(
            selected = state.selectedSubjectType,
            pageCount = PROGRESS_TYPES.size,
            onSelect = viewModel::onSelectSubjectType,
        )
    }

    val tablet = LocalWindowWidthSizeClass.current != WindowWidthSizeClass.Compact
    // 当前页签的条目类型。「全部」页签没有单一类型，兜底取动画（最主要的内容）：
    // 收藏状态 FAB 的动词与加载骨架（要不要画进度、留不留 EP 槽位）都按它，只此一处。
    val listSubjectType = listSubjectTypeOf(state.selectedSubjectType)

    Box(modifier = modifier.fillMaxSize()) {
        CollapsibleHeader(
            // 改了条件、某页重新排序回到顶部时，搜索栏一并回到全开，否则会在顶部留下一条收起的搜索栏；
            // 单纯翻页不动它（翻回去的页还停在原来的位置）。
            resetKey = state.reorderCount,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            collapsing = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContainedSearchField(
                        query = state.query,
                        onQueryChange = viewModel::onQueryChange,
                        placeholder = stringResource(R.string.progress_search_hint),
                        modifier = Modifier.weight(1f),
                        trailing = {
                            // 有筛选时图标上是已选几项的数字徽标；读屏念「筛选，已选 N 项」
                            val active = state.activeFilterCount
                            IconButton(onClick = viewModel::openFilters) {
                                CountBadgedIcon(active) {
                                    Icon(
                                        painter = painterResource(R.drawable.tune_24),
                                        contentDescription = if (active > 0) {
                                            pluralStringResource(R.plurals.progress_filter_entry_active, active, active)
                                        } else {
                                            stringResource(R.string.progress_filter_entry)
                                        },
                                    )
                                }
                            }
                            // 搜索框右侧至多两个图标：有输入时清除按钮占一个位，排序让位给筛选。
                            if (state.query.isEmpty()) {
                                ProgressSortAction(
                                    type = PROGRESS_TYPES[state.selectedSubjectType],
                                    order = state.pageOrder,
                                    onSelectSort = viewModel::onSelectSort,
                                    onSelectDirection = viewModel::onSelectDirection,
                                )
                            }
                        },
                    )
                }
            },
            pinned = {
                // tab 文字保持 baseline（未加 emphasized），依据 typography.md:41「不是替代字阶」
                // 与 typography.md:66-69「用于 already carries weight 的文字」：
                // 6 个 tab 全加粗会失去视觉重点，选中态应由颜色与 active indicator 承担。
                SubjectTypeTabs(
                    types = PROGRESS_TYPES,
                    pager = pager,
                    onSelect = { viewModel.onSelectSubjectType(PROGRESS_TYPES.indexOf(it)) },
                )
            },
        ) {
            ChogumiPullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 每个类型一页：各自的列表、顺序与滚动位置；邻页按本地已有的收藏画，落定后才同步那一类
                TabPager(pager, Modifier.fillMaxSize()) { page ->
                    ProgressPageContent(
                        state = state,
                        page = page,
                        tablet = tablet,
                        viewModel = viewModel,
                        onSubjectClick = onSubjectClick,
                    )
                }
            }
        }

        // 收藏状态筛选：与搜索页共用 core:ui 的 SelectionFabMenu（外观、形变与选中强调只在那里定义）。
        SelectionFabMenu(
            expanded = statusFiltering,
            onExpandedChange = { statusFiltering = it },
            options = CollectionType.choices,
            selected = state.collectionStatus,
            iconRes = { it.iconRes() },
            // 按当前列表的条目类型分动词（「全部」页签用动画组动词兜底）：
            // FAB 是筛选器，动词跟当前分类走，不改变任何写入语义。
            label = { status ->
                stringResource(status.labelResFor(listSubjectType))
            },
            onSelect = viewModel::onSelectStatus,
            collapsedDescription = stringResource(
                R.string.progress_status_fab,
                stringResource(state.collectionStatus.labelResFor(listSubjectType)),
            ),
        )

        // 收藏面板开着时提示条画在面板上（这里的在面板下面，看不见）：同一个提示条状态，只换画的位置
        if (!state.collectionSheet.isShown) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = FabClearance),
            )
        }
    }

    if (state.filtersOpen) {
        ProgressFilterSheet(
            session = state.filterPanelSession,
            subjectType = PROGRESS_TYPES[state.selectedSubjectType],
            categoryGroups = state.categoryGroups,
            selectedCategories = state.selectedCategories,
            tags = state.panelTags,
            selectedTags = state.selectedTags,
            resultCount = state.collections.size,
            onToggleCategory = viewModel::onToggleCategory,
            onToggleTag = viewModel::onToggleTag,
            onClear = viewModel::clearPanelFilters,
            onDismiss = viewModel::closeFilters,
        )
    }

    // 长按一行：与详情页 FAB 同一个收藏面板
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

/** 第 [page] 页的条目类型：「全部」页没有单一类型，兜底取动画（最主要的内容）。收藏状态 FAB 的动词与骨架都按它，只此一处。 */
private fun listSubjectTypeOf(page: Int): SubjectType = PROGRESS_TYPES[page] ?: SubjectType.ANIME

/**
 * 一页：形态只由 [ProgressUiState.shapeOf] 决定（骨架 / 没有条目 / 列表）。
 * 网格位置每页一份，跟着这一页的重排次数（[ProgressUiState.pageEpochs]）走：重排过就回到顶部，离屏时重排的也一样。
 */
@Composable
private fun ProgressPageContent(
    state: ProgressUiState,
    page: Int,
    tablet: Boolean,
    viewModel: ProgressViewModel,
    onSubjectClick: (Int) -> Unit,
) {
    when (state.shapeOf(page)) {
        ProgressPageShape.LOADING -> LazyVerticalGrid(
            columns = GridCells.Fixed(if (tablet) 2 else 1),
            // 底部为 FAB 让出空间（测量依据见 FabClearance）
            contentPadding = PaddingValues(bottom = FabClearance),
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
        ) {
            items(6) {
                CollectionListItemSkeleton(listSubjectTypeOf(page), state.collectionStatus)
            }
        }

        ProgressPageShape.EMPTY -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            EmptyLine(stringResource(R.string.empty_progress))
        }

        ProgressPageShape.ERROR -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            state.syncError?.let { failure -> RetryError(failure, onRetry = viewModel::refresh, Modifier.fillMaxWidth()) }
        }

        ProgressPageShape.LIST -> {
            val gridState = rememberOrderedGridState(state.pageEpochs[page].toLong())
            ScrollToTopOnTabReselect(gridState)
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (tablet) 2 else 1),
                // 底部为 FAB 让出空间（测量依据见 FabClearance）
                contentPadding = PaddingValues(bottom = FabClearance),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.pages[page], key = { it.subjectId }) { item ->
                    CollectionListItem(
                        item = item,
                        onClick = { onSubjectClick(item.subjectId) },
                        onEdit = { viewModel.openCollectionSheet(item.subjectId) },
                        progressCount = viewModel.progressCount(item, state),
                        epButtonState = viewModel.epButtonState(item, state),
                        ongoing = viewModel.isOngoing(item, state),
                        onNextEpisode = { viewModel.onEpButtonTap(item) },
                        onMarkFinished = { viewModel.markDone(item) },
                        pendingVisual = state.pendingVisuals[item.subjectId],
                    )
                }
            }
        }
    }
}
