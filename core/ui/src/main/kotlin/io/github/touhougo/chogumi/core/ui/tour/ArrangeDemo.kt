package io.github.touhougo.chogumi.core.ui.tour

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.PageTransitionKind
import io.github.touhougo.chogumi.core.ui.common.navigation.chogumiTransition
import io.github.touhougo.chogumi.core.ui.composables.ArrangeBar
import io.github.touhougo.chogumi.core.ui.composables.ContainedSearchEntry
import io.github.touhougo.chogumi.core.ui.composables.CoverStack
import io.github.touhougo.chogumi.core.ui.composables.CoverStackSize
import io.github.touhougo.chogumi.core.ui.composables.DraggedElevation
import io.github.touhougo.chogumi.core.ui.composables.GridPadding
import io.github.touhougo.chogumi.core.ui.composables.GridSpacing
import io.github.touhougo.chogumi.core.ui.composables.SectionHeaderSkeleton
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.reorderRowColor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 一条货架露出几张封面（演示台右边裁掉一截，像真的横滑货架）。 */
private const val ShelfCovers = 5

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
 * 再回到货架，两条换了位置。画的是探索页同样的东西：搜索入口 [ContainedSearchEntry]、货架标题的骨架 [SectionHeaderSkeleton]、
 * 整理栏 [ArrangeBar]、分段列表的行 [SegmentedItem] 打头叠封面 [CoverStack]；拖着的那一行与拖动排序同一套
 * （[reorderRowColor] 的底色、[DraggedElevation] 的阴影、单独成一段的整块圆角）。
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

        // 与探索页进出整理同一个转场：先淡出再淡入，两层不同时半透明
        val pageMotion = LocalAppMotion.current.page
        AnimatedContent(
            targetState = arranging,
            transitionSpec = { chogumiTransition(PageTransitionKind.TopLevel, pageMotion, 1).using(null) },
            label = "tour_arrange",
        ) { listShown ->
            // 页面是竖着滚的：每条货架都按自己的高排整条，放不下的由演示台裁掉，不被挤进剩下的高度
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = false)) {
                if (listShown) {
                    ArrangeBar(onAdd = {}, onDone = {})
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
                    // 探索页顶上的搜索入口（与探索页同样的摆法）
                    ContainedSearchEntry(
                        placeholder = stringResource(R.string.search_hint),
                        onClick = {},
                        modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding).padding(top = 8.dp),
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

/**
 * 探索页的一条货架：标题一行（货架标题的骨架），下面一排封面。封面排在一条横滑的行里（不接手势），
 * 每张都是整张，超出的由演示台裁掉，和真货架一样；不横滑时最后一张会被挤成剩下的宽度。
 */
@Composable
private fun DemoShelf(onHeaderPlaced: ((LayoutCoordinates) -> Unit)?) {
    Column {
        SectionHeaderSkeleton(if (onHeaderPlaced != null) Modifier.onPlaced(onHeaderPlaced) else Modifier)
        Row(
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            modifier = Modifier.horizontalScroll(rememberScrollState(), enabled = false).padding(start = GridPadding),
        ) {
            repeat(ShelfCovers) {
                SubjectPosterSkeletonBox(Modifier.width(ChogumiDimens.CoverWidth))
            }
        }
    }
}

/**
 * 整理列表的一行：分段列表的行（[SegmentedItem]），打头叠三张封面，标题与说明各一条，尾部是拖动把手。
 * [dragged] 时与拖动排序拖着的那一行一样：单独成一段、[reorderRowColor] 的底色、[DraggedElevation] 的阴影。
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
    val segmentIndex = if (dragged) 0 else index
    val segmentCount = if (dragged) 1 else count
    val shape = ListItemDefaults.segmentedShapes(segmentIndex, segmentCount).shape
    SegmentedItem(
        index = segmentIndex,
        count = segmentCount,
        onClick = null,
        containerColor = reorderRowColor(dragged),
        leadingContent = {
            Box(Modifier.size(CoverStackSize), contentAlignment = Alignment.CenterStart) {
                CoverStack { _, cover -> SubjectPosterSkeletonBox(cover) }
            }
        },
        trailingContent = {
            Box(
                Modifier
                    .size(48.dp)
                    .then(if (onHandlePlaced != null) Modifier.onPlaced(onHandlePlaced) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.drag_indicator_24), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        supportingContent = { SkeletonText(LocalTextStyle.current, widthFraction = 0.35f) },
        modifier = modifier.graphicsLayer {
            shadowElevation = if (dragged) DraggedElevation.toPx() else 0f
            this.shape = shape
        },
    ) {
        SkeletonText(LocalTextStyle.current, widthFraction = 0.5f)
    }
}
