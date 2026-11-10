package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.AirPhase
import io.github.touhougo.chogumi.core.model.StatsDrawing
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.model.statistics.ControversyTier
import io.github.touhougo.chogumi.core.model.statistics.GroupMean
import io.github.touhougo.chogumi.core.model.statistics.ScoreCard
import io.github.touhougo.chogumi.core.model.statistics.SubjectStats
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.ControversyCamps
import io.github.touhougo.chogumi.core.ui.chart.StatsTagCloud
import io.github.touhougo.chogumi.core.ui.chart.PlotPoint
import io.github.touhougo.chogumi.core.ui.chart.StatsRows
import io.github.touhougo.chogumi.core.ui.chart.StatsLine
import io.github.touhougo.chogumi.core.ui.chart.StatsStrip
import io.github.touhougo.chogumi.core.ui.chart.RatingDots
import io.github.touhougo.chogumi.core.ui.chart.ControversyGauge
import io.github.touhougo.chogumi.core.ui.chart.ControversyRidge
import io.github.touhougo.chogumi.core.ui.chart.StatsSegments
import io.github.touhougo.chogumi.core.ui.chart.StatsDistributions
import io.github.touhougo.chogumi.core.ui.chart.TenBars
import io.github.touhougo.chogumi.core.ui.chart.ScoreMark
import io.github.touhougo.chogumi.core.ui.chart.ScoreRuler
import io.github.touhougo.chogumi.core.ui.chart.CountBars
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVertical
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import java.util.Locale

internal fun number2(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
private fun percent(value: Double): String = String.format(Locale.ROOT, "%.0f%%", value * 100)

/** 数据和画法的唯一分派；页面、骨架与编辑缩略图调用同一份。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsModuleDrawing(module: StatsModule, tab: SubjectStatsTab, onSubjectClick: (Int) -> Unit) {
    val stats = tab.calculated ?: SubjectStats(tab.score, tab.episodeHeat.valueOrNull(), tab.lifeline.valueOrNull(), tab.flow)
    val score = tab.score
    val subject = tab.subject
    val ratingTips = score?.counts?.mapIndexed { index, count -> stringResource(R.string.stats_tip_score, index + 1, count) }.orEmpty()
    val groups = when (module.drawing) {
        StatsDrawing.PHASE_SLOPE, StatsDrawing.PHASE_DISTRIBUTIONS -> stats.airPhases.map { group ->
            val label = stringResource(when (group.group) { AirPhase.BEFORE -> R.string.stats_phase_before; AirPhase.AIRING -> R.string.stats_phase_airing; AirPhase.AFTER -> R.string.stats_phase_after })
            group.point(label)
        }
        StatsDrawing.COLLECT_LINE, StatsDrawing.COLLECT_STRIP -> stats.totalCollects.map { it.point(collectLabel(it.group), stringResource(R.string.stats_collect_group_tip, collectRange(it.group))) }
        StatsDrawing.REGDATE_LINE, StatsDrawing.REGDATE_DELTA -> stats.regdate.map { it.point(regdateLabel(it.group), stringResource(R.string.stats_regdate_group_tip, regdateLabel(it.group) + if (it.group == 1825) "" else "以内")) }
        else -> emptyList()
    }
    when (module.drawing) {
        StatsDrawing.RATING_BARS -> score?.let { TenBars(it.counts, ratingTips, stringResource(R.string.stats_score_description)) }
        StatsDrawing.RATING_ROWS -> score?.let { StatsRows(it.counts.indices.reversed().map { i -> PlotPoint("${i + 1}", it.counts[i].toDouble(), ratingTips[i], displayValue = percent(it.counts[i].toDouble() / it.counts.sumOf { n -> n.toLong() })) }) }
        StatsDrawing.RATING_DOTS -> score?.let { RatingDots(it.counts, ratingTips) }
        StatsDrawing.STATUS_RULER -> score?.let {
            val marks = it.byCollection.map { group -> ScoreMark(stringResource(R.string.stats_group_mean, stringResource(group.group.labelResFor(subject.type)), number2(group.mean)), group.mean, false, group.group.colors().color, group.point(stringResource(group.group.labelResFor(subject.type))).tip) } +
                listOfNotNull(it.mine?.let { mine -> ScoreMark(stringResource(R.string.stats_you_mark, mine), mine.toDouble(), true) })
            ScoreRuler(marks, stringResource(R.string.stats_ruler_description))
        }
        StatsDrawing.STATUS_DELTA -> score?.let { StatsRows(it.byCollection.map { group -> group.point(stringResource(group.group.labelResFor(subject.type)), color = group.group.colors().color) }, subject.ratingScore) }
        StatsDrawing.COLLECTION_FLOW -> tab.flow?.let { StatusFlow(it, subject.type) }
        StatsDrawing.COLLECTION_BAR, StatsDrawing.COLLECTION_RING -> tab.flow?.let { flow ->
            StatsSegments(flow.branches.map { branch ->
                val label = stringResource(branch.type.labelResFor(subject.type))
                PlotPoint("$label ${percent(branch.share)}", branch.count.toDouble(), "$label：${branch.count} 人（${percent(branch.share)}）", branch.type.colors().color)
            }, ring = module.drawing == StatsDrawing.COLLECTION_RING)
        }
        StatsDrawing.LIFELINE -> tab.lifeline.valueOrNull()?.let { LifeTimeline(it) }
        StatsDrawing.EPISODE_GRID -> tab.episodeHeat.valueOrNull()?.let { EpisodeHeat(it) }
        StatsDrawing.EPISODE_BARS, StatsDrawing.EPISODE_LINE -> tab.episodeHeat.valueOrNull()?.let { heat ->
            val points = heat.cells.map { cell -> PlotPoint(formatEpisodeSort(cell.sort), cell.comments.toDouble(), if (subject.type == io.github.touhougo.chogumi.core.model.SubjectType.MUSIC) stringResource(R.string.stats_track_tip, formatEpisodeSort(cell.sort), cell.comments)
                else androidx.compose.ui.res.pluralStringResource(R.plurals.stats_episode_tip, cell.comments, formatEpisodeSort(cell.sort), cell.comments)) }
            if (module.drawing == StatsDrawing.EPISODE_LINE) StatsLine(points) else CountBars(points.map { it.value.toInt() }, points.map { it.label }, points.map { it.tip }, statsModuleName(module, subject.type), 110.dp)
        }
        StatsDrawing.SERIES -> tab.series.valueOrNull()?.let { SeriesRow(subject, it, scores = true, onSubjectClick = onSubjectClick) }
        StatsDrawing.RECOMMENDATIONS -> CarouselRow { items(tab.recs.orEmpty(), key = { it.id }) { SubjectCardVertical(it, onSubjectClick, Modifier.width(ChogumiDimens.CoverWidth)) } }
        StatsDrawing.CONTROVERSY_SPECTRUM, StatsDrawing.CONTROVERSY_DIAL -> stats.controversy?.let { value ->
            val range = stringResource(when (value.tier) {
                ControversyTier.UNANIMOUS -> R.string.stats_sigma_below
                ControversyTier.CONCENTRATED -> R.string.stats_sigma_concentrated
                ControversyTier.SOME_DISAGREEMENT -> R.string.stats_sigma_some
                ControversyTier.MIXED -> R.string.stats_sigma_mixed
                ControversyTier.POLARIZED -> R.string.stats_sigma_polarized
            })
            ControversyGauge(value, stringResource(R.string.stats_controversy_tip, number2(value.sigma), value.tier.label(), range), dial = module.drawing == StatsDrawing.CONTROVERSY_DIAL)
        }
        StatsDrawing.CONTROVERSY_CAMPS -> stats.controversy?.let { value ->
            val ranges = listOf("1–4", "5–7", "8–10")
            val tips = value.camps.mapIndexed { i, n -> stringResource(R.string.stats_camp_tip, ranges[i], n, percent(n.toDouble() / value.count)) }
            ControversyCamps(value, tips)
        }
        StatsDrawing.CONTROVERSY_RIDGE -> stats.controversy?.let { value -> ControversyRidge(subject.ratingCounts, value, stringResource(R.string.stats_ridge_tip, number2(value.mean), percent(value.insideShare), value.insideLow, value.insideHigh)) }
        StatsDrawing.PHASE_SLOPE, StatsDrawing.COLLECT_LINE, StatsDrawing.REGDATE_LINE -> StatsLine(groups, subject.ratingScore)
        StatsDrawing.PHASE_DISTRIBUTIONS -> StatsDistributions(stats.airPhases.map { it.counts }, groups.map { it.label }, groups.map { it.tip })
        StatsDrawing.COLLECT_STRIP -> StatsStrip(groups, subject.ratingScore)
        StatsDrawing.REGDATE_DELTA -> StatsRows(groups, subject.ratingScore)
        StatsDrawing.TAG_CLOUD -> StatsTagCloud(stats.tags.map { PlotPoint(it.name, it.count.toDouble(), stringResource(R.string.stats_tag_tip, it.name, it.count)) })
        StatsDrawing.TAG_RANK -> StatsRows(stats.tags.take(8).map { PlotPoint(it.name, it.count.toDouble(), stringResource(R.string.stats_tag_tip, it.name, it.count)) })
    }
}

@Composable
private fun <K> GroupMean<K>.point(label: String, tipLabel: String = label, color: Color = Color.Unspecified): PlotPoint =
    PlotPoint(label, mean, androidx.compose.ui.res.pluralStringResource(R.plurals.stats_group_tip, count, tipLabel, number2(mean), count), color)

private fun collectLabel(key: Int): String = when (key) { 1 -> "1"; 10 -> "10-"; 50 -> "10+"; 100 -> "50+"; 200 -> "100+"; 500 -> "200+"; 1000 -> "500+"; else -> "1000+" }
private fun collectRange(key: Int): String = when (key) { 1 -> "1"; 10 -> "2–10"; 50 -> "11–50"; 100 -> "51–100"; 200 -> "101–200"; 500 -> "201–500"; 1000 -> "501–1000"; else -> "1000 以上" }
private fun regdateLabel(key: Int): String = when (key) { 10 -> "10天"; 30 -> "1月"; 180 -> "6月"; 365 -> "1年"; 730 -> "2年"; 1095 -> "3年"; else -> ">3年" }

/** 等待时按当前画法走同一分派，仅填入同结构占位数值。 */
@Composable
internal fun StatsDrawingSkeleton(module: StatsModule, tab: SubjectStatsTab) {
    val score = ScoreCard(List(10) { if (it == 7) 100 else 0 }, listOf(GroupMean(io.github.touhougo.chogumi.core.model.CollectionType.DONE, 8.0, 100, List(10) { 10 })), tab.score?.mine)
    val sampleHeat = io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCard(List((tab.subject.eps ?: 1).coerceIn(1, 100)) {
        io.github.touhougo.chogumi.core.model.statistics.EpisodeHeatCell(it + 1, (it + 1).toDouble(), 1, false)
    })
    val sample = SubjectStats(score, sampleHeat, null, null,
        airPhases = AirPhase.entries.map { GroupMean(it, 8.0, 100, List(10) { 10 }) },
        totalCollects = io.github.touhougo.chogumi.core.model.TOTAL_COLLECT_GROUPS.map { GroupMean(it, 8.0, 100, List(10) { 10 }) },
        regdate = io.github.touhougo.chogumi.core.model.REGDATE_GROUPS.map { GroupMean(it, 8.0, 100, List(10) { 10 }) })
    if (module.drawing == StatsDrawing.EPISODE_GRID) EpisodeHeatSkeleton(tab.subject)
    else StatsModuleDrawing(module, tab.copy(score = score, episodeHeat = Load.Ready(sampleHeat), calculated = sample, series = Load.Ready(SubjectSeries(emptyList(), emptyList()))), {})
}
