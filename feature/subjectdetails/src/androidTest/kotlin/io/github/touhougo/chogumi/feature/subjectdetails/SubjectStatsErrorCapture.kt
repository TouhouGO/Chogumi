package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.defaultStatsLayout
import io.github.touhougo.chogumi.core.model.statistics.LifelineCard
import io.github.touhougo.chogumi.core.model.statistics.LifelineEpisode
import io.github.touhougo.chogumi.core.model.statistics.ScoreCard
import io.github.touhougo.chogumi.core.model.statistics.SubjectStats
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 统计 tab 三个独立辅助读取同时失败时，页面实际调用的 [statsTabItems] 在各窗口与主题下的人工验收图。 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class SubjectStatsErrorCapture {
    @get:Rule val compose = createComposeRule()

    @Test
    fun 浅色统计辅助块失败() = capture(dark = false, suffix = "light")

    @Test
    fun 深色统计辅助块失败() = capture(dark = true, suffix = "dark")

    private fun capture(dark: Boolean, suffix: String) {
        compose.setContent { CaptureContent(dark) }
        compose.waitForIdle()
        saveScreenshot(FOLDER, "stats-errors-$suffix", compose.onRoot().captureToImage().asAndroidBitmap())
    }

    @Composable
    private fun CaptureContent(dark: Boolean) {
        ChogumiTheme(darkTheme = dark) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                val listState = rememberLazyListState()
                val entrances = rememberStatsEntrances(subject.id)
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
                    statsTabItems(failedTab, defaultStatsLayout(), listState, entrances, {}, {}, {}, {}, {}, {})
                }
            }
        }
    }

    private companion object {
        const val FOLDER = "subject-stats-errors"
        val subject = SubjectBo(
            id = 8,
            type = SubjectType.ANIME,
            name = "work",
            nameCn = "作品",
            ratingScore = 8.4,
            ratingRank = 28,
            ratingTotal = 250,
            ratingCounts = listOf(1, 2, 3, 4, 8, 12, 35, 78, 72, 35),
        )
        val lifeline = LifelineCard(
            phases = emptyList(),
            episodes = listOf(
                LifelineEpisode(1, 1.0, LocalDate.of(2026, 1, 1), 12),
                LifelineEpisode(2, 2.0, LocalDate.of(2026, 1, 8), 18),
            ),
            ended = true,
            sequels = emptyList(),
            pace = null,
        )
        val failedTab = SubjectStatsTab(
            subject = subject,
            calculated = SubjectStats(
                score = ScoreCard(subject.ratingCounts, emptyList(), mine = null), episodeHeat = null, lifeline = lifeline, flow = null,
                controversy = null, airPhases = emptyList(), totalCollects = emptyList(), regdate = emptyList(), tags = emptyList(),
            ),
            ratingSplit = Load.Failed(Failure.Http(500)),
            episodeHeat = Load.Ready(null),
            series = Load.Ready(SubjectSeries(emptyList(), emptyList())),
            lifeline = Load.Ready(lifeline),
            recentCollectTimes = Load.Failed(Failure.Http(501)),
            recs = Load.Failed(Failure.Http(502)),
        )
    }
}
