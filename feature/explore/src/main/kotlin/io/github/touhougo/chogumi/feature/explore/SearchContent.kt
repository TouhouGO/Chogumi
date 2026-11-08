package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.availableSorts
import io.github.touhougo.chogumi.core.model.combinationTitle
import io.github.touhougo.chogumi.core.model.effectiveSort
import io.github.touhougo.chogumi.core.model.isActive
import io.github.touhougo.chogumi.core.model.isPinnable
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.loadMoreFooter
import io.github.touhougo.chogumi.core.ui.composables.AppDropdownMenu
import io.github.touhougo.chogumi.core.ui.composables.AppMenuItem
import io.github.touhougo.chogumi.core.ui.composables.ContainedSearchField
import io.github.touhougo.chogumi.core.ui.composables.ContentGrid
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.FabClearance
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.GridPadding
import io.github.touhougo.chogumi.core.ui.composables.PinnedRowFrame
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SelectionFabMenu
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVertical
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVerticalSkeleton
import io.github.touhougo.chogumi.core.ui.composables.TabPager
import io.github.touhougo.chogumi.core.ui.composables.iconOrAllRes
import io.github.touhougo.chogumi.core.ui.composables.labelOrAllRes
import io.github.touhougo.chogumi.core.ui.composables.rememberOrderedGridState
import io.github.touhougo.chogumi.core.ui.composables.rememberTabPagerState
import io.github.touhougo.chogumi.core.ui.composables.subjectTypeTabsOf
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 搜索整页：搜索栏、排序条、按排序左右翻页的结果。关键词搜索、分类浏览、标签浏览都是这一页。
 *
 * 排序条（[SearchFilterBar]）固定在搜索栏下，每个可选排序一页，经 [TabPager] 左右滑切换（与探索、进度页的分类同一套）；
 * 没有可选排序时（空态、有关键词）只有一页、不翻。每页唯一的滚动容器是一只 ContentGrid（[SearchResultsGrid]）。
 * 条目类型由右下角 FAB 菜单切换，浮在网格之上（网格底部留出 [FabClearance]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    state: SearchUiState,
    actions: SearchActions,
    onSubjectClick: (Int) -> Unit,
    onBack: () -> Unit,
    /** 今年（业务日的年份，[SearchViewModel.today]）：放送时间筛选的年份上限按它。 */
    currentYear: Int,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    val submitSearch = {
        keyboard?.hide()
        focus.clearFocus()
        actions.onSearch()
    }

    var typeMenuOpen by remember { mutableStateOf(false) }
    val applied = state.form.applied
    val hint = applied.searchHint()

    // 每个可选排序一页，顺序同排序条；选中哪一页的唯一真源是已提交条件的生效排序，
    // 排序条与左右翻页的同步在 core:ui 的 TabPager 里。可选排序随类型、标签变了就重建，换类型时不从旧页滑过去。
    val sorts = SearchSort.entries.filter { it in applied.availableSorts() }
    val pager = key(sorts) {
        rememberTabPagerState(
            selected = sorts.indexOf(applied.effectiveSort()).coerceAtLeast(0),
            pageCount = sorts.size.coerceAtLeast(1),
            onSelect = { actions.onSortChange(sorts[it]) },
        )
    }

    Scaffold(modifier = modifier) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        // 搜索栏与排序条吸在上面，结果网格滑到它们下面时下沿出柔边
        PinnedRowFrame(modifier = Modifier.fillMaxSize(), pinned = {
            // contained 搜索栏（core:ui ContainedSearchField，与进度页同一份外观）：
            // 前置位放返回（导航图标按钮，input.md:693），后置清除输入 + 筛选（不超过两个，input.md:694、790）。
            ContainedSearchField(
                query = state.form.draftQuery,
                onQueryChange = actions::onQueryChange,
                placeholder = hint.text,
                placeholderValue = hint.value,
                modifier = Modifier.padding(horizontal = GridPadding, vertical = 8.dp),
                fieldModifier = Modifier.testTag("SearchQuery"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                onClear = actions::onQueryClear,
                leading = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back_24), stringResource(R.string.back))
                    }
                },
                trailing = {
                    // 图钉并入搜索栏右侧：页面上不为它单开一行。
                    // 右侧图标恒不超过两个（input.md:694、790）——图钉只在「这组条件可固定」
                    // 或「已固定」时出现，两者都要求关键词为空（isPinnable），而清除按钮只在
                    // 关键词非空时出现，所以两者互斥，不可能同时挤出三个图标。
                    SearchPinAction(state = state, actions = actions)

                    val activeCount = state.form.applied.activeConditionCount()
                    val filterDescription = if (activeCount > 0) {
                        stringResource(R.string.search_filters_selected, activeCount)
                    } else {
                        stringResource(R.string.search_filters)
                    }
                    val onOpenFiltersClick = {
                        keyboard?.hide()
                        focus.clearFocus()
                        actions.onFiltersOpen()
                    }
                    // 筛选入口并入搜索栏右侧（依据 input.md:694、790 右侧最多两个图标）：
                    // 无生效条件时用 standard icon button；有生效条件时改用 filled icon button（替换 filled tonal），
                    // 原因：tonal 的 secondaryContainer 在 contained 搜索框底色（surfaceContainerHigh）上对比度不足 3:1
                    // （浅色 1.05:1，深色 1.47:1），buttons.md:187 要求容器与背景对比度 ≥3:1；
                    // filled icon button 的 primary 容器色对比度（浅色 5.21:1，深色 9.00:1）达标。
                    // 这是 my design decision (not in M3)：它打开面板，不是开关，按 buttons.md:312 不能用 toggle。
                    // 不加数量徽标（产品决定）：有无生效条件只由按钮样式区分，条件数由读屏念出。
                    if (activeCount > 0) {
                        FilledIconButton(
                            onClick = onOpenFiltersClick,
                            modifier = Modifier.testTag("SearchOpenFilters"),
                        ) {
                            Icon(painterResource(R.drawable.tune_24), contentDescription = filterDescription)
                        }
                    } else {
                        IconButton(
                            onClick = onOpenFiltersClick,
                            modifier = Modifier.testTag("SearchOpenFilters"),
                        ) {
                            Icon(painterResource(R.drawable.tune_24), contentDescription = filterDescription)
                        }
                    }
                },
            )

            SearchFilterBar(
                criteria = applied,
                sorts = sorts,
                shown = sorts.getOrNull(pager.shownPage),
                actions = actions,
                // 上 8dp：加上搜索栏自身下方 8dp 与按钮 48dp 触控区里的 4dp 余量，搜索栏到排序条视觉上 20dp，
                // 与排序条到海报（网格顶部 16 + 4）相等。数值是我的设计决定（not in M3）。
                modifier = Modifier.padding(start = GridPadding, top = 8.dp, end = GridPadding),
            )
        }) {
            TabPager(pager, Modifier.fillMaxSize(), key = { sorts.getOrNull(it)?.name ?: NO_SORT }) { page ->
                val sort = sorts.getOrNull(page)
                SearchResultsGrid(
                    state = if (sort == null || sort == applied.effectiveSort()) state else state.previewOf(sort),
                    actions = actions,
                    onSubjectClick = onSubjectClick,
                    modifier = Modifier.testTag("SearchGrid_${sort?.name ?: NO_SORT}"),
                )
            }
        }

            // 条目类型：右下角 FAB 菜单（core:ui SelectionFabMenu，与进度页同一份）。收起时显示当前类型图标。
            SelectionFabMenu(
                expanded = typeMenuOpen,
                onExpandedChange = { open ->
                    if (open) {
                        keyboard?.hide()
                        focus.clearFocus()
                    }
                    typeMenuOpen = open
                },
                options = subjectTypeTabsOf(includeAll = true),
                selected = state.form.applied.subjectType,
                iconRes = { it.iconOrAllRes() },
                label = { stringResource(it.labelOrAllRes()) },
                onSelect = actions::onFilterTypeChange,
                collapsedDescription = stringResource(
                    R.string.search_type_fab,
                    stringResource(state.form.applied.subjectType.labelOrAllRes()),
                ),
                itemTestTag = { "ExploreSearchType-${it?.name ?: "ALL"}" },
            )
        }
    }

    // 改名对话框：只在 state.renaming 时在场（开关唯一来自 SearchViewModel）。
    if (state.renaming) {
        val criteria = state.form.applied
        PresetRenameDialog(
            initial = state.pinned?.name ?: state.form.title ?: defaultTitleFor(criteria),
            actions = actions,
        )
    }

    // 面板宿主：草稿非 null 即打开（唯一开关）。遮罩 / 下滑 / 返回关闭面板 → 丢弃草稿；
    // 「保存」先让面板滑下去（hideRequested），滑完的那一次关闭才提交，草稿不会先被丢掉。
    val draft = state.form.filterDraft
    if (draft != null) {
        var saving by remember { mutableStateOf(false) }
        SearchFilterSheet(
            subjectType = state.form.applied.subjectType,
            draft = draft,
            actions = actions,
            panelQuery = state.panelQuery,
            catalogSuggestions = state.catalogSuggestions,
            currentYear = currentYear,
            hideRequested = saving,
            onDismiss = { if (saving) actions.onFiltersSave() else actions.onFiltersDismiss() },
            onSave = { saving = true },
        )
    }
}

/**
 * 一个排序的结果页：校验错误、历史、结果 / 骨架 / 空态 / 错误、翻页页脚，全是网格项（文本类占满一行）。
 */
@Composable
private fun SearchResultsGrid(
    state: SearchUiState,
    actions: SearchActions,
    onSubjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchEndText = stringResource(R.string.load_more_end_search)

    ContentGrid(
        cell = GridCell.Poster,
        modifier = modifier.fillMaxSize(),
        // 新一轮搜索后列表回到顶部：只看 generation（不看草稿），详情返回 / 旋转时 generation 不变，不跳。
        state = rememberOrderedGridState(state.generation),
        // 底部为类型 FAB 让出空间（测量依据见 FabClearance）。顶部 16dp 见排序条处的说明。
        top = 16.dp,
        bottom = FabClearance,
        rowSpacing = 16.dp,
    ) {
        // 校验错误就地显示（随表单下一次变动更新）。
        if (state.validationErrors.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "validation") {
                Column(Modifier.fillMaxWidth()) {
                    state.validationErrors.forEach { err ->
                        Text(
                            stringResource(err.reason.messageRes()),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        val showingHistory = !state.form.applied.isActive() && state.history.isNotEmpty()
        if (showingHistory) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "history-header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.search_history),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = actions::onClearHistory) {
                        Text(stringResource(R.string.clear_history))
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "history-row") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
                ) {
                    items(state.history, key = { it }) { keyword ->
                        AssistChip(
                            onClick = { actions.onHistoryClick(keyword) },
                            label = { Text(keyword) },
                        )
                    }
                }
            }
        }

        val results = state.results
        val error = results.error
        if (error != null) {
            // 首屏搜索失败：就地报错 + 重试（与其它列表页同一个错误块），不是「没有结果」。
            item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                RetryError(error, onRetry = actions::onRetry, Modifier.fillMaxWidth().testTag("SearchError"))
            }
        } else if (results.isLoading && results.items.isEmpty()) {
            items(12, key = { "skeleton-$it" }) {
                SubjectCardVerticalSkeleton()
            }
        } else {
            items(results.items, key = { it.id }) { subject ->
                SubjectCardVertical(subject, onSubjectClick)
            }

            val showEmptyGuide = !state.form.applied.isActive() && !showingHistory
            val showEmptyResult = state.form.applied.isActive() && !showingHistory && results.isConfirmedEmpty

            if (showEmptyGuide || showEmptyResult) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    // 未开始搜索时给出怎么开始，不重复搜索框里的提示文字。
                    EmptyLine(stringResource(if (showEmptyResult) R.string.empty_search else R.string.search_empty_guide))
                }
            }

            loadMoreFooter(results, actions::loadMore, searchEndText)
        }
    }
}

/**
 * 左右翻页时邻页先画成的样子：条件换成那个排序，结果还没取，是骨架。
 * 翻页落定后才真正换排序、发请求（[rememberTabPagerState] 的 onSelect）。
 */
private fun SearchUiState.previewOf(sort: SearchSort): SearchUiState = copy(
    form = form.copy(applied = form.applied.copy(sort = sort)),
    validationErrors = emptyList(),
    results = PagedList(isLoading = true),
)

/** 没有可选排序时唯一那一页的页键与测试标签。 */
private const val NO_SORT = "NONE"

/** 货架名长度上限。**my design decision (not in M3)**：原型 `maxlength="20"`；M3 未规定。 */
internal const val PRESET_TITLE_MAX_LENGTH = 20

/**
 * 搜索栏右侧的图钉：把「这组条件放上探索页成一条货架」收进搜索栏，页面上不为它单开一行。
 *
 * - 这组条件可固定（[isPinnable]：关键词为空、具体类型且至少一个条件）但探索页上还没有 → outlined 图钉，点一下放上去，
 *   名字取 [defaultTitleFor]（`combinationTitle` 连出来的「A × B」）。
 * - 探索页上已有（[SearchUiState.pinned]）→ filled 图钉（buttons.md:219 选中态），点开菜单：重命名 / 从探索页移除。
 *   两个动作共用一个图标位，右侧图标数因此不会超过两个（input.md:694、790）。
 * - 两者都不成立 → 不渲染，一个像素都不占。
 *
 * 图钉本身是 toggle icon button（buttons.md:205「binary action toggled on/off，如 favorite / bookmark」）。
 */
@Composable
private fun SearchPinAction(state: SearchUiState, actions: SearchActions) {
    val criteria = state.form.applied
    val pinned = state.pinned
    if (pinned == null && !criteria.isPinnable()) return

    var menuOpen by remember(pinned) { mutableStateOf(false) }
    // 已固定时用存下来的名字，否则用条件连出来的默认名。
    val title = pinned?.name ?: state.form.title ?: defaultTitleFor(criteria)

    Box {
        val description = stringResource(if (pinned != null) R.string.search_unpin else R.string.search_pin)
        IconButton(
            onClick = { if (pinned != null) menuOpen = true else actions.onPinToggle(title) },
            modifier = Modifier.testTag("SearchPresetPin"),
        ) {
            Icon(
                painter = painterResource(if (pinned != null) R.drawable.push_pin_filled_24 else R.drawable.push_pin_24),
                contentDescription = description,
            )
        }
        AppDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            AppMenuItem(stringResource(R.string.search_rename_preset), onDismiss = { menuOpen = false }, Modifier.testTag("SearchPresetRename")) {
                actions.onRenameStart()
            }
            AppMenuItem(stringResource(R.string.search_unpin), onDismiss = { menuOpen = false }, Modifier.testTag("SearchPresetUnpin")) {
                actions.onPinToggle(title)
            }
        }
    }
}

/**
 * 改名对话框：组合已经存下来了，改的只是它的名字，所以用对话框而不是页面上的常驻输入行。
 * 空串由 [SearchActions.onRenameSubmit] 拦下不保存。
 */
@Composable
private fun PresetRenameDialog(initial: String, actions: SearchActions) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = actions::onRenameCancel,
        title = { Text(stringResource(R.string.search_rename_preset)) },
        text = {
            FormTextField(
                value = value,
                onValueChange = { if (it.length <= PRESET_TITLE_MAX_LENGTH) value = it },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.onRenameSubmit(value.trim()) }),
                inDialog = true,
                modifier = Modifier.focusRequester(focusRequester).testTag("SearchPresetTitleEditor"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { actions.onRenameSubmit(value.trim()) },
                modifier = Modifier.testTag("SearchPresetRenameConfirm"),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = actions::onRenameCancel) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * 没起名字时的默认组合名：与探索页组合货架同一条规则（[combinationTitleSpec]，「A × B」，超过 3 个加「等」）。
 *
 * 条件文字用 `conditionText` 取——条件的读法只此一份，这里不另拼。
 * 只有图钉可用（至少一个条件）时才走到这里。
 */
@Composable
internal fun defaultTitleFor(criteria: SearchCriteria): String =
    combinationTitleSpec(combinationTitle(criteria.activeConditions().map { criteria.conditionText(it) })).text()

