package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.defaultStatsLayout
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCard
import io.github.touhougo.chogumi.core.model.statistics.availableStatsData
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.arrange.arrangeOnLongPress
import io.github.touhougo.chogumi.core.ui.chart.BigNumber
import io.github.touhougo.chogumi.core.ui.chart.ChartEntrance
import io.github.touhougo.chogumi.core.ui.chart.HeatCell
import io.github.touhougo.chogumi.core.ui.chart.HeatCells
import io.github.touhougo.chogumi.core.ui.chart.LocalChartEntrance
import io.github.touhougo.chogumi.core.ui.chart.heatLevelOf
import io.github.touhougo.chogumi.core.ui.chart.label
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.CAROUSEL_SKELETON_COUNT
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SectionHeaderSkeleton
import io.github.touhougo.chogumi.core.ui.composables.SkeletonStandIn
import io.github.touhougo.chogumi.core.ui.composables.StatsCard
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVerticalSkeleton
import io.github.touhougo.chogumi.core.ui.composables.formatScore
import io.github.touhougo.chogumi.core.ui.theme.AppMotion
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * 统计 tab：评分、去向、一生、每集讨论四张卡，加系列与「喜欢这部的人也喜欢」两排作品。各卡的数由 core:data 的
 * subjectStatsOf 算，等哪几项数据由 statsTabOf 定；这里只画。图上不写说明文字，确切的数点按看。
 */

/**
 * 统计 tab 上画哪些模块，页面与整理列表同读这一个：没藏起来，并且这部作品有这份数据，或者数据还在等、初次没取到
 * （原位画骨架或重试）。确认没有这份数据的模块不画（只留「每集讨论」再打开一部游戏就是一个也没有）。
 */
internal fun visibleStatsModules(layout: List<StatsModule>, tab: SubjectStatsTab, available: Set<StatsData>): List<StatsModule> =
    layout.filter { !it.hidden && (it.data in available || tab.waitingOrFailed(it.data)) }

/**
 * 统计 tab 的全部列表项（页面只调这一个）：布局读到之前一块骨架；按 [visibleStatsModules] 逐模块画，一个也没有时给一行说明和进整理的入口
 * （进整理平时靠长按模块标题，没有模块就没有标题可按）；最后是网页透视的入口。
 */
internal fun LazyListScope.statsTabItems(
    tab: SubjectStatsTab,
    layout: List<StatsModule>?,
    listState: LazyListState,
    entrances: StatsEntrances,
    onArrange: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onRetryRatingSplit: () -> Unit,
    onRetryRecs: () -> Unit,
    onRetryRecentCollectTimes: () -> Unit,
    onRetryEpisodes: () -> Unit,
) {
    if (layout == null) {
        item(key = "stats-layout-pending") { StatsLayoutSkeleton(tab) }
        return
    }
    val available = tab.availableData()
    val visible = visibleStatsModules(layout, tab, available)
    if (visible.isEmpty()) item(key = "stats-empty") { StatsLayoutEmpty(onArrange) }
    items(visible, key = { it.id }) { module ->
        val motion = LocalAppMotion.current
        val ready = module.data in available
        val entrance = entrances.of(module, tab, ready, finished = motion.stagger(1) == 0L)
        LaunchedEffect(module.id, entrance) {
            if (entrance == null) return@LaunchedEffect
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == module.id } }.filter { it }.first()
            entrances.play(module.id, entrance, motion, module.drawing.entranceStagger())
        }
        CompositionLocalProvider(LocalChartEntrance provides entrance) {
            Column(Modifier.padding(vertical = 6.dp)) {
                StatsModuleBlock(module, tab, onArrange, onSubjectClick, onRetryRatingSplit, onRetryRecs, onRetryRecentCollectTimes, onRetryEpisodes)
            }
        }
    }
    item(key = "stats-web") { StatsWebButton(tab.subject.id) }
}

/**
 * 各模块的进场进度，存在懒列表之上：行滚走再滚回来、换画法都不重播。进度在这个模块的数据到了之后才建，元素数按那时的数据定
 * （章节晚到时「每集讨论」「一生」的格数要等章节）；数据没到时没有进度（画的是骨架或重试）。播放跑在 [scope] 里，行离开组合也接着走完。
 */
internal class StatsEntrances(private val scope: CoroutineScope) {
    private val entrances = mutableMapOf<String, ChartEntrance>()
    private val played = mutableSetOf<String>()

    fun of(module: StatsModule, tab: SubjectStatsTab, ready: Boolean, finished: Boolean): ChartEntrance? =
        if (ready) entrances.getOrPut(module.id) { ChartEntrance(module.entranceElements(tab), finished) } else entrances[module.id]

    /** 第一次出现在屏幕上时播一次。 */
    fun play(id: String, entrance: ChartEntrance, motion: AppMotion, staggerMillis: Long) {
        if (played.add(id)) scope.launch { entrance.play(motion, staggerMillis) }
    }
}

@Composable
internal fun rememberStatsEntrances(subjectId: Int): StatsEntrances {
    val scope = rememberCoroutineScope()
    return remember(subjectId) { StatsEntrances(scope) }
}

/** 布局还没读到：一张统计卡的骨架（形状取默认布局的第一块，只当样子，不是内容）。 */
@Composable
private fun StatsLayoutSkeleton(tab: SubjectStatsTab) {
    val module = defaultStatsLayout().first()
    Column(Modifier.padding(vertical = 6.dp)) {
        StatsCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonStandIn { Text(statsModuleName(module, tab.subject.type), style = MaterialTheme.typography.titleMedium) }
            SkeletonStandIn { StatsDrawingSkeleton(module, tab) }
        }
    }
}

/** 一个模块也画不出来：说一句，给进整理的入口（从那里把模块加回来）。 */
@Composable
private fun StatsLayoutEmpty(onArrange: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyLine(stringResource(R.string.stats_layout_empty))
        FilledTonalButton(onClick = onArrange, modifier = Modifier.testTag(StatsArrangeEntryTag)) { Text(stringResource(R.string.stats_arrange_enter)) }
    }
}

/** 初始加载与失败保留该模块的框架；确认缺数据才不画。 */
internal fun SubjectStatsTab.waitingOrFailed(data: StatsData): Boolean = when (data) {
    StatsData.STATUS_RATING, StatsData.AIR_PHASE, StatsData.TOTAL_COLLECTS, StatsData.REGDATE -> ratingSplit !is Load.Ready
    StatsData.EPISODE_DISCUSSION -> episodeHeat !is Load.Ready
    StatsData.LIFELINE -> lifeline !is Load.Ready
    StatsData.SERIES -> series is Load.Pending
    StatsData.RECOMMENDATIONS -> recs !is Load.Ready
    else -> false
}

internal fun SubjectStatsTab.availableData(): Set<StatsData> = availableStatsData(
    calculated,
    hasSeries = series.valueOrNull()?.isEmpty == false, hasRecommendations = recs.orEmpty().isNotEmpty())

@Composable
internal fun StatsModuleBlock(module: StatsModule, tab: SubjectStatsTab, onArrange: () -> Unit,
    onSubjectClick: (Int) -> Unit, onRetryRatingSplit: () -> Unit, onRetryRecs: () -> Unit, onRetryRecentCollectTimes: () -> Unit, onRetryEpisodes: () -> Unit) {
    val title = statsModuleName(module, tab.subject.type)
    val titleModifier = arrangeOnLongPress(onArrange, stringResource(R.string.stats_arrange_enter))
    if (module.data == StatsData.SERIES) {
        when (seriesSectionState(tab.series)) {
            SectionState.LOADING -> Column { SectionHeaderSkeleton(); SeriesRowSkeleton(scores = true) }
            SectionState.CONTENT -> Column { SectionHeader(title, modifier = titleModifier); StatsModuleDrawing(module, tab, onSubjectClick) }
            else -> Unit
        }
        return
    }
    if (module.data == StatsData.RECOMMENDATIONS) {
        when (sectionStateOf(tab.recs)) {
            SectionState.LOADING -> RecsSkeleton()
            SectionState.ERROR -> Column {
                SectionHeader(title, modifier = titleModifier.testTag(StatsRecsTitleTag))
                RetryError(requireNotNull(tab.recs.failureOrNull()), onRetryRecs, Modifier.fillMaxWidth().testTag(StatsRecsErrorTag), compact = true)
            }
            SectionState.CONTENT -> Column { SectionHeader(title, modifier = titleModifier); StatsModuleDrawing(module, tab, onSubjectClick) }
            SectionState.EMPTY -> Unit
        }
        return
    }
    StatsCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = titleModifier)
        if (module.data == StatsData.RATING) {
            Row(verticalAlignment = Alignment.Top) {
                BigNumber(formatScore(tab.subject.ratingScore), (tab.subject.ratingScore * 10).roundToInt(),
                    pluralStringResource(R.plurals.stats_rated_by, tab.subject.ratingTotal, tab.subject.ratingTotal), Modifier.weight(1f))
                if (tab.subject.ratingRank > 0) RankChip(tab.subject.ratingRank)
            }
        }
        if (module.data == StatsData.CONTROVERSY) tab.calculated.controversy?.let { value ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(value.tier.label(), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(stringResource(R.string.stats_sigma, number2(value.sigma)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val splitData = module.data in listOf(StatsData.STATUS_RATING, StatsData.AIR_PHASE, StatsData.TOTAL_COLLECTS, StatsData.REGDATE)
        when {
            splitData && tab.ratingSplit is Load.Failed -> RetryError(tab.ratingSplit.failure, onRetryRatingSplit,
                Modifier.fillMaxWidth().testTag(StatsRatingSplitErrorTag), compact = true)
            splitData && tab.ratingSplit is Load.Pending -> SkeletonStandIn { StatsDrawingSkeleton(module, tab) }
            module.data == StatsData.EPISODE_DISCUSSION && tab.episodeHeat is Load.Failed -> RetryError(tab.episodeHeat.failure, onRetryEpisodes, Modifier.fillMaxWidth(), compact = true)
            module.data == StatsData.LIFELINE && tab.lifeline is Load.Failed -> RetryError(tab.lifeline.failure, onRetryEpisodes, Modifier.fillMaxWidth(), compact = true)
            module.data == StatsData.EPISODE_DISCUSSION && tab.episodeHeat is Load.Pending -> SkeletonStandIn { StatsDrawingSkeleton(module, tab) }
            module.data == StatsData.LIFELINE && tab.lifeline is Load.Pending -> LifeTimelineSkeleton()
            else -> StatsModuleDrawing(module, tab, onSubjectClick)
        }
        if (module.data == StatsData.LIFELINE && tab.recentCollectTimes is Load.Failed) RetryError(
            tab.recentCollectTimes.failure, onRetryRecentCollectTimes, Modifier.fillMaxWidth().testTag(StatsRecentCollectTimesErrorTag), compact = true)
    }
}

/** 排名：奖杯 + 名次。外形取 M3 assist chip（最小高 32dp、CornerSmall、图标 18dp、labelLarge），不可点；底色 secondaryContainer 是我的设计决定（not in M3）。 */
@Composable
internal fun RankChip(rank: Int) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(R.drawable.trophy_24), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.stats_rank_number, rank), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---- 每集讨论：一集一格，深浅是讨论数，格里写集数，看过的集下面一道横 ----

/** 每集讨论一行最多几格；多于这些时按行数均分（23 集排成 12 + 11，不排成 13 + 10）。 */
private const val EPISODE_HEAT_COLUMNS = 13

/** 每集讨论格子最大边长（放得下三位集数）。我的设计决定（not in M3）。 */
private val EPISODE_HEAT_CELL = 32.dp

private fun <T> balancedRows(items: List<T>): List<List<T>> {
    val rows = ceil(items.size.toDouble() / EPISODE_HEAT_COLUMNS).toInt().coerceAtLeast(1)
    return items.chunked(ceil(items.size.toDouble() / rows).toInt().coerceAtLeast(1))
}

@Composable
internal fun EpisodeHeat(card: EpisodeHeatCard, tips: List<String>, description: String) {
    val peak = card.cells.maxOf { it.comments }
    val cells = card.cells.mapIndexed { index, cell ->
        HeatCell(
            level = heatLevelOf(cell.comments, peak),
            tip = tips[index],
            label = formatEpisodeSort(cell.sort),
            marked = cell.watched,
        )
    }
    val rows = balancedRows(cells)
    HeatCells(
        rows = rows,
        rowLabels = rows.map { "" },
        columns = rows.first().size,
        description = description,
        maxCell = EPISODE_HEAT_CELL,
    )
}

/** 每集讨论没到时的占位：按条目的话数排出同样的格子，不可见，盖一层色块。 */
@Composable
internal fun EpisodeHeatSkeleton(subject: SubjectBo) {
    val count = (subject.eps ?: 0).coerceIn(1, EpisodeTracking.EPISODE_RANGE_SIZE)
    val rows = balancedRows(List(count) { HeatCell(level = 0, tip = "", label = "") })
    SkeletonStandIn {
        HeatCells(
            rows = rows,
            rowLabels = rows.map { "" },
            columns = rows.first().size,
            description = "",
            maxCell = EPISODE_HEAT_CELL,
        )
    }
}

// ---- 系列与推荐：两排作品 ----

/** 推荐还没到：标题与一排封面卡的骨架。 */
@Composable
private fun RecsSkeleton() {
    Column {
        SectionHeaderSkeleton()
        CarouselRow(userScrollEnabled = false) {
            items(CAROUSEL_SKELETON_COUNT) { SubjectCardVerticalSkeleton(Modifier.width(ChogumiDimens.CoverWidth)) }
        }
    }
}

internal const val StatsRatingSplitErrorTag = "StatsRatingSplitError"
internal const val StatsRecentCollectTimesErrorTag = "StatsRecentCollectTimesError"
internal const val StatsRecsErrorTag = "StatsRecsError"
internal const val StatsRecsTitleTag = "StatsRecsTitle"
internal const val StatsArrangeEntryTag = "StatsArrangeEntry"

@Composable
internal fun StatsWebButton(subjectId: Int) {
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { uri.openUri(BgmWeb.subjectStats(subjectId)) }) { Text(stringResource(R.string.stats_open_web)) }
    }
}
