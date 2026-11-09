package io.github.touhougo.chogumi.feature.explore

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.ContentDomains
import io.github.touhougo.chogumi.core.model.ExploreEntry
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTile
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkHost
import io.github.touhougo.chogumi.core.ui.common.navigation.PageTransitionKind
import io.github.touhougo.chogumi.core.ui.common.navigation.ScrollToTopOnTabReselect
import io.github.touhougo.chogumi.core.ui.common.navigation.chogumiTransition
import io.github.touhougo.chogumi.core.ui.composables.ArrangeBar
import io.github.touhougo.chogumi.core.ui.composables.ChogumiPullToRefreshBox
import io.github.touhougo.chogumi.core.ui.composables.CollapsibleHeader
import io.github.touhougo.chogumi.core.ui.composables.ContainedSearchEntry
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import io.github.touhougo.chogumi.core.ui.composables.SubjectTypeTabs
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.pressedCorner
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import java.time.LocalDate
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ExploreScreen(
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onOpenSearch: (criteria: SearchCriteria, title: String?, openFilters: Boolean) -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ExploreViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)
    // 货架与热门大图上的海报都能长按标记
    PosterMarkHost(
        state = state.posterMarks,
        controller = viewModel.posterMarks,
        isLoggedIn = isLoggedIn,
        onLogin = onLogin,
        snackbar = snackbarHostState,
        modifier = modifier.fillMaxSize(),
    ) {
        ExploreContent(
            state = state,
            onSelectType = viewModel::selectType,
            onRefresh = viewModel::refresh,
            onTilesShown = viewModel::showTiles,
            onSubjectClick = onSubjectClick,
            onOpenSearch = onOpenSearch,
            onOpenCalendar = onOpenCalendar,
            arrangeActions = viewModel.arranger,
        )
        // 整理时返回先退出整理（只在整理时拦）；面板开着时面板自己的返回在前
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = state.arrange.active,
            onBackCompleted = viewModel.arranger::exit,
        )
        ArrangeSheets(state, viewModel.arranger, currentYear = state.today.year)
        // 收藏面板开着时提示条画在面板上
        if (!state.posterMarks.sheet.isShown) SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun ExploreContent(
    state: ExploreUiState,
    onSelectType: (SubjectType) -> Unit,
    onRefresh: () -> Unit,
    /** 某个分类的一条方块货架里这几块出现在屏上（去取方块要的数据）。 */
    onTilesShown: (SubjectType, String, List<ExploreTile>) -> Unit,
    onSubjectClick: (Int) -> Unit,
    onOpenSearch: (criteria: SearchCriteria, title: String?, openFilters: Boolean) -> Unit,
    onOpenCalendar: () -> Unit,
    /** 整理模式：长按货架标题进入、整理列表里的排序移除编辑。 */
    arrangeActions: ArrangeListActions,
    modifier: Modifier = Modifier,
) {
    // 页序与分类栏同一份：五个内容分类
    val types = ContentDomains
    // 选中哪个分类是唯一真源（ExploreUiState.selectedType），左右翻页与分类栏的同步在 core:ui 的 TabPager 里
    val pager = rememberTabPagerState(
        selected = types.indexOf(state.selectedType),
        pageCount = types.size,
        onSelect = { onSelectType(types[it]) },
    )

    val pageMotion = LocalAppMotion.current.page

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        // 搜索栏随内容滚走、一开始往上滚就浮现；分类标签栏紧贴其下、收起后固定在最上面
        // （唯一实现在 core:ui 的 CollapsibleHeader；M3 依据见该组件文档）。
        // 入口行仍是列表项，随内容滚走——入口是「去别处」的跳转，滚走不影响当前页的使用。
        // 整理时搜索栏的位置换成整理的顶栏（标题、添加货架、完成），分类栏照旧：整理时也能切分类
        CollapsibleHeader(
            collapsing = {
                AnimatedContent(
                    targetState = state.arrange.active,
                    transitionSpec = { chogumiTransition(PageTransitionKind.TopLevel, pageMotion, 1).using(null) },
                    label = "explore_arrange_bar",
                ) { arranging ->
                    if (arranging) {
                        ArrangeBar(onAdd = arrangeActions::openAdding, onDone = arrangeActions::exit)
                    } else {
                        ContainedSearchEntry(
                            placeholder = stringResource(R.string.search_hint),
                            // 在哪个分类点进来，搜索页就默认在哪个分类（类型选择与提示语都跟着它）。
                            onClick = { onOpenSearch(SearchCriteria(subjectType = state.selectedType), null, false) },
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp)
                                .testTag("explore_search_bar"),
                        )
                    }
                }
            },
            pinned = {
                SubjectTypeTabs(
                    types = types,
                    pager = pager,
                    onSelect = { it?.let(onSelectType) },
                    modifier = Modifier.testTag("explore_type_tabs"),
                )
            },
            modifier = Modifier.weight(1f),
        ) {
            ChogumiPullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 每个分类一页：各自的列表、各自的滚动位置；没加载过的分类翻过去先是骨架，落定后才去取
                // 每页只收自己那份版面、内容与日子：刷新指示、长按标记这些与货架无关的变化不让列表重跑
                TabPager(pager, Modifier.fillMaxSize(), key = { types[it].name }) { page ->
                    val type = types[page]
                    val savedPages = rememberSaveableStateHolder()
                    AnimatedContent(
                        targetState = state.arrange.active,
                        transitionSpec = { chogumiTransition(PageTransitionKind.TopLevel, pageMotion, 1).using(null) },
                        modifier = Modifier.fillMaxSize(),
                        label = "explore_arrange_page",
                    ) { arranging ->
                        savedPages.SaveableStateProvider(arranging) {
                            if (arranging) {
                                ArrangeList(
                                    type = type,
                                    layout = state.layoutOf(type),
                                    typeContent = state.contentOf(type),
                                    actions = arrangeActions,
                                    modifier = Modifier.testTag("explore_arrange_${type.name}"),
                                )
                            } else {
                                ExploreTypePage(
                                    type = type,
                                    layout = state.layoutOf(type),
                                    typeContent = state.contentOf(type),
                                    today = state.today,
                                    onTilesShown = onTilesShown,
                                    onSubjectClick = onSubjectClick,
                                    onOpenSearch = onOpenSearch,
                                    onOpenCalendar = onOpenCalendar,
                                    onArrange = arrangeActions::enter,
                                    modifier = Modifier.testTag("explore_list_${type.name}"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 作品货架（今日放送、排行榜、预设与自建的）同是一条 [WorksShelf]，列表滚动时互相复用槽位；入口行、热门大图、方块货架各自一类，
 * 不拿货架的槽位去拼它们。
 */
private const val WORKS_SHELF = "works_shelf"

/**
 * 一个分类的一页：入口行 + 货架，按这个分类自己的版面（[ExploreUiState.layoutOf]）与内容（[ExploreUiState.contentOf]）；
 * [today] 给今日放送的副标题。长按货架标题进整理（[onArrange]）。
 */
@Composable
private fun ExploreTypePage(
    type: SubjectType,
    layout: ExploreLayout,
    typeContent: ExploreTypeContent,
    today: LocalDate,
    onTilesShown: (SubjectType, String, List<ExploreTile>) -> Unit,
    onSubjectClick: (Int) -> Unit,
    onOpenSearch: (criteria: SearchCriteria, title: String?, openFilters: Boolean) -> Unit,
    onOpenCalendar: () -> Unit,
    onArrange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    ScrollToTopOnTabReselect(listState)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        // 固定入口行：随内容滚走。入口为「去别处」的跳转，滚走不影响当前页的使用。
        item(key = "explore_entries", contentType = "entries") {
            ExploreEntriesRow(
                entries = layout.entries,
                onOpenSearch = onOpenSearch,
                onOpenCalendar = onOpenCalendar,
                modifier = Modifier.testTag("explore_entries_row"),
            )
        }

        layout.shelves.forEach { shelf ->
            when (shelf) {
                is ExploreShelf.Trending -> {
                    item(key = "trending_$type", contentType = "trending") {
                        val titleText = exploreTitleText(shelf.title)
                        val content = typeContent.shelf(shelf.id)
                        val heroItems = content.valueOrNull().orEmpty()
                        if (content.shelfShown()) {
                            Column(Modifier.testTag("explore_shelf_trending")) {
                                ShelfHeader(
                                    title = titleText,
                                    onArrange = onArrange,
                                    onArrowClick = {
                                        onOpenSearch(shelf.criteria, titleText, false)
                                    },
                                )
                                // 骨架与轮播内容之间快速淡入，不硬切（M3 transitions.md:43）。
                                SkeletonCrossfade(
                                    loading = heroItems.isEmpty(),
                                    skeleton = { TrendingHeroSkeleton() },
                                ) {
                                    TrendingHeroCarousel(
                                        items = heroItems,
                                        onSubjectClick = onSubjectClick,
                                    )
                                }
                            }
                        }
                    }
                }

                is ExploreShelf.Works -> {
                    item(key = "works_${shelf.id}_$type", contentType = WORKS_SHELF) {
                        WorksShelf(
                            shelf = shelf,
                            typeContent = typeContent,
                            today = today,
                            onSubjectClick = onSubjectClick,
                            onOpenSearch = onOpenSearch,
                            onOpenCalendar = onOpenCalendar,
                            onArrange = onArrange,
                        )
                    }
                }

                is ExploreShelf.Tiles -> {
                    item(key = "tiles_${shelf.id}_$type", contentType = "tiles") {
                        TilesShelf(
                            shelf = shelf,
                            typeContent = typeContent,
                            onShown = { tiles -> onTilesShown(type, shelf.id, tiles) },
                            onSubjectClick = onSubjectClick,
                            onOpenSearch = onOpenSearch,
                            onArrange = onArrange,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 固定入口行：一行等分、**不横滑**（原型第 128–135 行 `.entries`）。
 *
 * 入口最多 4 个，等分铺满正好；横滑会把最后一个切掉半截，看起来像没做完。
 * 每格至少 72dp 高（大字号下标签折行、整行等高长高）、圆角 16dp、`surfaceContainer`，图标在上 24dp 用 primary 色，
 * 标签在下。按下圆角 16 → 28（原型第 133 行）。
 */
@Composable
internal fun ExploreEntriesRow(
    entries: List<ExploreEntry>,
    onOpenSearch: (criteria: SearchCriteria, title: String?, openFilters: Boolean) -> Unit,
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    ) {
        entries.forEach { entry ->
            val titleText = exploreTitleText(entry.title)
            ExploreEntryTile(
                title = titleText,
                iconRes = exploreEntryIcon(entry.title),
                onClick = { openExploreEntry(entry, titleText, onOpenSearch, onOpenCalendar) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 入口图标的唯一映射。 */
@DrawableRes
private fun exploreEntryIcon(title: ExploreTitle): Int = when (title) {
    is ExploreTitle.DailyCalendar -> R.drawable.calendar_month_24
    is ExploreTitle.Filters -> R.drawable.tune_24
    is ExploreTitle.Ranking -> R.drawable.bar_chart_24
    else -> R.drawable.auto_awesome_24
}

@Composable
private fun ExploreEntryTile(
    title: String,
    @DrawableRes iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val corner by pressedCorner(interactionSource, resting = 16.dp, pressed = 28.dp)

    PressArea(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.surfaceContainer,
        interactionSource = interactionSource,
        modifier = modifier.heightIn(min = 72.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
