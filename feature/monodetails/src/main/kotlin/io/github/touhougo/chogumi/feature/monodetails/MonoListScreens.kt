package io.github.touhougo.chogumi.feature.monodetails

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.occurrenceKeys
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.MonoWorkFacet
import io.github.touhougo.chogumi.core.model.MonoWorkOption
import io.github.touhougo.chogumi.core.model.MonoWorkSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.WORK_MIN_VOTES
import io.github.touhougo.chogumi.core.model.defaultMonoWorkSort
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.matches
import io.github.touhougo.chogumi.core.model.monoWorkFilterOptions
import io.github.touhougo.chogumi.core.model.monoWorkSortOptions
import io.github.touhougo.chogumi.core.model.sortMonoWorks
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.PagedListPage
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.ConnectedButtonGroupSkeleton
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.ListPageSkeleton
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import io.github.touhougo.chogumi.core.ui.composables.SortMenuAction
import io.github.touhougo.chogumi.core.ui.composables.iconOrAllRes
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.feature.monodetails.ui.MonoEntityRow
import io.github.touhougo.chogumi.feature.monodetails.ui.MonoEntityRowSkeleton
import io.github.touhougo.chogumi.feature.monodetails.ui.MonoRelationCard
import io.github.touhougo.chogumi.feature.monodetails.ui.MonoWorkRowSkeleton
import io.github.touhougo.chogumi.feature.monodetails.ui.monoWorkItems
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** 作品子页列表的上下留白：骨架与真实列表共用。 */
private val WorksListPadding = PaddingValues(bottom = 24.dp)

/**
 * 「全部」作品子页（原型 `worksScreen`、截图 32）：顶栏只写「参与作品」、右侧排序；下面两排筛选
 * （作品类型 × 人物的职位 / 角色的出场，同网页，各带计数，只有一个选项的那排不画）：类型是连通按钮组
 * [SubjectTypeFilterGroup]，职位 / 出场是 chip 行 [FilterChipRow]；再下面是与详情页同一个作品列表。
 * 筛选项来自 [monoWorkFilterOptions]，排序走 [sortMonoWorks]（与详情页的前 5 部同一个函数）。
 */
@Composable
fun MonoWorksScreen(
    monoType: MonoType,
    id: Int,
    onBack: () -> Unit,
    onSubjectClick: (Int) -> Unit,
) {
    val viewModel: MonoWorksViewModel = koinViewModel { parametersOf(monoType, id) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val publicRatings = LocalAppFeatures.current.publicRatings
    val sort = state.requestedSort ?: defaultMonoWorkSort(monoType, publicRatings)
    val sortOptions = monoWorkSortOptions(publicRatings)
    val works = state.works.valueOrNull().orEmpty()
    val options = remember(works, monoType, state.filter) { monoWorkFilterOptions(monoType, works, state.filter) }
    val shown = remember(works, state.filter, sort, publicRatings) {
        sortMonoWorks(works.filter(state.filter::matches), sort, publicRatings)
    }
    PageScaffold(
        // 顶栏只写标题，不带数量（产品决定）：数量看筛选里各项的计数。
        title = stringResource(monoType.worksTitleRes()),
        onBack = onBack,
        // 排序放顶栏右侧：列表上方没有数量行，排序独占一行太空
        actions = {
            if (sortOptions.size > 1) {
                SortMenuAction(sort = sort, options = sortOptions, label = { stringResource(it.labelRes()) }, onSelect = viewModel::selectSort)
            }
        },
    ) { padding ->
        val section = sectionStateOf(state.works)
        // 首屏骨架：与作品行同一组件的占位，不整屏转圈
        SkeletonCrossfade(
            loading = section == SectionState.LOADING,
            modifier = Modifier.fillMaxSize().padding(padding),
            skeleton = {
                ListPageSkeleton(contentPadding = WorksListPadding, header = { TypeFilterSkeleton() }) { index, count ->
                    MonoWorkRowSkeleton(index, count, Modifier.fillMaxWidth().padding(horizontal = ChogumiDimens.ScreenPadding))
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                when (section) {
                    SectionState.LOADING -> Unit // 首屏加载由外层骨架画
                    SectionState.ERROR -> state.works.failureOrNull()?.let { RetryError(it, onRetry = viewModel::retry, Modifier.align(Alignment.Center).fillMaxWidth()) }
                    SectionState.EMPTY -> EmptyLine(stringResource(R.string.no_content), Modifier.align(Alignment.Center))
                    SectionState.CONTENT -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = WorksListPadding,
                    ) {
                        if (options.types.size > 1) {
                            item(key = "filter-types") {
                                SubjectTypeFilterGroup(
                                    options = options.types,
                                    selected = state.filter.type,
                                    onSelect = { viewModel.selectFilter(state.filter.copy(type = it)) },
                                )
                            }
                        }
                        if (options.facets.size > 1) {
                            item(key = "filter-facets") {
                                FilterChipRow(
                                    options = options.facets,
                                    selected = state.filter.facet,
                                    name = { facet -> facetLabel(facet) },
                                    onSelect = { viewModel.selectFilter(state.filter.copy(facet = it)) },
                                )
                            }
                        }
                        monoWorkItems(works = shown, onWorkClick = onSubjectClick, horizontalPadding = ChogumiDimens.ScreenPadding)
                        item(key = "works-bottom") { Spacer(Modifier.height(8.dp)) }
                        if (sort == MonoWorkSort.RATING && publicRatings) {
                            item(key = "hint") {
                                Text(
                                    text = stringResource(R.string.mono_sort_rating_hint, WORK_MIN_VOTES),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
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
 * 作品类型一排（作品子页与出演角色子页共用）：全仓的单选连通按钮组 [ConnectedChoiceGroup]（主页分区那一排同款大按钮），
 * 「全部」在前，每格「类型图标 + 类型名 计数」；一行放不下类型名时整排改画「图标 + 计数」，读屏始终念完整的「动画 42」。
 * 「全部」只写「全部」不带数：出演角色的类型计数各自独立（同一角色可在几种类型里出现），相加不等于总数。
 */
@Composable
private fun SubjectTypeFilterGroup(
    options: List<MonoWorkOption<SubjectType>>,
    selected: SubjectType?,
    onSelect: (SubjectType?) -> Unit,
) {
    val values: List<SubjectType?> = listOf<SubjectType?>(null) + options.map { it.value }
    ConnectedChoiceGroup(
        options = values,
        selected = selected,
        label = { type ->
            type?.let { countedLabel(stringResource(it.labelRes()), options, it) } ?: stringResource(R.string.all)
        },
        shortLabel = { type -> type?.let { t -> options.first { it.value == t }.count.toString() } ?: stringResource(R.string.all) },
        icon = { type, selected -> type.iconOrAllRes(selected) },
        // 计数为 0（被另一排的选择排除）的类型收起，再有了就展开回原位：走按钮组的收放动画，同搜索排序的可用项增减。
        visible = { type -> type == null || type == selected || options.first { it.value == type }.count > 0 },
        onSelect = onSelect,
        modifier = Modifier.padding(TypeFilterPadding),
    )
}

/**
 * 类型筛选行的占位（骨架用）：按常见的「全部 + 两类」画三格。只有一类时真实页面不出这一行，内容往上收，不会往下顶。
 */
@Composable
private fun TypeFilterSkeleton() {
    ConnectedButtonGroupSkeleton(weights = List(TYPE_FILTER_SKELETON_SLOTS) { 1f }, modifier = Modifier.padding(TypeFilterPadding))
}

/** 类型筛选行的留白：真实行与占位共用。 */
private val TypeFilterPadding = PaddingValues(horizontal = ChogumiDimens.ScreenPadding, vertical = 4.dp)
private const val TYPE_FILTER_SKELETON_SLOTS = 3

/**
 * 一排可横滑的筛选 chip（M3 filter chip）：点一下选中、再点一下取消，取消即「不限」，所以不另放「全部」。
 * 每枚写「名字 计数」；计数为 0（被另一排的选择排除）的项收起（项多，置灰会挤满一排），已选中的那项始终在，能点掉。
 * 作品子页的职位 / 出场与出演角色子页的角色类型共用这一个组件。
 */
@Composable
private fun <T> FilterChipRow(
    options: List<MonoWorkOption<T>>,
    selected: T?,
    name: @Composable (T) -> String,
    onSelect: (T?) -> Unit,
) {
    val values = options.filter { it.count > 0 || it.value == selected }.map { it.value }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ChogumiDimens.ScreenPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
    ) {
        items(values, key = { it.toString() }) { value ->
            SelectableChip(
                label = countedLabel(name(value), options, value),
                selected = value == selected,
                onClick = { onSelect(value.takeIf { it != selected }) },
                // 项随另一排的选择收起 / 出现时，其余 chip 平移让位而不是跳变。
                modifier = Modifier.animateItem(
                    fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                    placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                ),
            )
        }
    }
}

@StringRes
private fun MonoWorkSort.labelRes(): Int = when (this) {
    MonoWorkSort.RATING -> R.string.mono_sort_rating
    MonoWorkSort.TIME -> R.string.mono_sort_time
}

/** chip 文案带上计数：「音乐 17」。 */
@Composable
private fun <T> countedLabel(name: String, options: List<MonoWorkOption<T>>, value: T): String =
    stringResource(R.string.label_with_count, name, options.first { it.value == value }.count)

/** 第二维筛选项 → chip 文案：职位名 / 出场。 */
@Composable
private fun facetLabel(facet: MonoWorkFacet): String = when (facet) {
    is MonoWorkFacet.Position -> facet.name
    is MonoWorkFacet.Role -> stringResource(facet.role.labelRes())
}

/** 完整关联人物列表页：纵向分段列表，触底续取。 */
@Composable
fun MonoRelationsListScreen(
    monoType: MonoType,
    id: Int,
    onBack: () -> Unit,
    onRelationClick: (Int) -> Unit,
) {
    val viewModel: MonoRelationsListViewModel = koinViewModel { parametersOf(monoType, id) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PagedListPage(
        title = stringResource(R.string.mono_relations_title),
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        emptyText = stringResource(R.string.no_content),
        contentPadding = MonoListPadding,
        skeletonRow = { index, count -> MonoEntityRowSkeleton(index, count, EntityRowModifier) },
        endText = stringResource(R.string.load_more_end_relations),
    ) { relations ->
        itemsIndexed(relations, key = { _, relation -> relation.id }) { index, relation ->
            MonoRelationCard(
                relation = relation,
                index = index,
                count = relations.size,
                onClick = { onRelationClick(relation.id) },
                modifier = EntityRowModifier,
            )
        }
    }
}

/**
 * 人物出演的完整角色子页：首屏骨架（筛选行与角色行的占位）、失败重试、灰字空态与统一分页页脚。
 * 两排筛选（同网页的「类型 / 角色」）走接口参数，各带计数，只有一个选项的那排不画；
 * 筛选行是列表的头几项，随滚动离开（同作品子页）。计数取到之前整页保持首屏骨架，筛选与列表一起出现。
 */
@Composable
fun PersonCharactersScreen(
    personId: Int,
    onBack: () -> Unit,
    onCharacterClick: (Int) -> Unit,
) {
    val viewModel: PersonCharactersViewModel = koinViewModel { parametersOf(personId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filter = state.filter
    val options = (state.options as? CastFilterOptionsState.Ready)?.options
    PagedListPage(
        title = stringResource(R.string.mono_representative_characters),
        state = state.characters,
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        emptyText = stringResource(R.string.no_content),
        contentPadding = MonoListPadding,
        skeletonRow = { index, count -> MonoEntityRowSkeleton(index, count, EntityRowModifier) },
        headerReady = state.options != CastFilterOptionsState.Loading,
        headerSkeleton = { TypeFilterSkeleton() },
        header = {
            if (options != null && options.types.size > 1) {
                item(key = "filter-types") {
                    SubjectTypeFilterGroup(
                        options = options.types,
                        selected = filter.type,
                        onSelect = { viewModel.selectFilter(filter.copy(type = it)) },
                    )
                }
            }
            if (options != null && options.roles.size > 1) {
                item(key = "filter-roles") {
                    FilterChipRow(
                        options = options.roles,
                        selected = filter.role,
                        name = { role -> stringResource(role.labelRes()) },
                        onSelect = { viewModel.selectFilter(filter.copy(role = it)) },
                    )
                }
            }
        },
    ) { characters ->
        // 同一角色在不同作品里各算一条出演记录，id 会重复，key 带出现次序
        val keys = occurrenceKeys(characters) { it.id }
        itemsIndexed(characters, key = { index, _ -> keys[index] }) { index, character ->
            MonoEntityRow(
                name = displayNameOf(character),
                caption = character.subjectName,
                image = character.avatarUrl,
                index = index,
                count = characters.size,
                onClick = { onCharacterClick(character.id) },
                modifier = EntityRowModifier,
            )
        }
    }
}

/** 关联人物 / 出演角色列表行的左右留白：骨架与真实行共用。 */
private val EntityRowModifier = Modifier.fillMaxWidth().padding(horizontal = ChogumiDimens.ScreenPadding)

/** 关联人物 / 出演角色列表的上下留白：骨架与真实列表共用。 */
private val MonoListPadding = PaddingValues(vertical = ChogumiDimens.ScreenPadding)
