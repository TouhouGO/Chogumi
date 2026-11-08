package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import io.github.touhougo.chogumi.core.model.CollectionStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.LengthDistribution
import io.github.touhougo.chogumi.core.model.Popularity
import io.github.touhougo.chogumi.core.model.PopularityPoint
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.WatchRhythm
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.chart.ordinalTones
import io.github.touhougo.chogumi.core.ui.composables.StatsCard
import io.github.touhougo.chogumi.core.ui.composables.colors
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme

/** 长尾数据让四个真实比例图都有一段很大、其余正值段很小。 */
internal object CareerProportionBarFixtures {
    val Ladder = StatusLadder(wish = 9_999, doing = 1, done = 1, onHold = 0, dropped = 0)
    val Rhythm = WatchRhythm(
        rings = emptyList(),
        hours = List(24) { 0 },
        weekdays = List(7) { 0 },
        nightShare = null,
        lagTiers = listOf(9_999, 1, 1, 1, 1),
        chaseShare = 100,
        spans = emptyList(),
        medianSpanDays = null,
    )
    val Stats = CollectionStatistics(lengthDistribution = LengthDistribution(9_999, 1, 1, 1))
    val Popularity = Popularity(
        points = listOf(PopularityPoint(subjectId = 1, title = "作品", members = 100, status = CollectionType.DONE)),
        medianMembers = 100,
        niche = emptyList(),
        rankTiers = listOf(9_999, 1, 1, 1, 1),
    )
}

internal data class CareerProportionExpectation(val title: String, val colors: List<Color>, val tail: String)

/** 预期只声明数据身份与颜色，不复算任何分段坐标。 */
@Composable
internal fun careerProportionExpectations(): List<CareerProportionExpectation> {
    val unit = stringResource(R.string.career_unit_title)
    return listOf(
        CareerProportionExpectation(
            stringResource(R.string.career_ladder_description),
            listOf(CollectionType.WISH, CollectionType.DOING, CollectionType.DONE).map { it.colors().color },
            stringResource(R.string.career_tip_count, stringResource(CollectionType.DONE.labelResFor(SubjectType.ANIME)), 1, unit),
        ),
        CareerProportionExpectation(
            stringResource(R.string.career_sec_chase),
            ordinalTones(5).reversed(),
            stringResource(R.string.career_tip_share, stringResource(R.string.career_chase_later), 1, stringResource(R.string.career_unit_episode), 0),
        ),
        CareerProportionExpectation(
            stringResource(R.string.career_sec_length),
            ordinalTones(4),
            stringResource(R.string.career_tip_share, stringResource(R.string.career_length_over_52), 1, unit, 0),
        ),
        CareerProportionExpectation(
            stringResource(R.string.career_sub_rank),
            ordinalTones(4).reversed() + MaterialTheme.colorScheme.surfaceContainerHighest,
            stringResource(R.string.career_tip_count, stringResource(R.string.career_rank_none), 1, unit),
        ),
    )
}

/** 测试与捕获共用真实页面区块，宽度来自设备窗口，字号与主题来自本轮参数。 */
@Composable
internal fun CareerProportionHost(fontScale: Float, darkTheme: Boolean, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        ChogumiTheme(darkTheme = darkTheme) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
                content()
            }
        }
    }
}

@Composable
internal fun CareerProportionSections() {
    val unit = stringResource(R.string.career_unit_title)
    StatsCard { LadderSummary(CareerProportionBarFixtures.Ladder, SubjectType.ANIME) }
    RhythmSections(CareerProportionBarFixtures.Rhythm)
    LengthSection(CareerProportionBarFixtures.Stats, unit)
    PopularitySection(CareerProportionBarFixtures.Popularity, unit, onSubjectClick = {})
}
