package io.github.touhougo.chogumi.feature.profile

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.Lineup
import io.github.touhougo.chogumi.core.model.Popularity
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.statistics.percentOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.ProportionBar
import io.github.touhougo.chogumi.core.ui.chart.ProportionSegment
import io.github.touhougo.chogumi.core.ui.chart.RankBar
import io.github.touhougo.chogumi.core.ui.chart.RankBars
import io.github.touhougo.chogumi.core.ui.chart.Treemap
import io.github.touhougo.chogumi.core.ui.chart.TreemapTile
import io.github.touhougo.chogumi.core.ui.chart.UnitCells
import io.github.touhougo.chogumi.core.ui.chart.UnitGroup
import io.github.touhougo.chogumi.core.ui.chart.ordinalTones
import io.github.touhougo.chogumi.core.ui.chart.percentCells
import io.github.touhougo.chogumi.core.ui.chart.shownPercentOf
import io.github.touhougo.chogumi.core.ui.composables.AdaptiveGridRows
import io.github.touhougo.chogumi.core.ui.composables.ColorSwatch
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.composables.formatScore
import io.github.touhougo.chogumi.core.ui.composables.labelResFor

// ---- 声优、制作公司（看过和在看的作品的阵容） ----

@Composable
internal fun LineupSections(lineup: Lineup, unit: String, onPersonClick: (Int) -> Unit) {
    lineup.voiceActors.firstOrNull()?.let { top ->
        val title = stringResource(R.string.career_sec_voice)
        val answer = highlighted(stringResource(R.string.career_ans_voice, top.name.name, top.works, unit), top.name.name)
        StatSection(title, answer) {
            Column {
                VoicePodium(lineup.voiceActors.take(PODIUM_SIZE), { stringResource(R.string.career_count, it, unit) }, onPersonClick)
                val rest = lineup.voiceActors.drop(PODIUM_SIZE)
                if (rest.isNotEmpty()) RankBars(rest.map { RankBar(it.name.name, it.works) }, Modifier.padding(top = 16.dp))
            }
        }
    }
    lineup.studios.firstOrNull()?.let { top ->
        val title = stringResource(R.string.career_sec_studio)
        val answer = highlighted(stringResource(R.string.career_ans_studio, top.name.name, top.works, unit), top.name.name)
        StatSection(title, answer) { RankBars(lineup.studios.map { RankBar(it.name.name, it.works) }) }
    }
}


// ---- 题材版图 ----

@Composable
internal fun GenreSection(stats: CollectionStatistics, unit: String) {
    val genres = stats.genreCounts.take(TREEMAP_GENRES).takeIf { it.isNotEmpty() } ?: return
    val title = stringResource(R.string.career_sec_genres)
    val top = genres.first()
    val answer = highlighted(stringResource(R.string.career_a_genre, top.label, top.count, unit), "${top.count}")
    val tiles = genres.map { genre ->
        val average = stats.genreRatings[genre.label]
        TreemapTile(
            label = genre.label,
            count = genre.count,
            tone = average?.let(::treemapToneOf),
            tip = if (average != null) stringResource(R.string.career_tip_genre, genre.label, genre.count, unit, formatScore(average))
            else stringResource(R.string.career_tip_count, genre.label, genre.count, unit),
        )
    }
    StatSection(title, answer) { Treemap(tiles, GENRE_TONES, "$title $answer") }
}


/** 题材版图最多几块：再多块就小得写不下名字（我的设计决定，not in M3）。 */
private const val TREEMAP_GENRES = 10


// ---- 冷门度 ----

@Composable
internal fun PopularitySection(popularity: Popularity, unit: String, onSubjectClick: (Int) -> Unit) {
    val format = rememberMembersFormat()
    val title = stringResource(R.string.career_sec_popularity)
    val median = format(popularity.medianMembers)
    val answer = highlighted(stringResource(R.string.career_ans_popularity, median), median)
    StatSection(title, answer) {
        Column {
            PopularityStrip(
                points = popularity.points,
                median = popularity.medianMembers,
                tip = { stringResource(R.string.career_tip_members, it.title, format(it.members)) },
                membersLabel = format,
                unit = unit,
                description = "$title $answer",
            )
            if (popularity.niche.isNotEmpty()) {
                StatSubtitle(stringResource(R.string.career_sub_niche, popularity.niche.size, unit))
                AdaptiveGridRows(popularity.niche, GridCell.Cover, sideMargin = 0.dp, rowSpacing = COVER_ROW_SPACING) { subject ->
                    Column {
                        SubjectPoster(
                            url = subject.coverUrl,
                            modifier = Modifier.fillMaxWidth(),
                            description = displayNameOf(subject),
                            onClick = { onSubjectClick(subject.id) },
                        )
                        Text(
                            stringResource(R.string.career_members, format(subject.collectionTotal)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            val rankTitle = stringResource(R.string.career_sub_rank)
            StatSubtitle(rankTitle)
            val labels = RankTierLabels.map { stringResource(it) }
            val tones = ordinalTones(RankTierLabels.size - 1).reversed() + MaterialTheme.colorScheme.surfaceContainerHighest
            ProportionBar(
                segments = popularity.rankTiers.mapIndexed { i, count ->
                    ProportionSegment(count, tones[i], stringResource(R.string.career_tip_count, labels[i], count, unit))
                },
                description = rankTitle,
                height = RANK_BAR_HEIGHT,
            )
            SwatchLegend(labels.indices.filter { popularity.rankTiers[it] > 0 }.map { tones[it] to labels[it] })
        }
    }
}


/** 排名档的名字（顺序同 [io.github.touhougo.chogumi.core.model.RANK_TIER_LIMITS]，最后是没有排名）。 */
private val RankTierLabels = listOf(
    R.string.career_rank_tier_100,
    R.string.career_rank_tier_500,
    R.string.career_rank_tier_2000,
    R.string.career_rank_tier_rest,
    R.string.career_rank_none,
)


private val RANK_BAR_HEIGHT = 28.dp


/** 收藏人数的短写法（「1.2万」「12K」）：跟随系统语言，用 ICU 的紧凑数字格式。 */
@Composable
private fun rememberMembersFormat(): (Int) -> String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        val format = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
        val formatMembers: (Int) -> String = { members -> format.format(members) }
        formatMembers
    }
}


/** 色块 + 名字的一排图例，放不下就换行。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SwatchLegend(items: List<Pair<Color, String>>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth().padding(start = 2.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorSwatch(color)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


// ---- 篇幅（仅动画） ----

@Composable
internal fun LengthSection(stats: CollectionStatistics, unit: String) {
    val lengths = stats.lengthDistribution?.takeIf { it.total > 0 } ?: return
    val labels = LengthBucketLabels.map { stringResource(it) }
    val counts = lengths.counts
    val tones = ordinalTones(counts.size)
    val peak = counts.indices.toList().peakBy { counts[it] } ?: return
    val title = stringResource(R.string.career_sec_length)
    val share = percentOf(counts[peak], lengths.total)
    val answer = highlighted(stringResource(R.string.career_a_length, labels[peak], share), "$share%")
    StatSection(title, answer) {
        Column {
            ProportionBar(
                segments = counts.mapIndexed { i, count ->
                    ProportionSegment(
                        count, tones[i],
                        stringResource(R.string.career_tip_share, labels[i], count, unit, percentOf(count, lengths.total)),
                    )
                },
                description = "$title $answer",
                height = LENGTH_BAR_HEIGHT,
            )
            SwatchLegend(labels.indices.filter { counts[it] > 0 }.map { tones[it] to labels[it] })
        }
    }
}


internal val LENGTH_BAR_HEIGHT = 44.dp


// ---- 收藏走到哪：百分格 + 填坑率 ----

@Composable
internal fun StatusSection(ladder: StatusLadder, stats: CollectionStatistics, type: SubjectType, unit: String) {
    if (ladder.total == 0) return
    val order = ladder.waffleOrder
    val labels = order.map { (status, _) -> stringResource(status.labelResFor(type)) }
    val cells = percentCells(order.map { it.second })
    val percents = order.mapIndexed { i, (_, count) ->
        shownPercentOf(cells[i], count)?.let { stringResource(R.string.stats_share_percent, it.toString()) } ?: stringResource(R.string.stats_share_under_one)
    }
    val title = stringResource(R.string.career_sec_status)
    val answer = highlighted(
        stringResource(
            R.string.career_ans_status, ladder.done, unit, stringResource(CollectionType.DONE.labelResFor(type)),
            ladder.doing, stringResource(CollectionType.DOING.labelResFor(type)),
        ),
        "${ladder.done}",
    )
    StatSection(title, answer) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                UnitCells(
                    groups = order.mapIndexed { i, (status, count) ->
                        UnitGroup(status, cells[i], stringResource(R.string.career_tip_cell, labels[i], count, unit, percents[i]))
                    },
                    columns = PERCENT_GRID_COLUMNS,
                    description = "$title $answer",
                    modifier = Modifier.width(PERCENT_GRID_WIDTH),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    order.forEachIndexed { i, (status, count) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorSwatch(status.colors().color)
                            Text(labels[i], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).alignByBaseline())
                            Text("$count", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alignByBaseline())
                            Text(
                                percents[i],
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.alignByBaseline(),
                            )
                        }
                    }
                }
            }
            stats.finishRate?.let { rate ->
                KpiRow(
                    listOf(KpiText(formatCount(rate), stringResource(R.string.career_unit_percent), stringResource(R.string.career_kpi_finish_rate))),
                    Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}


/** 百分格：10 列 × 10 行，一格 1%；宽 148dp 是我的设计决定（not in M3），旁边留给图例。 */
private const val PERCENT_GRID_COLUMNS = 10

private val PERCENT_GRID_WIDTH = 148.dp
