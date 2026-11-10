package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.model.StatsDrawing
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.statistics.ControversyTier
import io.github.touhougo.chogumi.core.res.R

/** 数据名字仅在这里选择，音乐的讨论按曲目说；可用性不按作品种类硬编码。 */
@Composable
internal fun statsDataName(data: StatsData, type: SubjectType): String = when (data) {
    StatsData.RATING -> stringResource(R.string.stats_data_rating)
    StatsData.STATUS_RATING -> stringResource(R.string.stats_data_status_rating)
    StatsData.LIFELINE -> stringResource(R.string.stats_lifeline_title)
    StatsData.SERIES -> stringResource(R.string.stats_series_title)
    StatsData.RECOMMENDATIONS -> stringResource(R.string.stats_recs_title)
    StatsData.CONTROVERSY -> stringResource(R.string.stats_data_controversy)
    StatsData.AIR_PHASE -> stringResource(R.string.stats_data_air_phase)
    StatsData.TOTAL_COLLECTS -> stringResource(R.string.stats_data_total_collects)
    StatsData.REGDATE -> stringResource(R.string.stats_data_regdate)
    StatsData.TAGS -> stringResource(R.string.stats_data_tags)
    StatsData.COLLECTION -> flowTitle(type)
    StatsData.EPISODE_DISCUSSION -> stringResource(if (type == SubjectType.MUSIC) R.string.stats_track_discussion else R.string.stats_episode_heat_title)
}

@Composable
internal fun statsModuleName(module: StatsModule, type: SubjectType): String = module.name.trim().ifEmpty { statsDataName(module.data, type) }

@Composable
internal fun StatsDrawing.label(): String = stringResource(when (this) {
    StatsDrawing.RATING_BARS -> R.string.stats_drawing_rating_bars
    StatsDrawing.RATING_ROWS -> R.string.stats_drawing_rating_rows
    StatsDrawing.RATING_DOTS -> R.string.stats_drawing_rating_dots
    StatsDrawing.STATUS_RULER -> R.string.stats_drawing_status_ruler
    StatsDrawing.STATUS_DELTA -> R.string.stats_drawing_status_delta
    StatsDrawing.COLLECTION_FLOW -> R.string.stats_drawing_collection_flow
    StatsDrawing.COLLECTION_BAR -> R.string.stats_drawing_collection_bar
    StatsDrawing.COLLECTION_RING -> R.string.stats_drawing_collection_ring
    StatsDrawing.LIFELINE -> R.string.stats_drawing_lifeline
    StatsDrawing.EPISODE_GRID -> R.string.stats_drawing_episode_grid
    StatsDrawing.EPISODE_BARS -> R.string.stats_drawing_episode_bars
    StatsDrawing.EPISODE_LINE -> R.string.stats_drawing_episode_line
    StatsDrawing.SERIES -> R.string.stats_drawing_series
    StatsDrawing.RECOMMENDATIONS -> R.string.stats_drawing_recommendations
    StatsDrawing.CONTROVERSY_SPECTRUM -> R.string.stats_drawing_controversy_spectrum
    StatsDrawing.CONTROVERSY_CAMPS -> R.string.stats_drawing_controversy_camps
    StatsDrawing.CONTROVERSY_RIDGE -> R.string.stats_drawing_controversy_ridge
    StatsDrawing.CONTROVERSY_DIAL -> R.string.stats_drawing_controversy_dial
    StatsDrawing.PHASE_SLOPE -> R.string.stats_drawing_phase_slope
    StatsDrawing.PHASE_DISTRIBUTIONS -> R.string.stats_drawing_phase_distributions
    StatsDrawing.COLLECT_LINE -> R.string.stats_drawing_collect_line
    StatsDrawing.COLLECT_STRIP -> R.string.stats_drawing_collect_strip
    StatsDrawing.REGDATE_LINE -> R.string.stats_drawing_regdate_line
    StatsDrawing.REGDATE_DELTA -> R.string.stats_drawing_regdate_delta
    StatsDrawing.TAG_CLOUD -> R.string.stats_drawing_tag_cloud
    StatsDrawing.TAG_RANK -> R.string.stats_drawing_tag_rank
})

@Composable
internal fun ControversyTier.label(): String = stringResource(when (this) {
    ControversyTier.UNANIMOUS -> R.string.stats_tier_unanimous
    ControversyTier.CONCENTRATED -> R.string.stats_tier_concentrated
    ControversyTier.SOME_DISAGREEMENT -> R.string.stats_tier_some_disagreement
    ControversyTier.MIXED -> R.string.stats_tier_mixed
    ControversyTier.POLARIZED -> R.string.stats_tier_polarized
})

/** 组内错开的间隔来自各画法的设计编排（not in M3），实际延迟经 AppMotion.stagger。 */
internal fun StatsDrawing.entranceStagger(): Long = when (this) {
    StatsDrawing.RATING_DOTS -> 5
    StatsDrawing.RATING_ROWS -> 22
    StatsDrawing.EPISODE_GRID -> 14
    StatsDrawing.EPISODE_BARS -> 12
    StatsDrawing.STATUS_RULER -> 70
    StatsDrawing.PHASE_DISTRIBUTIONS -> 150
    StatsDrawing.COLLECT_STRIP, StatsDrawing.STATUS_DELTA, StatsDrawing.REGDATE_DELTA -> 40
    StatsDrawing.TAG_CLOUD -> 25
    StatsDrawing.TAG_RANK -> 30
    StatsDrawing.COLLECTION_FLOW -> 80
    else -> 18
}

/** 按当前画法的元素数创建进度，不为十根柱启动一百段动画。换画法时新增元素直接终态。 */
internal fun StatsModule.entranceElements(tab: SubjectStatsTab): Int = when (drawing) {
    StatsDrawing.RATING_DOTS -> 100
    StatsDrawing.RATING_BARS, StatsDrawing.RATING_ROWS -> 10
    StatsDrawing.EPISODE_GRID, StatsDrawing.EPISODE_BARS, StatsDrawing.EPISODE_LINE -> tab.calculated?.episodeHeat?.cells?.size ?: 1
    StatsDrawing.COLLECT_LINE, StatsDrawing.COLLECT_STRIP -> 8
    StatsDrawing.REGDATE_LINE, StatsDrawing.REGDATE_DELTA -> 7
    StatsDrawing.STATUS_RULER, StatsDrawing.STATUS_DELTA, StatsDrawing.COLLECTION_FLOW, StatsDrawing.COLLECTION_BAR, StatsDrawing.COLLECTION_RING -> 5
    StatsDrawing.PHASE_DISTRIBUTIONS -> 3
    StatsDrawing.LIFELINE -> tab.calculated?.lifeline?.episodes?.size ?: 1
    else -> 1
}.coerceAtLeast(1)
