package io.github.touhougo.chogumi.core.ui.tour

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.DraggedElevation
import io.github.touhougo.chogumi.core.ui.composables.GridPadding
import io.github.touhougo.chogumi.core.ui.composables.GridSpacing
import io.github.touhougo.chogumi.core.ui.composables.PosterShape
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 货架上一张封面的宽：海报卡在手机上大约这么宽（设计决定，not in M3）。 */
private val ShelfCoverWidth = 96.dp

/** 一条货架露出几张封面（演示台右边裁掉一截，像真的横滑货架）。 */
private const val ShelfCovers = 5

/** 顶上一栏的高：探索页的搜索栏、整理时的整理栏（ExploreArrange.kt `ArrangeBar` 至少 64）。 */
private val TopBarHeight = 64.dp

/*
 * 整理列表一行打头的叠封面，照 feature:explore 整理列表的取值（ExploreArrange.kt：区域 44 × 56、封面宽 30、每张错开 7、
 * 各转 −10°、−5°、0°）。那是 feature:explore 的私有常量，这里读不到，演示照它们写一份。
 */
private val LeadWidth = 44.dp
private val LeadHeight = 56.dp
private val LeadCoverWidth = 30.dp
private val LeadCoverStep = 7.dp
private const val LeadCoverTilt = 5f
private const val LeadCovers = 3

/** 演示里的三条货架。 */
private const val ShelfCount = 3

private const val RestMillis = 700L
private const val LookMillis = 700L
private const val DroppedMillis = 1000L

/** 脚本要按的几个位置（演示台坐标，px）：放置时记下，只给脚本读，不是界面状态。 */
private class ArrangeTargets {
    var header = Offset.Zero
    var handle = Offset.Zero
    var rowStep = 0f
}

/**
 * 第四页：按住第一条货架的标题，页面换成整理列表（顶栏换成整理栏），按住第一行的把手往下拖过第二行、松手，
 * 再回到货架，两条换了位置。整理列表是分段列表的样子（[ListItemDefaults.segmentedShapes]、`surfaceContainer`），
 * 拖着的那一行与拖动排序同一套（升一级底色、[DraggedElevation] 的阴影、拖着时整块圆角）。
 */
@Composable
internal fun ArrangeDemo(playing: Boolean, modifier: Modifier = Modifier) {
    TourStage(modifier) { stage ->
        val motion = MaterialTheme.motionScheme
        val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
        val finger = remember { TourFinger() }
        val targets = remember { ArrangeTargets() }
        val gap = with(LocalDensity.current) { ListItemDefaults.SegmentedGap.toPx() }
        var order by remember { mutableStateOf(List(ShelfCount) { it }) }
        var arranging by remember { mutableStateOf(false) }
        var dragging by remember { mutableStateOf<Int?>(null) }
        // 拖动从哪按下：拖着的行跟着手指走，偏移是手指离它的竖直距离
        var dragStart by remember { mutableStateOf(Offset.Zero) }
        val yield = remember { Animatable(0f) }

        LaunchedEffect(playing) {
            finger.reset()
            order = List(ShelfCount) { it }
            arranging = false
            dragging = null
            yield.snapTo(0f)
            if (!playing) return@LaunchedEffect
            while (true) {
                delay(RestMillis)
                finger.press(targets.header)
                delay(longPressMillis)
                finger.lift()
                arranging = true
                delay(LookMillis)
                val start = targets.handle
                finger.press(start)
                delay(longPressMillis)
                val moving = order.first()
                dragStart = start
                dragging = moving
                val step = targets.rowStep
                coroutineScope {
                    // 拖着的行中线越过下一行，下一行让到上面去（与拖动排序同一条规则）
                    launch {
                        snapshotFlow { finger.position.value.y - start.y }.first { it > step / 2 }
                        yield.animateTo(-step, motion.defaultSpatialSpec())
                    }
                    finger.position.animateTo(start + Offset(0f, step), motion.slowSpatialSpec())
                }
                finger.lift()
                order = order.drop(1).take(1) + moving + order.drop(2)
                dragging = null
                yield.snapTo(0f)
                delay(DroppedMillis)
                arranging = false
            }
        }

        Crossfade(arranging, animationSpec = motion.defaultEffectsSpec(), label = "tour_arrange") { listShown ->
            Column(Modifier.fillMaxSize()) {
                if (listShown) {
                    DemoArrangeBar()
                    Column(
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                        modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding).padding(top = 8.dp),
                    ) {
                        order.forEachIndexed { index, shelf ->
                            val isDragged = shelf == dragging
                            val isYielding = dragging != null && index == 1
                            DemoArrangeRow(
                                index = index,
                                count = order.size,
                                dragged = isDragged,
                                onHandlePlaced = if (index == 0) { coordinates -> targets.handle = stage.centerOf(coordinates) } else null,
                                modifier = Modifier
                                    .zIndex(if (isDragged) 1f else 0f)
                                    .graphicsLayer { translationY = if (isDragged) finger.position.value.y - dragStart.y else if (isYielding) yield.value else 0f }
                                    // 行等高：一步是一行的高加行间距
                                    .then(if (index == 0) Modifier.onPlaced { row -> targets.rowStep = row.size.height + gap } else Modifier),
                            )
                        }
                    }
                } else {
                    // 探索页顶上的搜索栏
                    SkeletonBox(
                        Modifier
                            .padding(horizontal = ChogumiDimens.ScreenPadding, vertical = 4.dp)
                            .fillMaxWidth()
                            .height(TopBarHeight - 8.dp),
                        CircleShape,
                    )
                    order.forEachIndexed { index, _ ->
                        DemoShelf(onHeaderPlaced = if (index == 0) { coordinates -> targets.header = stage.centerOf(coordinates) } else null)
                    }
                }
            }
        }
        FingerDot(finger)
    }
}

/** 探索页的一条货架：标题一行（货架标题的版式，titleMedium，内边距 16 / 12），下面一排封面。 */
@Composable
private fun DemoShelf(onHeaderPlaced: ((LayoutCoordinates) -> Unit)?) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (onHeaderPlaced != null) Modifier.onPlaced(onHeaderPlaced) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SkeletonText(MaterialTheme.typography.titleMedium, widthFraction = 0.35f)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            modifier = Modifier.padding(start = GridPadding),
        ) {
            repeat(ShelfCovers) {
                SkeletonBox(Modifier.width(ShelfCoverWidth).height(ShelfCoverWidth / ChogumiDimens.CoverRatio), PosterShape)
            }
        }
    }
}

/** 整理栏：标题「整理货架」，右边添加与完成（与探索页整理时的顶栏同样的字、图标与按钮）。 */
@Composable
private fun DemoArrangeBar() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = TopBarHeight).padding(start = 16.dp, end = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.explore_arrange_title),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.add_24), contentDescription = null)
        }
        FilledIconButton(onClick = {}) {
            Icon(painterResource(R.drawable.check_24), contentDescription = null)
        }
    }
}

/**
 * 整理列表的一行：打头叠三张封面，标题与说明各一条，尾部是拖动把手。[dragged] 时与拖动排序拖着的那一行一样：
 * 整块圆角、升一级底色、抬起的阴影。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DemoArrangeRow(
    index: Int,
    count: Int,
    dragged: Boolean,
    onHandlePlaced: ((LayoutCoordinates) -> Unit)?,
    modifier: Modifier,
) {
    val shape = if (dragged) ListItemDefaults.segmentedShapes(index = 0, count = 1).shape else ListItemDefaults.segmentedShapes(index, count).shape
    val color = if (dragged) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
    Surface(shape = shape, color = color, shadowElevation = if (dragged) DraggedElevation else 0.dp, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Box(Modifier.size(width = LeadWidth, height = LeadHeight), contentAlignment = Alignment.CenterStart) {
                (0 until LeadCovers).reversed().forEach { layer ->
                    SkeletonBox(
                        Modifier
                            .width(LeadCoverWidth)
                            .height(LeadCoverWidth / ChogumiDimens.CoverRatio)
                            .offset(x = LeadCoverStep * layer)
                            .graphicsLayer { rotationZ = -LeadCoverTilt * (LeadCovers - 1 - layer) },
                        PosterShape,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                SkeletonText(MaterialTheme.typography.bodyLarge, widthFraction = 0.5f)
                SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.35f)
            }
            Box(
                Modifier
                    .size(48.dp)
                    .then(if (onHandlePlaced != null) Modifier.onPlaced(onHandlePlaced) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.drag_indicator_24), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
