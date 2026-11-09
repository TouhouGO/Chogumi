package io.github.touhougo.chogumi.feature.explore

import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ShelfEntry
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TileDrawing
import io.github.touhougo.chogumi.core.model.WorkShelfContent
import io.github.touhougo.chogumi.core.model.WorkShelfEntry
import io.github.touhougo.chogumi.core.model.WorkShelfSource
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.effectiveSort
import io.github.touhougo.chogumi.core.model.isTagBrowse
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.ReorderableItemScope
import io.github.touhougo.chogumi.core.ui.composables.ReorderableList
import io.github.touhougo.chogumi.core.ui.composables.containerColor
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox

/**
 * 探索页每一条货架的标题（近期热门、各样式的作品货架、各画法的方块货架与刻度尺都用它，全页唯一）：[SectionHeader] 加上
 * 长按进入整理（原型「长按任一货架标题」）。近期热门固定在最上面、不进整理列表，长按它照样进整理。
 */
@Composable
internal fun ShelfHeader(
    title: String,
    onArrange: () -> Unit,
    subtitle: List<String>? = null,
    onArrowClick: (() -> Unit)? = null,
) {
    SectionHeader(
        title = title,
        subtitle = subtitle,
        onArrowClick = onArrowClick,
        modifier = arrangeOnLongPress(onArrange),
    )
}

/**
 * 长按进入整理：按住时有一下触感；读屏用长按动作。只旁听标题这一块：标题行尾的箭头按钮自己消费了按下，长按箭头不算。
 */
@Composable
private fun arrangeOnLongPress(onArrange: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val label = stringResource(R.string.explore_arrange_enter)
    val arrange by rememberUpdatedState(onArrange)
    return Modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    arrange()
                },
            )
        }
        .semantics { onLongClick(label) { arrange(); true } }
}

/**
 * 整理时的顶栏：占住搜索栏的位置，标题「整理货架」，右边「添加货架」与「完成」（页面动作放顶栏右侧；完成是填充按钮，
 * 与面板标题行右端的确认同一个位置与强调）。分类标签栏照旧在下面，整理时也能切分类。
 */
@Composable
internal fun ArrangeBar(onAdd: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 8.dp)
            .testTag("explore_arrange_bar"),
    ) {
        Text(
            text = stringResource(R.string.explore_arrange_title),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAdd, modifier = Modifier.testTag("explore_arrange_add")) {
            Icon(painterResource(R.drawable.add_24), contentDescription = stringResource(R.string.explore_arrange_add))
        }
        FilledIconButton(onClick = onDone, modifier = Modifier.testTag("explore_arrange_done")) {
            Icon(painterResource(R.drawable.check_24), contentDescription = stringResource(R.string.explore_arrange_done))
        }
    }
}

/** 整理列表的一行：画出来的那条货架（名字、内容）与布局里它那一条（来源、样式）。 */
internal data class ArrangeRow(val shelf: ExploreShelf, val entry: ShelfEntry)

/** 整理列表：这一页画着的货架（近期热门固定不动，不在里面），顺序即页面上的顺序。 */
internal fun ExploreLayout.arrangeRows(): List<ArrangeRow> {
    val byId = shelfLayout.associateBy { it.id }
    return shelves.mapNotNull { shelf -> byId[shelf.id]?.let { ArrangeRow(shelf, it) } }
}

/**
 * 一个分类的整理列表（原型 `.elist`）：拖把手排序、左滑移除、点一行编辑（[ReorderableList]）。
 * 一条都没有时只有一行说明，顶栏的「添加货架」照样能加。
 */
@Composable
internal fun ArrangeList(
    type: SubjectType,
    layout: ExploreLayout,
    typeContent: ExploreTypeContent,
    actions: ArrangeListActions,
    modifier: Modifier = Modifier,
) {
    val rows = layout.arrangeRows()
    if (rows.isEmpty()) {
        EmptyLine(stringResource(R.string.explore_arrange_empty), modifier.testTag("explore_arrange_empty"))
        return
    }
    ReorderableList(
        items = rows,
        key = { it.entry.id },
        onMove = { ids -> actions.move(type, ids) },
        onRemove = { actions.remove(type, it.entry.id) },
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        modifier = modifier,
    ) { row ->
        ArrangeItem(row, typeContent, onEdit = { actions.openEditor(type, row.entry) })
    }
}

@Composable
private fun ReorderableItemScope.ArrangeItem(row: ArrangeRow, typeContent: ExploreTypeContent, onEdit: () -> Unit) {
    SegmentedItem(
        index = segmentIndex,
        count = segmentCount,
        onClick = onEdit,
        containerColor = containerColor,
        leadingContent = { ArrangeLead(row.shelf, typeContent) },
        trailingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .dragHandle()
                    .testTag("explore_arrange_handle_${row.entry.id}"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.drag_indicator_24),
                    contentDescription = stringResource(R.string.reorder_drag_handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        supportingContent = {
            MetaText(parts = arrangeRowDetail(row), color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.testTag("explore_arrange_row_${row.entry.id}"),
    ) {
        Text(exploreTitleText(row.shelf.title), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 一行的说明：作品货架是样式，按词取的再加排序（固定货架的排序不能改，不写）；方块货架是「方块」与画法。
 */
@Composable
private fun arrangeRowDetail(row: ArrangeRow): List<String> = when (val shelf = row.shelf) {
    is ExploreShelf.Works -> {
        val content = shelf.content
        val fixed = (row.entry as? WorkShelfEntry)?.source is WorkShelfSource.Fixed
        listOfNotNull(
            stringResource(shelf.style.labelRes()),
            (content as? WorkShelfContent.Search)?.criteria?.takeUnless { fixed }?.let { criteria ->
                stringResource(criteria.effectiveSort().labelRes(isTagBrowse = criteria.isTagBrowse()))
            },
        )
    }
    is ExploreShelf.Tiles -> listOf(stringResource(R.string.explore_shelf_kind_tiles), stringResource(shelf.drawing.labelRes()))
    is ExploreShelf.Trending -> emptyList()
}

/**
 * 一行打头的图：作品货架叠三张这条货架的封面（还没取到时是封面骨架），方块货架是方块图标（原型 `.erow .lead`）。
 * 叠法：从左往右错开 7dp、各转 −10°、−5°、0°，第一部在最上面——我的设计决定（not in M3）。
 */
@Composable
private fun ArrangeLead(shelf: ExploreShelf, typeContent: ExploreTypeContent) {
    Box(Modifier.size(width = LeadWidth, height = LeadHeight), contentAlignment = Alignment.CenterStart) {
        when (shelf) {
            is ExploreShelf.Works -> LeadCovers(typeContent.worksOf(shelf))
            is ExploreShelf.Tiles, is ExploreShelf.Trending -> Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.grid_view_24), contentDescription = null) }
            }
        }
    }
}

@Composable
private fun LeadCovers(works: Load<List<SubjectBo>>) {
    val subjects = works.valueOrNull()?.take(LEAD_COVERS)
    // 第一部画在最后（最上面）
    (0 until LEAD_COVERS).reversed().forEach { position ->
        val layer = LEAD_COVERS - 1 - position
        val cover = Modifier
            .width(LeadCoverWidth)
            .offset(x = LeadCoverStep * layer)
            .graphicsLayer { rotationZ = LeadCoverTilt * (layer - (LEAD_COVERS - 1)) }
        when {
            subjects == null -> SubjectPosterSkeletonBox(cover)
            position < subjects.size -> SubjectPoster(url = subjects[position].coverUrl, modifier = cover, shadowElevation = 1.dp)
        }
    }
}

private const val LEAD_COVERS = 3
private val LeadWidth = 44.dp
private val LeadHeight = 56.dp
private val LeadCoverWidth = 30.dp
private val LeadCoverStep = 7.dp
private const val LeadCoverTilt = 5f

/** 作品货架样式的名字（唯一映射）。 */
@StringRes
internal fun WorkShelfStyle.labelRes(): Int = when (this) {
    WorkShelfStyle.POSTER_ROW -> R.string.explore_style_poster_row
    WorkShelfStyle.FOCUS -> R.string.explore_style_focus
    WorkShelfStyle.CHART -> R.string.explore_style_chart
    WorkShelfStyle.BENTO -> R.string.explore_style_bento
    WorkShelfStyle.COVER_FLOW -> R.string.explore_style_cover_flow
    WorkShelfStyle.DECK -> R.string.explore_style_deck
    WorkShelfStyle.SPINE -> R.string.explore_style_spine
    WorkShelfStyle.TIMELINE -> R.string.explore_style_timeline
}

/** 方块画法的名字（唯一映射）。 */
@StringRes
internal fun TileDrawing.labelRes(): Int = when (this) {
    TileDrawing.COLOR -> R.string.explore_drawing_color
    TileDrawing.STACK -> R.string.explore_drawing_stack
    TileDrawing.COVER -> R.string.explore_drawing_cover
    TileDrawing.LOGO -> R.string.explore_drawing_logo
    TileDrawing.RULER -> R.string.explore_drawing_ruler
}
