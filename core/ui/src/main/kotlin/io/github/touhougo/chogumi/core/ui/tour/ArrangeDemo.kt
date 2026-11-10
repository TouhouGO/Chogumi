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
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.chart.PlotPoint
import io.github.touhougo.chogumi.core.ui.chart.StatsSegments
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.arrange.StatsModuleLead
import io.github.touhougo.chogumi.core.ui.chart.CountBars
import io.github.touhougo.chogumi.core.ui.chart.TenBars
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
import io.github.touhougo.chogumi.core.ui.composables.StatsCard
import io.github.touhougo.chogumi.core.ui.composables.StatsCardGap
import io.github.touhougo.chogumi.core.ui.composables.StatsModulePadding
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.reorderRowColor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import io.github.touhougo.chogumi.core.ui.theme.raisedContainer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 一条货架露出几张封面（演示台右边裁掉一截，像真的横滑货架）。 */
private const val ShelfCovers = 5

/** 演示里的三块（三条货架、三个统计模块）。 */
private const val BlockCount = 3

private const val RestMillis = 700L
private const val LookMillis = 700L
private const val DroppedMillis = 1000L

/** 统计那一页的三个模块：评分、每集讨论、收藏状态（整理行打头的图标按它画）。 */
private val StatsBlocks = listOf(StatsData.RATING, StatsData.EPISODE_DISCUSSION, StatsData.COLLECTION)

/** 统计卡里示意的计数：评分十档、每集讨论十二集（形状像一部常见的作品，数是示意）。 */
private val RatingCounts = listOf(2, 1, 3, 6, 14, 30, 62, 88, 41, 12)
private val EpisodeCounts = listOf(96, 70, 64, 58, 61, 55, 52, 60, 57, 66, 72, 90)

/** 每集讨论的柱高，与统计页这种画法同高。 */
private val EpisodeBarsHeight = 110.dp

/** 收藏状态的分段条：看过、在看、搁置、抛弃各占多少（百分比，示意）。 */
private val CollectionShares = listOf(CollectionType.DONE to 62, CollectionType.DOING to 20, CollectionType.ON_HOLD to 10, CollectionType.DROPPED to 8)

/** 脚本要按的几个位置（演示台坐标，px）：放置时记下，只给脚本读，不是界面状态。 */
private class ArrangeTargets {
    var header = Offset.Zero
    var handle = Offset.Zero
    var rowStep = 0f
}

/**
 * 整理探索页那一页：按住第一条货架的标题，页面换成整理列表，拖第一行过第二行，回到货架。画的是探索页同样的东西：
 * 搜索入口 [ContainedSearchEntry]、货架标题的骨架 [SectionHeaderSkeleton]、整理栏 [ArrangeBar]、整理行打头叠封面 [CoverStack]。
 */
@Composable
internal fun ArrangeDemo(playing: Boolean, modifier: Modifier = Modifier) {
    ArrangeTour(
        playing = playing,
        modifier = modifier,
        bar = { ArrangeBar(onAdd = {}, onDone = {}) },
        lead = {
            Box(Modifier.size(CoverStackSize), contentAlignment = Alignment.CenterStart) {
                CoverStack { _, cover -> SubjectPosterSkeletonBox(cover) }
            }
        },
    ) { order, header ->
        // 探索页顶上的搜索入口（与探索页同样的摆法）
        ContainedSearchEntry(
            placeholder = stringResource(R.string.search_hint),
            onClick = {},
            modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding).padding(top = 8.dp),
        )
        order.forEachIndexed { index, _ -> DemoShelf(header(index)) }
    }
}

/**
 * 整理统计那一页：作品统计 tab 上一张张统计卡（[StatsCard]，标题是骨架、图是真的 [TenBars] 与 [CountBars]），
 * 按住第一张的标题进整理，拖第一行过第二行，回到统计 tab，两张换了位置。整理栏是统计的标题与「添加模块」，行首是 [StatsModuleLead]。
 */
@Composable
internal fun StatsArrangeDemo(playing: Boolean, modifier: Modifier = Modifier) {
    ArrangeTour(
        playing = playing,
        modifier = modifier,
        bar = {
            ArrangeBar(
                onAdd = {},
                onDone = {},
                title = stringResource(R.string.stats_arrange_title),
                addLabel = stringResource(R.string.stats_arrange_add),
            )
        },
        lead = { index -> StatsModuleLead(StatsBlocks[index]) },
    ) { order, header ->
        Column(Modifier.padding(vertical = StatsModulePadding)) {
            order.forEachIndexed { index, block ->
                DemoStatsCard(StatsBlocks[block], header(index), Modifier.padding(vertical = StatsModulePadding))
            }
        }
    }
}

/**
 * 两页整理演示共用的脚本：按住第一块的标题，页面换成整理列表（顶栏换成 [bar]），按住第一行的把手往下拖过第二行、松手，
 * 再回到页面，两块换了位置（下一轮再换回来）。整理列表的行是分段列表的行 [SegmentedItem]，打头画 [lead]（参数是这一行是哪一块）；
 * 拖着的那一行与拖动排序同一套（[reorderRowColor] 的底色、[DraggedElevation] 的阴影、单独成一段的整块圆角）。
 * [page] 按 `order` 画页面本身，第 `i` 块的标题挂上 `header(i)`（脚本从第一块的标题读按的位置）。
 */
@Composable
private fun ArrangeTour(
    playing: Boolean,
    modifier: Modifier,
    bar: @Composable () -> Unit,
    lead: @Composable (block: Int) -> Unit,
    page: @Composable (order: List<Int>, header: (index: Int) -> Modifier) -> Unit,
) {
    TourStage(modifier) { stage ->
        val motion = MaterialTheme.motionScheme
        val longPressMillis = LocalViewConfiguration.current.longPressTimeoutMillis
        val finger = remember { TourFinger() }
        val targets = remember { ArrangeTargets() }
        val gap = with(LocalDensity.current) { ListItemDefaults.SegmentedGap.toPx() }
        var order by remember { mutableStateOf(List(BlockCount) { it }) }
        var arranging by remember { mutableStateOf(false) }
        var dragging by remember { mutableStateOf<Int?>(null) }
        // 拖动从哪按下：拖着的行跟着手指走，偏移是手指离它的竖直距离
        var dragStart by remember { mutableStateOf(Offset.Zero) }
        val yield = remember { Animatable(0f) }

        LaunchedEffect(playing) {
            finger.reset()
            order = List(BlockCount) { it }
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

        // 与真页面进出整理同一个转场：先淡出再淡入，两层不同时半透明
        val pageMotion = LocalAppMotion.current.page
        AnimatedContent(
            targetState = arranging,
            transitionSpec = { chogumiTransition(PageTransitionKind.TopLevel, pageMotion, 1).using(null) },
            label = "tour_arrange",
        ) { listShown ->
            // 页面是竖着滚的：每一块都按自己的高排整块，放不下的由演示台裁掉，不被挤进剩下的高度
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = false)) {
                if (listShown) {
                    bar()
                    Column(
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                        modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding).padding(top = 8.dp),
                    ) {
                        order.forEachIndexed { index, block ->
                            val isDragged = block == dragging
                            val isYielding = dragging != null && index == 1
                            DemoArrangeRow(
                                index = index,
                                count = order.size,
                                dragged = isDragged,
                                lead = { lead(block) },
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
                    page(order) { index -> if (index == 0) Modifier.onPlaced { targets.header = stage.centerOf(it) } else Modifier }
                }
            }
        }
        FingerDot(finger)
    }
}

/**
 * 统计 tab 上的一张统计卡：与统计页同一个外壳 [StatsCard]（卡与卡的间距 [StatsModulePadding]、卡里 [StatsCardGap]），
 * 标题画成骨架（[header] 挂在它上面），图是真的图表组件、数是示意。卡在页面上是 `surfaceContainer`，演示台本身就是这个颜色，
 * 所以取 `raisedContainer`。第三张（收藏状态的分段条 [StatsSegments]）在演示台下沿只露出上半截。
 */
@Composable
private fun DemoStatsCard(data: StatsData, header: Modifier, modifier: Modifier) {
    StatsCard(modifier, verticalArrangement = Arrangement.spacedBy(StatsCardGap), color = MaterialTheme.colorScheme.raisedContainer) {
        SkeletonText(MaterialTheme.typography.titleMedium, widthFraction = 0.3f, modifier = header)
        val noTips = { counts: List<Int> -> counts.map { "" } }
        when (data) {
            StatsData.RATING -> TenBars(RatingCounts, noTips(RatingCounts), description = "")
            StatsData.EPISODE_DISCUSSION ->
                CountBars(EpisodeCounts, EpisodeCounts.indices.map { "${it + 1}" }, noTips(EpisodeCounts), description = "", height = EpisodeBarsHeight)
            else -> StatsSegments(
                CollectionShares.map { (type, share) ->
                    PlotPoint("${stringResource(type.labelResFor(SubjectType.ANIME))} $share%", share.toDouble(), tip = "", color = type.colors().color)
                },
                ring = false,
            )
        }
    }
}

/**
 * 探索页的一条货架：标题一行（货架标题的骨架），下面一排封面。封面排在一条横滑的行里（不接手势），
 * 每张都是整张，超出的由演示台裁掉，和真货架一样；不横滑时最后一张会被挤成剩下的宽度。
 */
@Composable
private fun DemoShelf(header: Modifier) {
    Column {
        SectionHeaderSkeleton(header)
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
 * 整理列表的一行：分段列表的行（[SegmentedItem]），打头是 [lead]，标题与说明各一条，尾部是拖动把手。
 * [dragged] 时与拖动排序拖着的那一行一样：单独成一段、[reorderRowColor] 的底色、[DraggedElevation] 的阴影。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DemoArrangeRow(
    index: Int,
    count: Int,
    dragged: Boolean,
    lead: @Composable () -> Unit,
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
        leadingContent = lead,
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
