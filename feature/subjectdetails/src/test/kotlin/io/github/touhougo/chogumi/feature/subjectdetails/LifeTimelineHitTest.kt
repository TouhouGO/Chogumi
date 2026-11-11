package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.model.statistics.LifelineCard
import io.github.touhougo.chogumi.core.model.statistics.LifelineEpisode
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 一生时间线点哪一根柱就出哪一集：柱密到相邻几根要的那一列互相叠着时也一样。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h800dp-xhdpi")
class LifeTimelineHitTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val chart get() = compose.onNodeWithContentDescription(context.getString(R.string.stats_lifeline_description))

    private fun card(episodes: Int) = LifelineCard(
        phases = emptyList(),
        episodes = List(episodes) { i -> LifelineEpisode(i + 1, i + 1.0, LocalDate.of(2024, 1, 1).plusWeeks(i.toLong()), 5 + i % 7) },
        ended = true,
        sequels = emptyList(),
        pace = null,
    )

    private fun tipOf(episode: LifelineEpisode) =
        context.resources.getQuantityString(R.plurals.stats_episode_tip, episode.comments, formatEpisodeSort(episode.sort), episode.comments)

    private fun show(card: LifelineCard) {
        compose.setContent { ChogumiTheme { Box(Modifier.width(360.dp)) { LifeTimeline(card) } } }
        compose.waitForIdle()
    }

    /** 点第 [index] 根柱所在那一列里、离列底 [aboveBase] 像素的地方（列底就是柱底）。 */
    private fun tapColumn(card: LifelineCard, index: Int, aboveBase: Float) {
        val origin = chart.fetchSemanticsNode().boundsInRoot.topLeft
        val column = compose.onNodeWithContentDescription(tipOf(card.episodes[index])).fetchSemanticsNode().boundsInRoot
        chart.performTouchInput { click(Offset(column.center.x, column.bottom - aboveBase) - origin) }
    }

    @Test
    fun `一百集挤在一起时_点在一根柱上出的是这一集`() {
        val card = card(episodes = 100)
        show(card)

        listOf(0, 40, 97).forEach { index ->
            tapColumn(card, index, aboveBase = 2f)
            compose.onNodeWithText(tipOf(card.episodes[index])).assertExists()
        }
    }

    @Test
    fun `柱稀疏时_点在柱上方的空白出这一列的那一集`() {
        val card = card(episodes = 12)
        show(card)

        tapColumn(card, index = 5, aboveBase = 100f)
        compose.onNodeWithText(tipOf(card.episodes[5])).assertExists()
    }
}
