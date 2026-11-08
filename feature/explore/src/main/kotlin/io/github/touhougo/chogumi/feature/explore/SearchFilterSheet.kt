package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.DimensionSelectionMode
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionGroup
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagCatalogEntry
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.dimensionSelectionMode
import io.github.touhougo.chogumi.core.model.formatAbbreviatedCount
import io.github.touhougo.chogumi.core.model.hasAdditionalFilters
import io.github.touhougo.chogumi.core.model.isThemeWord
import io.github.touhougo.chogumi.core.model.isWordSelected
import io.github.touhougo.chogumi.core.model.searchDimensionsFor
import io.github.touhougo.chogumi.core.model.stableKey
import io.github.touhougo.chogumi.core.model.themeDimensionFor
import io.github.touhougo.chogumi.core.model.themeWordsFor
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.ContainedSearchField
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip
import io.github.touhougo.chogumi.core.ui.composables.SheetSectionTitle
import io.github.touhougo.chogumi.core.ui.composables.searchDimensionTitleRes
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 筛选面板（modal bottom sheet，画在所在页的面板层里，见 [PageModalSheet]）：搜索框 + 题材区 + 其余维度分组 +
 * 时间范围滑块（见 [AirtimeSection]）。
 *
 * 编辑的是 [SearchForm.filterDraft]：点选只改草稿，点「保存」才写入已提交条件并重搜一次；
 * 点遮罩 / 下滑 / 返回即关闭并丢弃草稿（containment.md:478 列出的 modal 关闭方式）。
 * 「需要保存的页面里，选择不必即时生效」见 input.md:125。[hideRequested] 置上后面板滑下去，滑完照常回调 [onDismiss]。
 *
 * 各维度取值全部换行铺开当索引用——my design decision (not in M3)：M3 对超过两行的 filter chip 组
 * 只是「consider」横向滚动（input.md:466），本面板要一眼看全，故换行而非横滑。
 */
@Composable
fun SearchFilterSheet(
    subjectType: SubjectType?,
    draft: SearchFilters,
    actions: FilterDraftActions,
    panelQuery: String,
    catalogSuggestions: List<TagCatalogEntry>,
    currentYear: Int,
    hideRequested: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    PageModalSheet(onDismiss = onDismiss, fullHeight = true, hideRequested = hideRequested) {
        SearchFilterPanel(
            subjectType = subjectType,
            draft = draft,
            actions = actions,
            panelQuery = panelQuery,
            catalogSuggestions = catalogSuggestions,
            currentYear = currentYear,
            onSave = onSave,
        )
    }
}

/**
 * 面板内容。与 sheet 外壳分离，仪器测试可直接渲染而不依赖 sheet 动画。
 *
 * 从上到下：搜索框（含建议 / 其他已选）→ 题材区 → 其余维度 → 时间。
 * 固定顶部的只有标题行；下面是一个 LazyColumn，内容超出时内部滚动（containment.md:656「title is pinned」）。
 *
 * [panelQuery] 与 [catalogSuggestions] 由 SearchViewModel 持有并传下来——搜索框的输入只有这一份，
 * 面板不另存局部状态。[catalogSuggestions] 在目录还没拉下来时是空列表，此时
 * 建议里只有本地词典与题材词，不显示加载态。
 */
@Composable
fun SearchFilterPanel(
    subjectType: SubjectType?,
    draft: SearchFilters,
    actions: FilterDraftActions,
    panelQuery: String,
    catalogSuggestions: List<TagCatalogEntry>,
    currentYear: Int,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = searchDimensionsFor(subjectType)
    val themeDimension = themeDimensionFor(subjectType)
    val themeWords = themeWordsFor(subjectType)
    // 草稿连同当前类型：词的落位与「是否已选」都由 core:model 按类型判定。
    val criteria = SearchCriteria(subjectType = subjectType, filters = draft)
    // 分区展开状态是纯 UI 局部状态，不进草稿（草稿只记已选条件）。
    var expanded by remember(subjectType) { mutableStateOf(emptySet<SearchDimension>()) }

    Column(modifier.fillMaxWidth()) {
        // 横向 16dp：M3 compact 边距（layout.md:354）；分组间距 24dp、底部留白为 my design decision (not in M3)。
        PanelHeader(
            canClear = draft.hasAdditionalFilters(),
            onClear = { actions.onDraftClear(); expanded = emptySet() },
            onSave = onSave,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("SearchFilterPanel"),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "search-field") {
                FilterSearchField(
                    criteria = criteria,
                    query = panelQuery,
                    catalogSuggestions = catalogSuggestions,
                    onQueryChange = actions::onPanelQueryChange,
                    onAddWord = { word ->
                        actions.onDraftWordPick(word, WordPick.ADD)
                        actions.onPanelQueryChange("")
                    },
                    onRemoveOther = actions::onDraftTagRemove,
                )
            }

            if (groups.isEmpty()) {
                item(key = "dimension-hint") {
                    Text(
                        stringResource(R.string.search_dimension_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("SearchDimensionHint"),
                    )
                }
            }

            if (themeWords.isNotEmpty()) {
                item(key = "theme") {
                    ThemeSection(
                        criteria = criteria,
                        words = themeWords,
                        onToggle = { word -> actions.onDraftWordPick(word, WordPick.TOGGLE) },
                    )
                }
            }

            items(groups.filter { it.dimension != themeDimension }, key = { "dimension-${it.dimension.name}" }) { group ->
                DimensionSection(
                    type = subjectType,
                    group = group,
                    selected = draft.dimensions[group.dimension].orEmpty(),
                    expanded = group.dimension in expanded,
                    onToggleExpand = {
                        expanded = if (group.dimension in expanded) expanded - group.dimension else expanded + group.dimension
                    },
                    onToggle = { actions.onDraftDimensionToggle(group.dimension, it) },
                )
            }

            item(key = "airtime") {
                AirtimeSection(
                    airtime = draft.airtime,
                    currentYear = currentYear,
                    onChange = actions::onDraftAirtimeChange,
                )
            }
        }
    }
}

/**
 * 标题行（[SheetTitleRow]）：标题靠左，右端先「清除」（text button，低强调），再是「保存」（filled button：
 * 用于 Save/Confirm 这类最终操作，buttons.md 强调层级表）。确认按钮靠尾部边缘，与 M3 对话框的按钮排布一致（containment.md:605）。
 * 窄屏、大字号、英文下标题让位（句尾省略），两个按钮保持完整。
 */
@Composable
private fun PanelHeader(canClear: Boolean, onClear: () -> Unit, onSave: () -> Unit) {
    SheetTitleRow(stringResource(R.string.search_filters)) {
        TextButton(onClick = onClear, enabled = canClear, modifier = Modifier.testTag("SearchPanelClear")) {
            Text(stringResource(R.string.clear_filters))
        }
        Button(onClick = onSave, modifier = Modifier.testTag("SearchPanelSave")) {
            Text(stringResource(R.string.save))
        }
    }
}

/**
 * 面板内搜索框：输入时列建议（本地词典与题材词 + 标签目录），末行永远是「按『输入的词』筛选」
 * （输入正好等于某条建议时不出这一行）；输入法的「搜索」动作等于选末行。没有输入时列出「其他已选」。
 *
 * 回车与点建议都只走 [onAddWord]（只加不减，见 [WordPick.ADD]）；落成维度还是标签由
 * `resolveTagInput` 决定，这里不判。建议怎么合由 [filterSuggestions] 一处决定。
 */
@Composable
private fun FilterSearchField(
    criteria: SearchCriteria,
    query: String,
    catalogSuggestions: List<TagCatalogEntry>,
    onQueryChange: (String) -> Unit,
    onAddWord: (String) -> Unit,
    onRemoveOther: (TagFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ContainedSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.search_filter_field_hint),
            fieldModifier = Modifier.testTag("SearchFilterField"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onAddWord(query) }),
        )
        val suggestions = filterSuggestions(criteria.subjectType, query, catalogSuggestions)
        if (suggestions != null) {
            SuggestionList(criteria, suggestions, onAddWord)
        } else {
            OtherSelectedRow(criteria, onRemoveOther)
        }
    }
}

/** 「其他已选」：题材区以外的已选标签（用户标签、落不进维度的分类标签），一律叫「标签」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherSelectedRow(criteria: SearchCriteria, onRemove: (TagFilter) -> Unit) {
    val others = criteria.filters.tags.filter { !it.isThemeWord(criteria.subjectType) }
    if (others.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetSectionTitle(stringResource(R.string.search_other_selected))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
            verticalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
        ) {
            others.forEach { tag ->
                RemovableChip(
                    label = tag.value,
                    spoken = tagSummary(criteria, tag),
                    onRemove = { onRemove(tag) },
                    modifier = Modifier.testTag("SearchDraftTag-${tag.stableKey()}"),
                )
            }
        }
    }
}

/** 一条建议的辅助文本：维度写它属于哪个分区，题材词写「题材」，目录词写使用次数。 */
@Composable
private fun SuggestionList(
    criteria: SearchCriteria,
    suggestions: List<FilterSuggestion>,
    onApply: (String) -> Unit,
) {
    val subjectType = criteria.subjectType
    val themeWords = themeWordsFor(subjectType)
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier = Modifier.testTag("SearchFilterSuggestions")) {
        suggestions.forEach { row ->
            val isFree = row is FilterSuggestion.Word && row.label !in themeWords
            val text = if (isFree) stringResource(R.string.search_suggestion_fallback, row.label) else row.label
            val note = when {
                isFree -> stringResource(R.string.search_suggestion_fallback_note)
                row is FilterSuggestion.Dimension -> stringResource(searchDimensionTitleRes(subjectType, row.dimension))
                row is FilterSuggestion.Catalog -> stringResource(
                    R.string.search_suggestion_catalog_count,
                    formatAbbreviatedCount(row.count, locale),
                )
                row.label in themeWords -> stringResource(R.string.search_theme_section)
                else -> ""
            }
            val selected = stringResource(R.string.search_suggestion_selected).takeIf { criteria.isWordSelected(row.label) }
            SuggestionRow(
                text = text,
                note = when {
                    selected == null -> note
                    note.isEmpty() -> selected
                    else -> stringResource(R.string.search_suggestion_note_selected, note, selected)
                },
                testTag = "SearchSuggestion-${row.label}",
                onClick = { onApply(row.label) },
            )
        }
    }
}

/**
 * 一条建议：M3 列表项（搜索的建议与结果用 list 组件承载，input.md:691、:808），
 * 单一动作（整行点一下即选，containment.md:326）；主文本放建议词，辅助文本放它属于哪个分区与「已选」
 * （label + supporting text 两个槽位，containment.md:250；新设计用 expressive 列表，containment.md:346）。
 */
@Composable
private fun SuggestionRow(text: String, note: String, testTag: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        supportingContent = if (note.isNotEmpty()) {
            { Text(note) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    ) {
        Text(text)
    }
}

/**
 * 题材区：题材维度的取值 + 人工挑的题材词，合成一组，一律多选（同一维度内的多值是「同时满足」）。
 * 点 chip 是切换（[WordPick.TOGGLE]）：已选的再点一下即取消，维度取值和自由词一样。
 */
@Composable
private fun ThemeSection(
    criteria: SearchCriteria,
    words: List<String>,
    onToggle: (String) -> Unit,
) {
    OptionSection(
        title = stringResource(R.string.search_theme_section),
        mode = DimensionSelectionMode.MULTI,
        options = words,
        isSelected = criteria::isWordSelected,
        testTagPrefix = THEME_TAG,
        onToggle = onToggle,
    )
}

/** 一个维度分区：标题 + 选法标记 + 取值 chip 流（取值超过 12 个时折叠，末位一个展开/收起 chip）。 */
@Composable
private fun DimensionSection(
    type: SubjectType?,
    group: SearchDimensionGroup,
    selected: List<SearchDimensionOption>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggle: (SearchDimensionOption) -> Unit,
) {
    val long = group.options.size > COLLAPSED_OPTIONS
    val isSelected = { option: SearchDimensionOption -> option in selected }
    // 折叠时：前 12 个原位不动，12 个以外但已选的接在后面照样显示。
    val visible = if (long && !expanded) {
        group.options.take(COLLAPSED_OPTIONS) + group.options.drop(COLLAPSED_OPTIONS).filter(isSelected)
    } else {
        group.options
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = stringResource(searchDimensionTitleRes(type, group.dimension)),
            multi = dimensionSelectionMode(type, group.dimension) == DimensionSelectionMode.MULTI,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
            verticalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
        ) {
            visible.forEach { option ->
                OptionChip(
                    label = option.label,
                    selected = isSelected(option),
                    testTag = dimensionOptionTag(group.dimension, option),
                    onClick = { onToggle(option) },
                )
            }
            if (long) {
                ExpandChip(
                    expanded = expanded,
                    count = group.options.size,
                    testTag = "SearchDim-${group.dimension.name}-EXPAND",
                    onClick = onToggleExpand,
                )
            }
        }
    }
}

/** 通用取值分区：题材区与维度分区共用版式（标题 + 选法标记 + chip 流）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionSection(
    title: String,
    mode: DimensionSelectionMode,
    options: List<String>,
    isSelected: (String) -> Boolean,
    testTagPrefix: String,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title = title, multi = mode == DimensionSelectionMode.MULTI)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
            verticalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
        ) {
            options.forEach { option ->
                OptionChip(
                    label = option,
                    selected = isSelected(option),
                    testTag = "$testTagPrefix-$option",
                    onClick = { onToggle(option) },
                )
            }
        }
    }
}

/** 分区标题 + 选法标记（「单选」/「可多选，同时满足」）。 */
@Composable
private fun SectionHeader(title: String, multi: Boolean) {
    SheetSectionTitle(title) {
        Text(
            stringResource(if (multi) R.string.search_mode_multi else R.string.search_mode_single),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 「全部 N 个 / 收起」：原地展开，不弹对话框（原型规格依据）。 */
@Composable
private fun ExpandChip(expanded: Boolean, count: Int, testTag: String, onClick: () -> Unit) {
    SelectableChip(
        label = stringResource(if (expanded) R.string.search_collapse else R.string.search_show_all, count),
        selected = false,
        trailingIcon = if (expanded) R.drawable.expand_less_24 else R.drawable.expand_more_24,
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
    )
}

/** 取值 chip 的稳定 testTag：维度 + 请求用 metaTag（同类型内唯一，见 SearchDimensionTest）。 */
internal fun dimensionOptionTag(dimension: SearchDimension, option: SearchDimensionOption): String =
    "SearchDim-${dimension.name}-${option.metaTag}"

/** 单选 / 多选 filter chip。选中项文字用 emphasized（typography.md:53-67「selected states」）。 */
@Composable
private fun OptionChip(label: String, selected: Boolean, testTag: String, onClick: () -> Unit) {
    SelectableChip(label = label, selected = selected, onClick = onClick, modifier = Modifier.testTag(testTag))
}

/** 题材区 chip 的 testTag 前缀（取值可能是维度取值也可能是自由词，故不按 metaTag 编）。 */
private const val THEME_TAG = "SearchTheme"

/** 分区取值超过这个数才折叠（my design decision (not in M3)）。 */
private const val COLLAPSED_OPTIONS = 12
