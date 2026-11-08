package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.ShelfChoice
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TileShelfSource
import io.github.touhougo.chogumi.core.model.WorkShelfSource
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.availableSorts
import io.github.touhougo.chogumi.core.model.effectiveSort
import io.github.touhougo.chogumi.core.model.isPinnable
import io.github.touhougo.chogumi.core.model.isTagBrowse
import io.github.touhougo.chogumi.core.model.keptBy
import io.github.touhougo.chogumi.core.model.shelfChoicesFor
import io.github.touhougo.chogumi.core.model.shelfTitleOf
import io.github.touhougo.chogumi.core.model.tileCandidates
import io.github.touhougo.chogumi.core.model.tileDrawingsFor
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.BusyButton
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.ScrollableModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SegmentedCreateItem
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SheetSectionTitle
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.SkeletonSketch
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import java.time.LocalDate

/**
 * 整理模式的面板：开着的编辑面板（作品货架、方块货架各一套）与「添加货架」。都画在探索页自己的面板层里
 * （[PageModalSheet] / [ScrollableModalSheet]），编辑面板可以叠在「添加货架」上（新建），筛选面板叠在作品货架的编辑面板上。
 */
@Composable
internal fun ArrangeSheets(
    state: ExploreUiState,
    arranger: ShelfArranger,
    currentYear: Int,
) {
    val arrange = state.arrange
    if (arrange.adding) {
        AddShelfSheet(
            type = state.selectedType,
            today = state.today,
            layout = state.layoutOf(state.selectedType),
            hideRequested = arrange.closing == ArrangeSheet.ADDING,
            arranger = arranger,
        )
    }
    when (val editor = arrange.editor) {
        is ShelfEditor.Works -> WorksEditorSheet(
            editor = editor,
            title = editorTitle(editor, state),
            hideRequested = arrange.closing == ArrangeSheet.EDITOR,
            saving = arrange.saving,
            currentYear = currentYear,
            arranger = arranger,
        )
        is ShelfEditor.Tiles -> TilesEditorSheet(
            editor = editor,
            title = editorTitle(editor, state),
            typeContent = state.contentOf(editor.type),
            hideRequested = arrange.closing == ArrangeSheet.EDITOR,
            saving = arrange.saving,
            arranger = arranger,
        )
        null -> Unit
    }
}

/** 编辑面板的标题：新建的两种、固定货架用它自己的名字（只能换样式），其余「编辑货架」。 */
@Composable
private fun editorTitle(editor: ShelfEditor, state: ExploreUiState): String {
    val fixedTitle = (editor as? ShelfEditor.Works)?.takeIf { it.isFixed }?.originalId?.let { id ->
        state.layoutOf(editor.type).shelfLayout.firstOrNull { it.id == id }?.let { shelfTitleOf(editor.type, state.today, it) }
    }
    return when {
        fixedTitle != null -> exploreTitleText(fixedTitle)
        editor.originalId != null -> stringResource(R.string.explore_shelf_edit)
        editor is ShelfEditor.Works -> stringResource(R.string.explore_shelf_new_works)
        else -> stringResource(R.string.explore_shelf_new_tiles)
    }
}

/**
 * 作品货架的编辑面板（原型 `editSheetHTML`）：名称、条件、排序、样式、移除。固定货架只有样式与移除。
 *
 * 条件：已选的逐条列出、点一下去掉；「+ 条件」打开搜索页同一个筛选面板改草稿，保存回到这里（与搜索页同一个 [reduce]）。
 * 排序只列这组条件能用的（`availableSorts`）。名称空着时用占位的名字：预设的词没变是预设名，否则是条件连成的「A × B」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorksEditorSheet(
    editor: ShelfEditor.Works,
    title: String,
    hideRequested: Boolean,
    saving: Boolean,
    currentYear: Int,
    arranger: ShelfArranger,
) {
    val criteria = editor.form.applied
    val keptPreset = (editor.source as? WorkShelfSource.Preset)?.keptBy(editor.type, criteria)
    val placeholder = when {
        editor.isFixed -> ""
        keptPreset != null -> keptPreset.name
        criteria.isPinnable() -> defaultTitleFor(criteria)
        else -> ""
    }
    val name = editor.name.trim().ifEmpty { placeholder }
    PageModalSheet(onDismiss = arranger::editorClosed, fullHeight = false, hideRequested = hideRequested) {
        SheetTitleRow(title) {
            BusyButton(
                onClick = { arranger.save(name) }, enabled = editor.canSave, busy = saving,
                busyLabel = stringResource(R.string.loading), modifier = Modifier.testTag("shelf_editor_save"),
            ) {
                Text(stringResource(R.string.save))
            }
        }
        EditorBody {
            if (!editor.isFixed) {
                FormTextField(
                    value = editor.name,
                    enabled = !saving,
                    onValueChange = { if (it.length <= PRESET_TITLE_MAX_LENGTH) arranger.setName(it) },
                    label = stringResource(R.string.explore_shelf_name),
                    placeholder = placeholder,
                    modifier = Modifier.fillMaxWidth().testTag("shelf_editor_name"),
                )
                EditorSection(stringResource(R.string.explore_shelf_conditions)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing)) {
                        criteria.activeConditions().forEach { condition ->
                            val text = criteria.conditionText(condition)
                            RemovableChip(label = text, spoken = text, onRemove = { arranger.removeCondition(condition) })
                        }
                        AddChip(stringResource(R.string.explore_shelf_add_condition), arranger::openWords, Modifier.testTag("shelf_editor_add_condition"))
                    }
                }
                val sorts = criteria.availableSorts()
                if (sorts.isNotEmpty()) {
                    EditorSection(stringResource(R.string.explore_shelf_sort)) {
                        ConnectedChoiceGroup(
                            options = SearchSort.entries,
                            selected = criteria.effectiveSort(),
                            label = { stringResource(it.labelRes(isTagBrowse = criteria.isTagBrowse())) },
                            onSelect = arranger::setSort,
                            visible = { it in sorts },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            EditorSection(stringResource(R.string.explore_shelf_style)) {
                StyleOptions(
                    options = WorkShelfStyle.entries,
                    selected = editor.style,
                    label = { stringResource(it.labelRes()) },
                    onSelect = arranger::setStyle,
                ) { style -> WorksShelfBody(style, subjects = null, onSubjectClick = {}) }
            }
            if (editor.originalId != null) RemoveShelfButton(arranger::removeEdited, enabled = !saving)
        }
    }
    val draft = editor.form.filterDraft
    if (draft != null) {
        // 与搜索页同一个面板与同一套开关：「保存」先让面板滑下去，滑完的那一次关闭才把草稿交回
        var saving by remember { mutableStateOf(false) }
        SearchFilterSheet(
            subjectType = editor.type,
            draft = draft,
            actions = arranger.wordActions,
            panelQuery = editor.panelQuery,
            catalogSuggestions = editor.catalogSuggestions,
            currentYear = currentYear,
            hideRequested = saving,
            onDismiss = { if (saving) arranger.saveWords() else arranger.dismissWords() },
            onSave = { saving = true },
        )
    }
}

/**
 * 方块货架的编辑面板：名称（只有自己建的）、方块（点掉一块；「+ 方块」展开同一组的候选，自己建的从整个预设目录里挑）、
 * 画法（只列这条货架能用的，`tileDrawingsFor`）、移除。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TilesEditorSheet(
    editor: ShelfEditor.Tiles,
    title: String,
    typeContent: ExploreTypeContent,
    hideRequested: Boolean,
    saving: Boolean,
    arranger: ShelfArranger,
) {
    val custom = editor.source is TileShelfSource.Custom
    val placeholder = stringResource(R.string.explore_shelf_tiles_default_name)
    val name = editor.name.trim().ifEmpty { placeholder }
    val candidates = remember(editor.type, editor.source) { tileCandidates(editor.type, editor.source) }
    val byKey = remember(candidates) { candidates.associateBy { it.label.key } }
    val chosen = editor.tileKeys.mapNotNull { byKey[it] }
    val rest = candidates.filter { it.label.key !in editor.tileKeys }
    PageModalSheet(onDismiss = arranger::editorClosed, fullHeight = false, hideRequested = hideRequested) {
        SheetTitleRow(title) {
            BusyButton(
                onClick = { arranger.save(name) }, enabled = editor.canSave, busy = saving,
                busyLabel = stringResource(R.string.loading), modifier = Modifier.testTag("shelf_editor_save"),
            ) {
                Text(stringResource(R.string.save))
            }
        }
        EditorBody {
            if (custom) {
                FormTextField(
                    value = editor.name,
                    enabled = !saving,
                    onValueChange = { if (it.length <= PRESET_TITLE_MAX_LENGTH) arranger.setName(it) },
                    label = stringResource(R.string.explore_shelf_name),
                    placeholder = placeholder,
                    modifier = Modifier.fillMaxWidth().testTag("shelf_editor_name"),
                )
            }
            EditorSection(stringResource(R.string.explore_shelf_tiles)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
                    verticalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
                ) {
                    chosen.forEach { tile ->
                        val label = exploreTileLabelText(tile.label)
                        RemovableChip(label = label, spoken = label, onRemove = { arranger.removeTile(tile.label.key) })
                    }
                    if (rest.isNotEmpty()) AddChip(stringResource(R.string.explore_shelf_add_tile), arranger::togglePicking, Modifier.testTag("shelf_editor_add_tile"))
                }
                if (editor.picking && rest.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
                        verticalArrangement = Arrangement.spacedBy(ChogumiDimens.ChipSpacing),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
                            .padding(12.dp)
                            .testTag("shelf_editor_tile_candidates"),
                    ) {
                        rest.forEach { tile ->
                            AssistChip(
                                onClick = { arranger.addTile(tile.label.key) },
                                label = { Text(exploreTileLabelText(tile.label)) },
                            )
                        }
                    }
                }
            }
            EditorSection(stringResource(R.string.explore_shelf_drawing)) {
                val preview = ExploreShelf.Tiles(
                    id = editor.originalId.orEmpty(),
                    title = ExploreTitle.Named(name),
                    tiles = chosen,
                    drawing = editor.drawing,
                )
                StyleOptions(
                    options = tileDrawingsFor(editor.source),
                    selected = editor.drawing,
                    label = { stringResource(it.labelRes()) },
                    onSelect = arranger::setDrawing,
                ) { drawing ->
                    TilesShelf(preview.copy(drawing = drawing), typeContent, onShown = {}, onSubjectClick = {}, onOpenSearch = { _, _, _ -> }, onArrange = {})
                }
            }
            if (editor.originalId != null) RemoveShelfButton(arranger::removeEdited, enabled = !saving)
        }
    }
}

/** 编辑面板标题行下面的内容：可滚（面板顶边固定，内容比面板高就在里面滚）。 */
@Composable
private fun EditorBody(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
    ) { content() }
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetSectionTitle(title)
        content()
    }
}

/** 「+ 条件」「+ 方块」：加东西的那颗 chip（assist chip，前面一个加号，原型 `.ichip.add`）。 */
@Composable
private fun AddChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(painterResource(R.drawable.add_24), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
        },
        modifier = modifier,
    )
}

/** 「移除货架」（= 隐藏，「添加货架」里还能加回来）：error 色的文字按钮，放在面板最下面。 */
@Composable
private fun RemoveShelfButton(onClick: () -> Unit, enabled: Boolean) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.testTag("shelf_editor_remove"),
    ) {
        Icon(painterResource(R.drawable.delete_24), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(stringResource(R.string.explore_shelf_remove), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

/**
 * 一排可横滑的样式（或画法）选项：每格是那种画法本身缩小的样子（[StylePreview]）加名字，选中的描主色边。
 * 横滑而不是网格：八种样式在任何宽度下都不会在末行落单。格宽、缩略图高是我的设计决定（not in M3）。
 */
@Composable
private fun <T> StyleOptions(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    preview: @Composable (T) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        items(options, key = { it.toString() }) { option ->
            val on = option == selected
            val shape = MaterialTheme.shapes.large
            PressArea(
                onClick = { onSelect(option) },
                shape = shape,
                role = Role.RadioButton,
                modifier = Modifier
                    .width(StyleOptionWidth)
                    .semantics { this.selected = on }
                    .testTag("shelf_style_$option"),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer, shape)
                        .border(2.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                        .padding(8.dp),
                ) {
                    StylePreview(Modifier.fillMaxWidth().height(StylePreviewHeight)) { preview(option) }
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 一种画法的缩略图：把真的画法（[WorksShelfBody] / [TilesShelf]，样式与画法的唯一分派）按 [PreviewVirtualWidth] 的宽、
 * 与页面上一样竖着排好（封面流的说明行在封面下面），
 * 整体缩到这一格的宽，顶对齐、超出的高度裁掉。作品还没有时画的是骨架，[SkeletonSketch] 把它画成主题色的色块与字条，
 * 缩小了也看得出每种版式。只是看的：上面盖一层接住触摸的透明层，里面的横滑与点击都收不到；不进读屏。
 */
@Composable
private fun StylePreview(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.clipToBounds()) {
        Layout(content = { Column { SkeletonSketch(content) } }, modifier = Modifier.clearAndSetSemantics {}) { measurables, constraints ->
            val virtual = PreviewVirtualWidth.roundToPx()
            val placeable = measurables.first().measure(Constraints(minWidth = virtual, maxWidth = virtual))
            val scale = constraints.maxWidth.toFloat() / virtual
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.placeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitEachGesture { while (true) awaitPointerEvent() } })
    }
}

/** 缩略图按多宽的屏排：一部手机的宽，缩下来正好看得出一行几张、什么形状。 */
private val PreviewVirtualWidth = 360.dp
private val StyleOptionWidth = 112.dp
private val StylePreviewHeight = 64.dp

/**
 * 「添加货架」（原型 `addSheetHTML`）：新建作品货架、新建方块货架，然后是我建的、固定货架与预设方块、预设目录各大类。
 * 只列名字、不报部数；点一行就在页面上放上或移除（新放的在近期热门下面第一条），右端的圆标出它在不在页面上。
 */
@Composable
private fun AddShelfSheet(
    type: SubjectType,
    today: LocalDate,
    layout: ExploreLayout,
    hideRequested: Boolean,
    arranger: ShelfArranger,
) {
    val choices = remember(type, layout.shelfLayout) { shelfChoicesFor(type, layout.shelfLayout) }
    ScrollableModalSheet(onDismiss = arranger::addingClosed, hideRequested = hideRequested) {
        SheetTitleRow(stringResource(R.string.explore_arrange_add)) {
            Button(onClick = arranger::finishAdding, modifier = Modifier.testTag("add_shelf_done")) {
                Text(stringResource(R.string.explore_arrange_done))
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth().testTag("add_shelf_list"),
        ) {
            item(key = "new_works") {
                NewShelfRow(0, R.drawable.add_24, R.string.explore_shelf_new_works, R.string.explore_shelf_new_works_detail) {
                    arranger.newWorksShelf(type)
                }
            }
            item(key = "new_tiles") {
                NewShelfRow(1, R.drawable.grid_view_24, R.string.explore_shelf_new_tiles, R.string.explore_shelf_new_tiles_detail) {
                    arranger.newTilesShelf(type)
                }
            }
            choiceGroup("custom", R.string.explore_shelf_group_custom, null, choices.custom, type, today, arranger)
            choiceGroup("fixed", R.string.explore_shelf_group_fixed, null, choices.fixed, type, today, arranger)
            choices.presets.forEach { (group, rows) -> choiceGroup("preset_$group", null, group, rows, type, today, arranger) }
        }
    }
}

/** 一组：组名（资源或目录里的大类名）加一段分段列表；没有行的组不画。 */
private fun LazyListScope.choiceGroup(
    key: String,
    titleRes: Int?,
    titleText: String?,
    rows: List<ShelfChoice>,
    type: SubjectType,
    today: LocalDate,
    arranger: ShelfArranger,
) {
    if (rows.isEmpty()) return
    item(key = "group_$key") {
        SheetSectionTitle(
            text = titleText ?: stringResource(checkNotNull(titleRes)),
            modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp),
        )
    }
    itemsIndexed(rows, key = { _, choice -> "${key}_${choice.entry.id}" }) { index, choice ->
        ChoiceRow(index, rows.size, choice, type, today) { arranger.toggle(type, choice) }
    }
}

@Composable
private fun NewShelfRow(index: Int, icon: Int, title: Int, detail: Int, onClick: () -> Unit) {
    SegmentedCreateItem(
        index = index,
        count = 2,
        title = stringResource(title),
        icon = icon,
        onClick = onClick,
        supporting = stringResource(detail),
        modifier = Modifier.testTag("add_shelf_new_$index"),
    )
}

@Composable
private fun ChoiceRow(index: Int, count: Int, choice: ShelfChoice, type: SubjectType, today: LocalDate, onToggle: () -> Unit) {
    val title = shelfTitleOf(type, today, choice.entry)?.let { exploreTitleText(it) } ?: return
    SegmentedItem(
        index = index,
        count = count,
        onClick = onToggle,
        trailingContent = { ShownMark(choice.shown) },
        supportingContent = choice.detail?.let { detail -> { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        modifier = Modifier
            .semantics { selected = choice.shown }
            .testTag("add_shelf_row_${choice.entry.id}"),
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 一行在不在页面上：在是填色的勾，不在是描边的加号（原型 `.tog`）。整行就是开关，这里只是标记。 */
@Composable
private fun ShownMark(shown: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (shown) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (shown) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (shown) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(if (shown) R.drawable.check_24 else R.drawable.add_24),
                contentDescription = stringResource(if (shown) R.string.explore_shelf_hide else R.string.explore_shelf_show),
            )
        }
    }
}
