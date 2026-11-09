package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.EpisodeCellKind
import io.github.touhougo.chogumi.core.res.R
import kotlin.math.roundToInt

/**
 * 格子尺（原型 `.ruler`）：0 到 [last] 一格一个，横着拖，正中框住的那一格就是值，松手吸到一格上。
 * 与章节方格同一套格子（大小与格距取 [GridCell.EpisodeTile]，颜色、形状与字取 TileStyle）：值之前连成一段的是已经过的，
 * 值那一格 primary 实色，之后是描边空位；[limit] 之后是还没有的灰格，拖不过去、也点不了。点哪一格就到哪一格；
 * [latest] 那一格下面标「最新」。两头渐隐，看得出还能往两边拖。
 *
 * 拖动、吸附与跟值的规矩在 [rememberRulerTrack]（与探索的年代尺共用）：值由调用方持有，拖动途中经 [onValueChange] 报出，
 * 调用方改了值尺子滑过去；[onFollowingFinger] 报出「手正拖着或甩着」。
 * 对读屏是一个滑块：念 [description] 与当前格的字（[labelOf]），能直接设值。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TileRuler(
    value: Int,
    last: Int,
    labelOf: (Int) -> String,
    onValueChange: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    limit: Int = last,
    latest: Int? = null,
    onFollowingFinger: (Boolean) -> Unit = {},
) {
    val cell = GridCell.EpisodeTile.minWidth
    val gap = GridCell.EpisodeTile.spacing
    val shown = value.coerceIn(0, last)
    val reach by rememberUpdatedState(limit.coerceIn(0, last))
    val change by rememberUpdatedState(onValueChange)
    val track = rememberRulerTrack(shown, last, cell, gap, SnapPosition.Center, onValueChange, onFollowingFinger)
    val state = track.state
    // [limit] 之后拖不过去：往前的位移超过剩下的距离时，多出的在尺子外层吃掉（拖动与甩动、吸附都经这里）
    val stop = remember(track) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.x >= 0f) return Offset.Zero
                val room = (reach * track.stepPx - track.scrolledPx()).coerceAtLeast(0).toFloat()
                return if (-available.x > room) Offset(available.x + room, 0f) else Offset.Zero
            }
        }
    }
    val latestText = stringResource(R.string.ruler_latest)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = description
                stateDescription = labelOf(shown)
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..last.toFloat(), steps = (last - 1).coerceAtLeast(0))
                setProgress { target ->
                    change(target.roundToInt().coerceIn(0, reach))
                    true
                }
            },
    ) {
        LazyRow(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FrameInset)
                .nestedScroll(stop)
                .fadeEdges(EdgeFade),
            // 两边各留半屏：第一格和最后一格也能停在正中
            contentPadding = PaddingValues(horizontal = (maxWidth - cell) / 2),
            horizontalArrangement = Arrangement.spacedBy(gap),
            flingBehavior = track.flingBehavior,
        ) {
            items(count = last + 1) { index ->
                Column(modifier = Modifier.width(cell), horizontalAlignment = Alignment.CenterHorizontally) {
                    RulerTile(
                        label = labelOf(index),
                        kind = when {
                            index == shown && shown > 0 -> EpisodeCellKind.CURRENT
                            index in 1 until shown -> EpisodeCellKind.WATCHED
                            index > reach -> EpisodeCellKind.UNAIRED
                            else -> EpisodeCellKind.AIRED
                        },
                        // 1 到值连成一段（0 是「一格都没有」，不进段）
                        join = TileJoin(start = index in 2..shown, end = index in 1 until shown),
                        size = cell,
                        enabled = index <= reach,
                        onClick = { change(index) },
                    )
                    if (latest != null) {
                        // 每格都留出这一行：标「最新」的那一格滑出屏幕时尺子不变矮
                        Text(
                            text = latestText,
                            style = MaterialTheme.typography.labelSmallEmphasized,
                            color = if (index == latest) MaterialTheme.colorScheme.primary else Color.Transparent,
                            maxLines = 1,
                            modifier = Modifier.padding(top = FrameInset).hiddenWhenWiderThanSlot(),
                        )
                    }
                }
            }
        }
        // 正中的框：比格子四周大 [FrameInset]，圆角与格子同心
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .size(cell + FrameInset * 2)
                .border(FrameStroke, MaterialTheme.colorScheme.primary, RoundedCornerShape(FrameCorner)),
        )
    }
}

/**
 * 一行字比所在的格宽就不画（行高照留）：吸附、居中和读出的值都按「每格一样宽」算，字撑宽一格会让它们对不上；
 * 与 [TileLabel] 放不下就丢字同一做法。
 */
private fun Modifier.hiddenWhenWiderThanSlot(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
    val fits = placeable.width <= constraints.maxWidth
    layout(if (fits) placeable.width else 0, placeable.height) { if (fits) placeable.place(0, 0) }
}

@Composable
private fun RulerTile(label: String, kind: EpisodeCellKind, join: TileJoin, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val shape = rememberMorphingShape(MaterialTheme.shapes.tileShape(join), MaterialTheme.motionScheme.fastSpatialSpec())
    val container by animateColorAsState(colors.tileContainer(kind), effects, label = "rulerTileContainer")
    val content by animateColorAsState(colors.tileContent(kind), effects, label = "rulerTileContent")
    val stroke by animateColorAsState(
        colors.tileBorder(kind, preview = null, target = false).color,
        effects,
        label = "rulerTileStroke",
    )
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(container)
            .border(TileStroke, stroke, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TileLabel(number = { TileNumber(text = label, emphasized = kind.numberEmphasized, color = content) })
    }
}

/** 两头渐隐：把内容在左右 [width] 内淡到透明（不依赖背景色）。 */
private fun Modifier.fadeEdges(width: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val edge = (width.toPx() / size.width).coerceAtMost(0.5f)
        drawRect(
            brush = Brush.horizontalGradient(0f to Color.Transparent, edge to Color.Black, 1f - edge to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }

/** 正中的框比格子四周各大多少，「最新」离格子多远（设计稿 `.ruler .frame`，not in M3）。 */
private val FrameInset = 6.dp

/** 框线宽（设计稿，not in M3）。 */
private val FrameStroke = 2.dp

/** 框的圆角：格子的 medium 圆角（12dp）加 [FrameInset]，与格子同心（设计决定，not in M3）。 */
private val FrameCorner = 18.dp

/** 两头渐隐的宽度（设计稿 `.ruler .fade`，not in M3）。 */
private val EdgeFade = 48.dp
