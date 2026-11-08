package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.HIGH_SCORE_MIN
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.TimeMachine
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.BubbleCell
import io.github.touhougo.chogumi.core.ui.chart.BubbleMatrix
import io.github.touhougo.chogumi.core.ui.chart.QuadrantChart
import io.github.touhougo.chogumi.core.ui.chart.QuadrantLabels
import io.github.touhougo.chogumi.core.ui.chart.QuadrantPoint
import io.github.touhougo.chogumi.core.ui.chart.TenBars
import io.github.touhougo.chogumi.core.ui.composables.AdaptiveGridRows
import io.github.touhougo.chogumi.core.ui.composables.CoverBadge
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeInset
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.formatOneDecimal
import io.github.touhougo.chogumi.core.ui.composables.formatScore
import kotlin.math.abs

// ---- 时光机 ----

@Composable
internal fun TimeMachineSection(machine: TimeMachine, unit: String) {
    if (machine.cells.isEmpty()) return
    val title = stringResource(R.string.career_sec_time_machine)
    val lag = machine.averageLag?.let(::formatOneDecimal)
    val answer = if (lag != null) {
        highlighted(stringResource(R.string.career_ans_lag, lag), lag)
    } else {
        val added = machine.cells.sumOf { it.subjects.size }
        highlighted(stringResource(R.string.career_ans_added, added, unit), "$added")
    }
    val tips = machine.cells.map { cell ->
        stringResource(
            R.string.career_tip_time, cell.year, cell.subjects.size, unit,
            stringResource(R.string.career_decade_name, cell.decade), titlesOf(cell.subjects),
        )
    }
    val cells = machine.cells.mapIndexed { i, cell ->
        BubbleCell(machine.decades.indexOf(cell.decade), machine.years.indexOf(cell.year), cell.subjects.size, tips[i])
    }
    val decadeLabels = machine.decades.map { stringResource(R.string.career_decade_short, it % 100) }
    StatSection(title, answer) {
        BubbleMatrix(decadeLabels, machine.years.map { "$it" }, cells, description = "$title $answer")
    }
}


/** 气泡里列几部作品名，多了写「等」。 */
@Composable
private fun titlesOf(subjects: List<SubjectBo>): String = namesOf(subjects.map { displayNameOf(it) })


@Composable
internal fun namesOf(titles: List<String>): String {
    val names = titles.take(TIP_TITLES).joinToString(stringResource(R.string.list_separator))
    return if (titles.size > TIP_TITLES) stringResource(R.string.career_titles_more, names) else names
}


private const val TIP_TITLES = 3


// ---- 打分 + 口味象限 ----

@Composable
internal fun ScoreSections(stats: CollectionStatistics, unit: String, who: String, publicRatings: Boolean) {
    if (stats.ratedCount == 0) return
    val title = stringResource(R.string.career_sec_score)
    val counts = (1..10).map { stats.scoreCounts[it] ?: 0 }
    val view = scoreViewOf(stats, publicRatings)
    val answer = when {
        view == null -> {
            val need = ratingsStillNeeded(stats)
            highlighted(stringResource(R.string.career_ans_score_need, need, unit), "$need")
        }
        !view.showPublic -> formatScore(view.verdict.myAverage).let { highlighted(stringResource(R.string.career_ans_score_mine, it), it) }
        else -> {
            val mine = formatScore(view.verdict.myAverage)
            val gap = formatScore(abs(view.verdict.gap))
            val text = when (tasteLeanOf(view.verdict.gap)) {
                TasteLean.SAME -> stringResource(R.string.career_ans_score_same, mine)
                TasteLean.STRICTER -> stringResource(R.string.career_ans_score_lower, mine, gap)
                TasteLean.LENIENT -> stringResource(R.string.career_ans_score_higher, mine, gap)
            }
            highlighted(text, mine)
        }
    }
    StatSection(title, answer) {
        Column {
            TenBars(
                counts = counts,
                tips = counts.mapIndexed { i, n -> pluralStringResource(R.plurals.career_tip_score, i + 1, i + 1, n, unit) },
                description = "$title $answer",
            )
            if (view != null && view.showPublic) {
                val gapTitle = stringResource(R.string.career_sub_gap, who)
                StatSubtitle(gapTitle)
                GapStrip(
                    bins = gapBinsOf(view.verdict.works),
                    tip = { stringResource(R.string.career_tip_gap, it.title, who, it.myScore, formatScore(it.publicScore)) },
                    unit = unit,
                    description = gapTitle,
                )
            }
        }
    }
    if (view == null || !view.showPublic) return
    val quadrants = tasteQuadrantsOf(view.verdict)
    val quadrantTitle = stringResource(R.string.career_sec_quadrant)
    val personal = quadrants.personal.size
    val quadrantAnswer = highlighted(stringResource(R.string.career_ans_quadrant, personal, unit), "$personal")
    // 点色按所在的角：公认神作 primary、私心偏爱 tertiary、众望难符 secondary、公认一般 outline（M3 颜色角色）
    val scheme = MaterialTheme.colorScheme
    val colorOf = buildMap {
        quadrants.acclaimed.forEach { put(it.subjectId, scheme.primary) }
        quadrants.personal.forEach { put(it.subjectId, scheme.tertiary) }
        quadrants.letdown.forEach { put(it.subjectId, scheme.secondary) }
        quadrants.ordinary.forEach { put(it.subjectId, scheme.outline) }
    }
    val points = view.verdict.works.map {
        QuadrantPoint(
            x = it.publicScore,
            y = it.myScore.toDouble(),
            color = colorOf.getValue(it.subjectId),
            tip = stringResource(R.string.career_tip_gap, it.title, who, it.myScore, formatScore(it.publicScore)),
        )
    }
    StatSection(quadrantTitle, quadrantAnswer) {
        QuadrantChart(
            points = points,
            centerX = view.verdict.publicAverage,
            centerY = view.verdict.myAverage,
            labels = QuadrantLabels(
                topEnd = stringResource(R.string.career_quad_acclaimed, quadrants.acclaimed.size),
                topStart = stringResource(R.string.career_quad_personal, personal),
                bottomEnd = stringResource(R.string.career_quad_letdown, quadrants.letdown.size),
                bottomStart = stringResource(R.string.career_quad_ordinary, quadrants.ordinary.size),
            ),
            description = "$quadrantTitle $quadrantAnswer",
        )
    }
}


// ---- 最爱 ----

@Composable
internal fun FavoritesSection(stats: CollectionStatistics, unit: String, onSubjectClick: (Int) -> Unit) {
    if (stats.topRated.isEmpty()) return
    val count = highRatedCountOf(stats)
    StatSection(
        stringResource(R.string.career_sec_favorites),
        highlighted(stringResource(R.string.career_a_top_rated, count, unit, HIGH_SCORE_MIN), "$count"),
    ) {
        AdaptiveGridRows(stats.topRated, GridCell.Cover, sideMargin = 0.dp, rowSpacing = COVER_ROW_SPACING) { rated ->
            SubjectPoster(
                url = rated.subject.coverUrl,
                modifier = Modifier.fillMaxWidth(),
                description = displayNameOf(rated.subject),
                onClick = { onSubjectClick(rated.subject.id) },
            ) {
                CoverBadge("${rated.score}", Modifier.align(Alignment.BottomStart).padding(CoverBadgeInset))
            }
        }
    }
}


/** 封面格的行距与格距相同（[GridCell.Cover] 的 8dp）。 */
internal val COVER_ROW_SPACING = 8.dp
