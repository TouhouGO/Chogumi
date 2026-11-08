package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCard
import io.github.touhougo.chogumi.core.model.statistics.LifelineCard
import io.github.touhougo.chogumi.core.model.statistics.ScoreCard
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.BigNumber
import io.github.touhougo.chogumi.core.ui.chart.HeatCell
import io.github.touhougo.chogumi.core.ui.chart.HeatCells
import io.github.touhougo.chogumi.core.ui.chart.ScoreMark
import io.github.touhougo.chogumi.core.ui.chart.ScoreRuler
import io.github.touhougo.chogumi.core.ui.chart.TenBars
import io.github.touhougo.chogumi.core.ui.chart.heatLevelOf
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.CAROUSEL_SKELETON_COUNT
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SectionHeaderSkeleton
import io.github.touhougo.chogumi.core.ui.composables.SkeletonStandIn
import io.github.touhougo.chogumi.core.ui.composables.StatsCard
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVertical
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVerticalSkeleton
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.composables.formatScore
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * 统计 tab：评分、去向、一生、每集讨论四张卡，加系列与「喜欢这部的人也喜欢」两排作品。各卡的数由 core:data 的
 * subjectStatsOf 算，等哪几项数据由 statsTabOf 定；这里只画。图上不写说明文字，确切的数点按看。
 */

/**
 * 统计 tab。[tab] 为 null 是这一拍还没算出来（条目本体刚到）。这个 tab 出不出现由 [visibleSectionsFor] 判定，这里不判断。
 */
@Composable
internal fun StatsSection(
    tab: SubjectStatsTab?,
    onSubjectClick: (Int) -> Unit,
    onRetryRatingSplit: () -> Unit,
    onRetryRecs: () -> Unit,
    onRetryRecentCollectTimes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tab == null) return@Column
        val subject = tab.subject
        tab.score?.let { ScoreStatsCard(subject, it, tab.ratingSplit, onRetryRatingSplit) }
        tab.flow?.let { flow ->
            StatsBlockCard(flowTitle(subject.type)) {
                StatusFlow(flow, subject.type)
            }
        }
        when (sectionStateOf(tab.lifeline) { it == null }) {
            SectionState.LOADING -> StatsBlockCard(stringResource(R.string.stats_lifeline_title)) { LifeTimelineSkeleton() }
            SectionState.CONTENT -> StatsBlockCard(stringResource(R.string.stats_lifeline_title)) {
                LifelineContent(requireNotNull(tab.lifeline.valueOrNull()), tab.recentCollectTimes, onRetryRecentCollectTimes)
            }
            SectionState.ERROR, SectionState.EMPTY -> Unit
        }
        when (sectionStateOf(tab.episodeHeat) { it == null }) {
            SectionState.LOADING -> StatsBlockCard(stringResource(R.string.stats_episode_heat_title)) {
                EpisodeHeatSkeleton(subject)
            }
            SectionState.CONTENT -> StatsBlockCard(stringResource(R.string.stats_episode_heat_title)) {
                EpisodeHeat(requireNotNull(tab.episodeHeat.valueOrNull()))
            }
            SectionState.ERROR, SectionState.EMPTY -> Unit
        }
        SeriesBlock(subject, tab.series, onSubjectClick)
        RecsBlock(tab.recs, onRetryRecs, onSubjectClick)
        TextButton(
            onClick = { uriHandler.openUri(BgmWeb.subjectStats(subject.id)) },
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp),
        ) {
            Text(stringResource(R.string.stats_open_web))
        }
    }
}

/** 统计卡：标题（区块标题字阶）+ 图，标题与图之间 12dp。 */
@Composable
private fun StatsBlockCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    StatsCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

// ---- 评分：大分数、排名、1–10 分柱、各收藏状态的均分落在尺上 ----

@Composable
private fun ScoreStatsCard(
    subject: SubjectBo,
    card: ScoreCard,
    ratingSplit: Load<*>,
    onRetryRatingSplit: () -> Unit,
) {
    StatsCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            BigNumber(
                value = formatScore(subject.ratingScore),
                order = (subject.ratingScore * 10).roundToInt(),
                name = pluralStringResource(R.plurals.stats_rated_by, subject.ratingTotal, subject.ratingTotal),
                modifier = Modifier.weight(1f),
            )
            if (subject.ratingRank > 0) RankChip(subject.ratingRank)
        }
        TenBars(
            counts = card.counts,
            tips = card.counts.mapIndexed { i, count -> stringResource(R.string.stats_tip_score, i + 1, count) },
            description = stringResource(R.string.stats_score_description),
        )
        val marks = card.byCollection.map { group ->
            ScoreMark(
                label = stringResource(R.string.stats_group_mean, stringResource(group.group.labelResFor(subject.type)), formatScore(group.mean)),
                score = group.mean,
                own = false,
                color = group.group.colors().color,
            )
        } + listOfNotNull(card.mine?.let { ScoreMark(stringResource(R.string.stats_you_mark, it), it.toDouble(), own = true) })
        val description = stringResource(R.string.stats_ruler_description)
        when (ratingSplit) {
            // 骨架：同样带分组的尺，不可见，盖一层色块
            Load.Pending -> SkeletonStandIn {
                ScoreRuler(
                    marks = listOf(ScoreMark("", 8.0, own = false, color = CollectionType.DONE.colors().color)),
                    description = description,
                )
            }
            is Load.Failed -> RetryError(
                ratingSplit.failure,
                onRetryRatingSplit,
                Modifier.fillMaxWidth().testTag(StatsRatingSplitErrorTag),
                compact = true,
            )
            is Load.Ready -> if (card.byCollection.isNotEmpty()) ScoreRuler(marks, description)
        }
    }
}

@Composable
private fun LifelineContent(card: LifelineCard, recentCollectTimes: Load<*>, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LifeTimeline(card)
        when (recentCollectTimes) {
            Load.Pending -> SkeletonStandIn(Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.stats_pace_day, 8), style = MaterialTheme.typography.labelSmall)
            }
            is Load.Failed -> RetryError(
                recentCollectTimes.failure,
                onRetry,
                Modifier.fillMaxWidth().testTag(StatsRecentCollectTimesErrorTag),
                compact = true,
            )
            is Load.Ready -> Unit
        }
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
private fun EpisodeHeat(card: EpisodeHeatCard) {
    val peak = card.cells.maxOf { it.comments }
    val cells = card.cells.map { cell ->
        HeatCell(
            level = heatLevelOf(cell.comments, peak),
            tip = pluralStringResource(R.plurals.stats_episode_tip, cell.comments, formatEpisodeSort(cell.sort), cell.comments),
            label = formatEpisodeSort(cell.sort),
            marked = cell.watched,
        )
    }
    val rows = balancedRows(cells)
    HeatCells(
        rows = rows,
        rowLabels = rows.map { "" },
        columns = rows.first().size,
        description = stringResource(R.string.stats_episode_heat_title),
        maxCell = EPISODE_HEAT_CELL,
    )
}

/** 每集讨论没到时的占位：按条目的话数排出同样的格子，不可见，盖一层色块。 */
@Composable
private fun EpisodeHeatSkeleton(subject: SubjectBo) {
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

@Composable
private fun SeriesBlock(subject: SubjectBo, series: Load<SubjectSeries>, onSubjectClick: (Int) -> Unit) {
    when (seriesSectionState(series)) {
        SectionState.LOADING -> Column {
            SectionHeaderSkeleton()
            SeriesRowSkeleton(scores = true)
        }
        SectionState.CONTENT -> Column {
            SectionHeader(stringResource(R.string.stats_series_title))
            SeriesRow(subject, requireNotNull(series.valueOrNull()), scores = true, onSubjectClick = onSubjectClick)
        }
        SectionState.ERROR, SectionState.EMPTY -> Unit
    }
}

@Composable
private fun RecsBlock(recs: Load<List<SubjectBo>>, onRetry: () -> Unit, onSubjectClick: (Int) -> Unit) {
    when (sectionStateOf(recs)) {
        SectionState.LOADING -> Column {
            SectionHeaderSkeleton()
            CarouselRow(userScrollEnabled = false) {
                items(CAROUSEL_SKELETON_COUNT) { SubjectCardVerticalSkeleton(Modifier.width(ChogumiDimens.CoverWidth)) }
            }
        }
        SectionState.ERROR -> Column {
            SectionHeader(
                stringResource(R.string.stats_recs_title),
                modifier = Modifier.testTag(StatsRecsTitleTag),
            )
            RetryError(
                recs.failureOrNull() ?: return@Column,
                onRetry,
                Modifier.fillMaxWidth().testTag(StatsRecsErrorTag),
                compact = true,
            )
        }
        SectionState.CONTENT -> Column {
            SectionHeader(stringResource(R.string.stats_recs_title))
            CarouselRow {
                items(recs.orEmpty(), key = { it.id }) { rec ->
                    SubjectCardVertical(rec, onSubjectClick, Modifier.width(ChogumiDimens.CoverWidth))
                }
            }
        }
        SectionState.EMPTY -> Unit
    }
}

internal const val StatsRatingSplitErrorTag = "StatsRatingSplitError"
internal const val StatsRecentCollectTimesErrorTag = "StatsRecentCollectTimesError"
internal const val StatsRecsErrorTag = "StatsRecsError"
internal const val StatsRecsTitleTag = "StatsRecsTitle"
