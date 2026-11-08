package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EpisodeGrid
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.res.R

/* 超过一百集时方格上方的分段导航条（[EpisodeTileGrid]）：每段一格，选中段宽、写着集号范围。 */

/** 导航条的框架（真实与骨架共用）：整条 48dp 高，是触控区。 */
@Composable
internal fun NavigatorFrame(modifier: Modifier = Modifier, content: @Composable BoxWithConstraintsScope.() -> Unit) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .height(NavigatorHeight),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/**
 * 分段导航条：整部按段排成一条，每段填多少就是这一段看了多少；选中段变宽变高、写出「201–300」，点或按住拖动切换，
 * 拖过一段轻震一下。选中项变宽、相邻项让位是 M3 按钮组的做法，选中段写出区间像滑块的数值标签（buttons.md / input.md）；
 * 段高、宽度比例与填色深浅是我的设计决定（not in M3，取自设计稿）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EpisodeRangeNavigator(grid: EpisodeGrid, onSelect: (Int) -> Unit) {
    val ranges = grid.ranges
    val labels = ranges.map { "${formatEpisodeSort(it.first().sort)}–${formatEpisodeSort(it.last().sort)}" }
    val labelStyle = MaterialTheme.typography.labelMediumEmphasized
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    NavigatorFrame(Modifier.selectableGroup()) {
        val gap = with(density) { NavigatorGap.roundToPx() }
        val labelWidth = measurer.measure(labels[grid.selectedRange], labelStyle).size.width
        val widths = rangeSegmentWidths(
            count = ranges.size,
            selected = grid.selectedRange,
            available = constraints.maxWidth - gap * (ranges.size - 1),
            minSelected = labelWidth + with(density) { NavigatorLabelPadding.roundToPx() } * 2,
        )
        val currentWidths by rememberUpdatedState(widths)
        val currentSelected by rememberUpdatedState(grid.selectedRange)
        val currentOnSelect by rememberUpdatedState(onSelect)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(gap) {
                    // 按下即选，按住拖动跟着手指换段；拖动不交给外层（不翻选项卡、不滚页面）
                    awaitEachGesture {
                        var shown = currentSelected
                        val pick: (Float) -> Unit = { x ->
                            val index = rangeSegmentAt(x, currentWidths, gap)
                            if (index != shown) {
                                shown = index
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                currentOnSelect(index)
                            }
                        }
                        pick(awaitFirstDown().position.x)
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) {
                                    pick(change.position.x)
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(NavigatorGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ranges.forEachIndexed { index, range ->
                RangeSegment(
                    label = labels[index],
                    description = stringResource(
                        R.string.episode_range_desc,
                        formatEpisodeSort(range.first().sort),
                        formatEpisodeSort(range.last().sort),
                        grid.rangeWatched[index],
                    ),
                    width = with(density) { widths[index].toDp() },
                    watched = grid.rangeWatched[index],
                    size = range.size,
                    selected = index == grid.selectedRange,
                    first = index == 0,
                    last = index == ranges.lastIndex,
                    onSelect = { onSelect(index) },
                )
            }
        }
    }
}

/**
 * 导航条各段的宽（像素）：选中段按 [NAVIGATOR_SELECTED_WEIGHT] 比 1 分得更宽，但至少放得下区间文字（[minSelected]）；
 * 其余各段平分余下的宽，除不尽的像素从左往右逐段多分 1。
 */
internal fun rangeSegmentWidths(count: Int, selected: Int, available: Int, minSelected: Int): List<Int> {
    if (count == 1) return listOf(available)
    val weighted = (available * NAVIGATOR_SELECTED_WEIGHT / (count - 1 + NAVIGATOR_SELECTED_WEIGHT)).toInt()
    val selectedWidth = maxOf(weighted, minSelected).coerceAtMost(available)
    val rest = available - selectedWidth
    val each = rest / (count - 1)
    val remainder = rest % (count - 1)
    var given = 0
    return List(count) { index ->
        if (index == selected) {
            selectedWidth
        } else {
            each + if (given++ < remainder) 1 else 0
        }
    }
}

/** 横坐标 [x] 落在哪一段：段间距算给左边那一段，超出两端取首末段。 */
internal fun rangeSegmentAt(x: Float, widths: List<Int>, gap: Int): Int {
    var end = 0
    widths.forEachIndexed { index, width ->
        end += width + gap
        if (x < end) return index
    }
    return widths.lastIndex
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RangeSegment(
    label: String,
    description: String,
    width: Dp,
    watched: Int,
    size: Int,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    onSelect: () -> Unit,
) {
    val shapes = MaterialTheme.shapes
    val colors = MaterialTheme.colorScheme
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Color>()
    val animatedWidth by animateDpAsState(width, spatial, label = "rangeSegmentWidth")
    val height by animateDpAsState(if (selected) NavigatorSelectedHeight else NavigatorSegmentHeight, spatial, label = "rangeSegmentHeight")
    val targetShape: CornerBasedShape = if (selected) {
        CircleShape
    } else {
        shapes.extraSmall.copy(
            topStart = if (first) CircleShape.topStart else shapes.extraSmall.topStart,
            bottomStart = if (first) CircleShape.bottomStart else shapes.extraSmall.bottomStart,
            topEnd = if (last) CircleShape.topEnd else shapes.extraSmall.topEnd,
            bottomEnd = if (last) CircleShape.bottomEnd else shapes.extraSmall.bottomEnd,
        )
    }
    val shape = rememberMorphingShape(targetShape, MaterialTheme.motionScheme.fastSpatialSpec())
    val fillTarget = when {
        selected -> colors.primaryContainer
        watched == size -> colors.primary
        else -> colors.primary.copy(alpha = NAVIGATOR_PARTIAL_FILL_ALPHA)
    }
    val track by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainerHighest, effects, label = "rangeSegmentTrack")
    val fill by animateColorAsState(fillTarget, effects, label = "rangeSegmentFill")
    val fraction by animateFloatAsState(watched.toFloat() / size, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "rangeSegmentFraction")
    val labelAlpha by animateFloatAsState(if (selected) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "rangeSegmentLabel")
    Box(
        modifier = Modifier
            .width(animatedWidth)
            .height(height)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Tab
                this.selected = selected
                onClick {
                    onSelect()
                    true
                }
            }
            .clip(shape)
            .background(track),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(fill),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMediumEmphasized,
            color = colors.onSecondaryContainer,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.graphicsLayer { alpha = labelAlpha },
        )
    }
}

// ---------------------------------------------------------------------------
// 分段导航条的尺寸与填色
// ---------------------------------------------------------------------------

/** 分段导航条：整条高（触控区）、段间距、未选段高、选中段高、选中段文字左右留白（设计稿，not in M3）。 */
private val NavigatorHeight = 48.dp
private val NavigatorGap = 2.dp
internal val NavigatorSegmentHeight = 12.dp
private val NavigatorSelectedHeight = 32.dp
private val NavigatorLabelPadding = 12.dp

/** 选中段与其余各段的宽度比（设计稿 `flex-grow: 5.2`，not in M3）。 */
private const val NAVIGATOR_SELECTED_WEIGHT = 5.2f

/** 没看完的段填 55% 的 primary，看完的段填满色（设计稿，not in M3）。 */
private const val NAVIGATOR_PARTIAL_FILL_ALPHA = 0.55f
