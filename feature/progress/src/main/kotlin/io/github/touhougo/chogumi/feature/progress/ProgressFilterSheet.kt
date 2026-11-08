package io.github.touhougo.chogumi.feature.progress

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.ProgressCategory
import io.github.touhougo.chogumi.core.model.ProgressCategoryGroup
import io.github.touhougo.chogumi.core.model.ProgressFilterOption
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.progressFilterOpening
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.AdaptiveGridRows
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.CountBadge
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.FilterTile
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SheetSectionTitle
import io.github.touhougo.chogumi.core.ui.composables.searchDimensionTitleRes

/**
 * 进度页筛选面板：只做筛选，排序在搜索栏的排序菜单里。
 *
 * 页面面板（[PageModalSheet]），打开时按内容定高（M3 bottom sheet：最高到离顶 72dp，宽于 640dp 时 56dp），
 * 开着时高度不变（非满高面板一律如此）：切换托盘、展开标签都往下长，顶边不跳，多出来的在标题行下面滚动，一直滚到面板底边。
 * 点格子即时生效、面板不关；标题行右端的「显示 N 部」只是关面板，N 是这一页筛完剩几部。
 * 关闭方式另有点遮罩、下滑、返回键（M3 modal bottom sheet）。
 *
 * [session] 是第几次打开（[ProgressUiState.filterPanelSession]）：换了就是一个新面板。上一个正在滑下去时又点了筛选入口，
 * 旧的直接撤掉、新的从底部滑上来，这一下不会因为旧的还没滑完而落空。[onDismiss] 带上是哪一次的面板滑完了，
 * 旧面板迟到的关闭不会把新面板关掉。
 */
@Composable
fun ProgressFilterSheet(
    session: Int,
    subjectType: SubjectType?,
    categoryGroups: List<ProgressCategoryGroup>,
    selectedCategories: Set<ProgressCategory>,
    tags: List<ProgressFilterOption<String>>,
    selectedTags: Set<String>,
    resultCount: Int,
    onToggleCategory: (ProgressCategory) -> Unit,
    onToggleTag: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: (session: Int) -> Unit,
) {
    key(session) {
        var hideRequested by rememberSaveable { mutableStateOf(false) }
        PageModalSheet(onDismiss = { onDismiss(session) }, fullHeight = false, hideRequested = hideRequested) {
            ProgressFilterPanel(
                subjectType = subjectType,
                categoryGroups = categoryGroups,
                selectedCategories = selectedCategories,
                tags = tags,
                selectedTags = selectedTags,
                resultCount = resultCount,
                onToggleCategory = onToggleCategory,
                onToggleTag = onToggleTag,
                onClear = onClear,
                onDone = { hideRequested = true },
            )
        }
    }
}

/**
 * 面板内容：钉住的标题行（「筛选」；右端是「清除」与「显示 N 部」），下面整块可滚动的分类与我的标签。
 * 打开时的样子由 [progressFilterOpening] 定：先摊开题材；有分类时我的标签收着（标题上是已选几个），没有分类时直接摊开。
 * 仪器测试 `ProgressFilterPanelTest` 直接渲染它（不经面板动画）。
 *
 * 外层左右只留 8dp，分类按钮组与网格再各缩 8dp（共 16dp，M3 compact 页边距）；分类的托盘不缩，
 * 所以正好比网格左右各宽 8dp，托盘里的格子与下面的标签格对齐。
 */
@Composable
internal fun ColumnScope.ProgressFilterPanel(
    subjectType: SubjectType?,
    categoryGroups: List<ProgressCategoryGroup>,
    selectedCategories: Set<ProgressCategory>,
    tags: List<ProgressFilterOption<String>>,
    selectedTags: Set<String>,
    resultCount: Int,
    onToggleCategory: (ProgressCategory) -> Unit,
    onToggleTag: (String) -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
) {
    val opening = progressFilterOpening(categoryGroups)
    PanelHeader(
        canClear = selectedTags.isNotEmpty() || selectedCategories.isNotEmpty(),
        resultCount = resultCount,
        onClear = onClear,
        onDone = onDone,
    )
    Column(
        modifier = Modifier
            .weight(1f, fill = false)
            .testTag("ProgressFilterPanel")
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (categoryGroups.isNotEmpty()) {
            CategorySection(subjectType, categoryGroups, opening.dimension, selectedCategories, onToggleCategory)
        }
        if (tags.isNotEmpty()) TagSection(tags, opening.tagsCollapsible, selectedTags, onToggleTag)
        if (categoryGroups.isEmpty() && tags.isEmpty()) {
            EmptyLine(stringResource(R.string.progress_filter_empty))
        }
    }
}

/**
 * 钉住的标题行（[SheetTitleRow]）：左「筛选」；右端先「清除」（低强调用 text button，M3 buttons.md「Text = lowest priority actions」，
 * 没有可清的时不显示），再是确认的「显示 N 部」（filled button，M3 small 高 40dp，component-tokens.md ButtonSmall）。
 * 确认动作在右上角、最靠边：M3 对话框「confirmation button is always closest to the edge」，全屏对话框的确认也在标题栏右端
 * （containment.md Dialogs）。「清除」出现、消失不让下面的内容跳：行高由 [SheetTitleRow] 的最小高度撑住。
 * 「清除」只清标签与分类（[ProgressViewModel.clearPanelFilters]）。
 */
@Composable
private fun PanelHeader(canClear: Boolean, resultCount: Int, onClear: () -> Unit, onDone: () -> Unit) {
    SheetTitleRow(stringResource(R.string.progress_filter_title)) {
        if (canClear) {
            TextButton(onClick = onClear, modifier = Modifier.testTag("ProgressPanelClear")) {
                Text(stringResource(R.string.clear_filters))
            }
        }
        Button(onClick = onDone, modifier = Modifier.testTag("ProgressFilterDone")) {
            Text(pluralStringResource(R.plurals.progress_filter_show, resultCount, resultCount))
        }
    }
}

/** 面板里的一节：名字（面板里一项设置的名字，labelLarge / onSurfaceVariant，docs/design/typography.md）与内容，间距 12dp。 */
@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SheetSectionTitle(title, Modifier.padding(horizontal = 8.dp))
        content()
    }
}

/**
 * 我的标签。[collapsible]（有分类时）：默认收着，标题行整行可点（至少 48dp 高，M3 触控区），收着时名字后面是已选几个
 * （[CountBadge]，与分类维度同一种徽标），右边 expand_more / expand_less，展开往下长、淡入（default spatial / default effects）。
 * 否则（没有分类，标签就是面板的全部内容）直接摊开、不可收起。
 */
@Composable
private fun TagSection(tags: List<ProgressFilterOption<String>>, collapsible: Boolean, selected: Set<String>, onToggle: (String) -> Unit) {
    if (!collapsible) {
        Section(stringResource(R.string.progress_filter_tags), Modifier.testTag("ProgressFilterTags")) {
            OptionGrid(tags, label = { it }, selected = { it in selected }, onToggle = onToggle)
        }
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.progress_filter_tags)
    val count = selected.size
    val selectedDescription = if (count > 0) pluralStringResource(R.plurals.progress_filter_dimension_selected, count, count) else null
    Column(Modifier.testTag("ProgressFilterTags")) {
        PressArea(
            onClick = { expanded = !expanded },
            shape = MaterialTheme.shapes.medium,
            onClickLabel = stringResource(if (expanded) R.string.detail_collapse else R.string.detail_expand),
            modifier = Modifier
                .semantics { if (selectedDescription != null && !expanded) stateDescription = selectedDescription }
                .testTag("ProgressFilterTagsHeader"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetSectionTitle(title)
                if (!expanded) CountBadge(count)
                Spacer(Modifier.weight(1f))
                Icon(
                    painter = painterResource(if (expanded) R.drawable.expand_less_24 else R.drawable.expand_more_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            OptionGrid(
                options = tags,
                label = { it },
                selected = { it in selected },
                onToggle = onToggle,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 分类：各维度是一组连通按钮（等宽铺满一行），点一个它就选中、取值在下面的托盘里摊开，再点收起；
 * 同一时间只开一个托盘。维度上有已选取值时按钮带数字徽标。维度的叫法按类型（[searchDimensionTitleRes]）。
 *
 * 托盘 `surfaceContainerHigh`、圆角 24（我的设计决定，not in M3），里面的取值与标签是同一种格子、同一套网格；
 * 从按钮下面长出来走 default spatial，淡入淡出走 default effects（M3 motion：几何用 spatial、透明度用 effects）。
 */
@Composable
private fun CategorySection(
    subjectType: SubjectType?,
    groups: List<ProgressCategoryGroup>,
    initialDimension: SearchDimension?,
    selected: Set<ProgressCategory>,
    onToggle: (ProgressCategory) -> Unit,
) {
    var openDimension by rememberSaveable { mutableStateOf(initialDimension) }
    val open = groups.firstOrNull { it.dimension == openDimension }
    // 收起时托盘还要带着刚才的取值缩回去
    var shown by rememberSaveable { mutableStateOf(openDimension) }
    if (open != null) shown = open.dimension
    val context = LocalContext.current
    val selectedCount = { dimension: SearchDimension -> selected.count { it.dimension == dimension } }
    Section(stringResource(R.string.progress_filter_categories), Modifier.testTag("ProgressFilterCategories")) {
        ConnectedChoiceGroup(
            options = groups.map { it.dimension },
            selected = open?.dimension,
            label = { stringResource(searchDimensionTitleRes(subjectType, it)) },
            onSelect = { openDimension = it },
            onDeselect = { openDimension = null },
            badge = selectedCount,
            itemModifier = { dimension ->
                val count = selectedCount(dimension)
                Modifier.semantics {
                    if (count > 0) {
                        stateDescription = context.resources.getQuantityString(R.plurals.progress_filter_dimension_selected, count, count)
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        AnimatedVisibility(
            visible = open != null,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            groups.firstOrNull { it.dimension == shown }?.let { group ->
                OptionGrid(
                    options = group.options,
                    label = { it.option.label },
                    selected = { it in selected },
                    onToggle = onToggle,
                    modifier = Modifier
                        .testTag("ProgressFilterTray")
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, TrayShape)
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** 筛选格网格的左右留白：与面板里分区标题、托盘内边距同一个 8dp（我的设计决定，not in M3）。 */
private val OptionGridSideMargin = 8.dp

/** 托盘圆角（原型 `.tray{border-radius:24px}`，我的设计决定，not in M3）。 */
private val TrayShape = RoundedCornerShape(24.dp)

/** 标签与分类取值共用的格子网格：等宽、每一行都铺满（[AdaptiveGridRows] 的 fillLastRow），格子是 [FilterTile]。 */
@Composable
private fun <K> OptionGrid(
    options: List<ProgressFilterOption<K>>,
    label: (K) -> String,
    selected: (K) -> Boolean,
    onToggle: (K) -> Unit,
    modifier: Modifier = Modifier,
) {
    AdaptiveGridRows(
        items = options,
        cell = GridCell.FilterTile,
        modifier = modifier,
        sideMargin = OptionGridSideMargin,
        rowSpacing = GridCell.FilterTile.spacing,
        fillLastRow = true,
    ) { option ->
        key(option.key) {
            FilterTile(
                label = label(option.key),
                count = option.count,
                covers = option.recent,
                selected = selected(option.key),
                onClick = { onToggle(option.key) },
            )
        }
    }
}
