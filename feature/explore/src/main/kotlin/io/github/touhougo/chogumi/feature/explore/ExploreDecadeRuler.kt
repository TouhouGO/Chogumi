package io.github.touhougo.chogumi.feature.explore

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTile
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.RulerTrack
import io.github.touhougo.chogumi.core.ui.composables.rememberRulerTrack
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.roundToInt

/**
 * 刻度尺（年代，原型 `.ruler`）：一把横着拖的尺子，一格一个年代，最新的在最左（方块按年代排的顺序，见 `decadeTiles`）；
 * 指针在第一格正中，对准哪一格，那一格的字放大、变 primary，每过一格轻震一下（拖动内核 [rememberRulerTrack]，与章节的格子尺同一份）。
 * 点哪一格尺子就滑到哪一格。
 *
 * 下面一行是尺子停在的那个年代的作品：**停稳了才换**（[RulerTrack.settled]），换上时淡入一下；拖动途中只换读数，不换内容、不发请求
 * （[onShown] 只报停过的那一块）。标题的箭头进停在的那个年代的列表。停在哪一格跟着这一组方块存下（[rememberShelfIndex]）：
 * 进详情再回来还停在原处，方块换了就从头开始。
 */
@Composable
internal fun DecadeRuler(
    shelf: ExploreShelf.Tiles,
    title: String,
    /** 长按标题进整理。 */
    onArrange: () -> Unit,
    typeContent: ExploreTypeContent,
    onShown: (List<ExploreTile>) -> Unit,
    onSubjectClick: (Int) -> Unit,
    onOpenSearch: (criteria: SearchCriteria, title: String?, openFilters: Boolean) -> Unit,
) {
    val tiles = shelf.tiles
    if (tiles.isEmpty()) return
    val key = remember(shelf.id, tiles) { "${shelf.id}|" + tiles.joinToString(",") { it.label.key } }
    val resting = rememberShelfIndex(key)
    val restIndex = resting.intValue.coerceIn(0, tiles.lastIndex)
    var aimed by remember(key) { mutableIntStateOf(restIndex) }
    val track = rememberRulerTrack(
        value = aimed,
        last = tiles.lastIndex,
        cell = RulerSegmentWidth,
        gap = 0.dp,
        snapPosition = SnapPosition.Start,
        onValueChange = { aimed = it },
    )
    // 换内容的淡入是「停稳了」这个事件的反馈：在事件里开始，同一格停两次不重放（docs/code-standards.md「一次性反馈属于事件，不属于状态」）
    val swap = remember { Animatable(1f) }
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(track, key) {
        snapshotFlow { track.aimed.takeIf { track.settled }?.coerceIn(0, tiles.lastIndex) }
            .filterNotNull()
            .collect { index ->
                if (index == resting.intValue) return@collect
                resting.intValue = index
                swap.snapTo(SWAP_FADE_FROM)
                swap.animateTo(1f, effects)
            }
    }
    val restingTile = tiles[restIndex]
    LaunchedEffect(shelf.id, restingTile) { onShown(listOf(restingTile)) }
    val restingLabel = exploreTileLabelText(restingTile.label)
    val labels = tiles.map { exploreTileLabelText(it.label) }

    ShelfHeader(title = title, onArrange = onArrange, onArrowClick = { onOpenSearch(restingTile.criteria, restingLabel, false) })
    RulerBand(track, labels, aimed, description = title, onPick = { aimed = it })
    Box(Modifier.graphicsLayer { alpha = swap.value }) {
        RulerWorks(typeContent.tileWorks(shelf.id, restingTile), onSubjectClick)
    }
}

/**
 * 尺身：一格宽 [RulerSegmentWidth]，左边留 16、右边留到最后一格也能停到指针下；每格上面是年代，下面是刻度（一格十年，
 * 年代起点一根长刻度）。对读屏是一个滑块：念货架名与对准的年代，能直接设值。
 */
@Composable
private fun RulerBand(track: RulerTrack, labels: List<String>, aimed: Int, description: String, onPick: (Int) -> Unit) {
    val last = labels.lastIndex
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .testTag("explore_decade_ruler")
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = labels[aimed.coerceIn(0, last)]
                progressBarRangeInfo = ProgressBarRangeInfo(aimed.toFloat(), 0f..last.toFloat(), steps = (last - 1).coerceAtLeast(0))
                setProgress { target ->
                    onPick(target.roundToInt().coerceIn(0, last))
                    true
                }
            },
    ) {
        LazyRow(
            state = track.state,
            flingBehavior = track.flingBehavior,
            contentPadding = PaddingValues(start = RulerInset, end = (maxWidth - RulerInset - RulerSegmentWidth).coerceAtLeast(0.dp)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(labels) { index, label -> RulerSegment(label, selected = index == aimed, onClick = { onPick(index) }) }
        }
        // 指针：第一格正中，比长刻度高一截
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = RulerInset + RulerSegmentWidth / 2 - NeedleWidth / 2)
                .size(width = NeedleWidth, height = NeedleHeight)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

/**
 * 尺子的一格：年代写在上面（对准时 titleLarge、primary，否则 bodyMedium、onSurfaceVariant；字位按大的那档留高，换档时尺子不跳），
 * 下面是刻度。点一格尺子滑过去：尺子自己动就是反馈，不另画水波纹。
 */
@Composable
private fun RulerSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val labelColor by animateColorAsState(
        if (selected) colors.primary else colors.onSurfaceVariant,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "decade_label",
    )
    val big = MaterialTheme.typography.titleLarge
    val slot = with(LocalDensity.current) { big.lineHeight.toDp() } + RulerLabelGap
    val minor = colors.outlineVariant
    val major = colors.outline
    Column(
        Modifier
            .width(RulerSegmentWidth)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().height(slot), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = if (selected) big else MaterialTheme.typography.bodyMedium,
                color = labelColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(MajorTickHeight)) {
            val step = size.width / TICKS_PER_SEGMENT
            val minorWidth = MinorTickWidth.toPx()
            val minorHeight = MinorTickHeight.toPx()
            for (tick in 1 until TICKS_PER_SEGMENT) {
                drawRect(minor, Offset(step * tick, size.height - minorHeight), Size(minorWidth, minorHeight))
            }
            drawRect(major, Offset.Zero, Size(MajorTickWidth.toPx(), size.height))
        }
    }
}

/** 尺子下面那一行：停在的年代排在前面的作品，与海报行同一种卡（[PosterRow]）；这个年代一部都没有时一行空态，取失败时不画。 */
@Composable
private fun RulerWorks(works: Load<List<SubjectBo>>, onSubjectClick: (Int) -> Unit) {
    when (sectionStateOf(works)) {
        SectionState.LOADING -> PosterRow(null, onSubjectClick)
        SectionState.CONTENT -> PosterRow(works.valueOrNull().orEmpty(), onSubjectClick)
        SectionState.EMPTY -> EmptyLine(stringResource(R.string.empty_search))
        SectionState.ERROR -> Unit
    }
}

/** 换上新年代的作品时从这个不透明度淡入（原型 `rswap` 的 .25）。我的设计决定（not in M3）。 */
private const val SWAP_FADE_FROM = 0.25f

/** 一格的宽（原型 `.rd`）。 */
private val RulerSegmentWidth = 128.dp

/** 尺子左边的留白，与货架的左边距一致。 */
private val RulerInset = 16.dp

/** 年代字下面到刻度的间隔。我的设计决定（not in M3）。 */
private val RulerLabelGap = 8.dp

/** 一格十年，一年一根刻度（原型 `.rd` 的 12.8px 间隔）。 */
private const val TICKS_PER_SEGMENT = 10

/** 刻度的尺寸（原型 `.rd` 与 `.rd::before`）。我的设计决定（not in M3）。 */
private val MinorTickWidth = 1.dp
private val MinorTickHeight = 12.dp
private val MajorTickWidth = 2.dp
private val MajorTickHeight = 22.dp

/** 指针的尺寸（原型 `.needle`）。我的设计决定（not in M3）。 */
private val NeedleWidth = 3.dp
private val NeedleHeight = 30.dp
