package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.statistics.LifelineCard
import io.github.touhougo.chogumi.core.model.statistics.LifelineEpisode
import io.github.touhougo.chogumi.core.model.statistics.ScoreCard
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 统计辅助块的初始失败必须原位可见、可重试；加载与确认空仍沿用原来的呈现。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w400dp-h800dp")
class SubjectStatsSectionErrorTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val retryText get() = context.getString(R.string.retry)

    @Test
    fun `三个独立辅助块失败各显示一个可点击重试，其他统计内容仍显示`() {
        val ratingRetries = AtomicInteger()
        val recRetries = AtomicInteger()
        val collectRetries = AtomicInteger()

        show(
            tab = failedTab(),
            onRetryRatingSplit = ratingRetries::incrementAndGet,
            onRetryRecs = recRetries::incrementAndGet,
            onRetryRecentCollectTimes = collectRetries::incrementAndGet,
        )

        compose.onNodeWithText(context.getString(R.string.stats_lifeline_title)).assertIsDisplayed()
        compose.onNodeWithTag(StatsRecsTitleTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.stats_open_web)).assertExists()
        compose.onAllNodesWithText(retryText).assertCountEquals(3)
        compose.onNode(hasText(retryText) and hasAnyAncestor(hasTestTag(StatsRatingSplitErrorTag))).performClick()
        compose.onNode(hasText(retryText) and hasAnyAncestor(hasTestTag(StatsRecentCollectTimesErrorTag))).performClick()
        compose.onNode(hasText(retryText) and hasAnyAncestor(hasTestTag(StatsRecsErrorTag))).performScrollTo().performClick()
        assertEquals(1, ratingRetries.get())
        assertEquals(1, collectRetries.get())
        assertEquals(1, recRetries.get())
    }

    @Test
    fun `推荐确认空不画，等待保留骨架且其他块状态不变`() {
        var tab by mutableStateOf(failedTab().copy(recs = Load.Ready(emptyList())))
        compose.setContent {
            ChogumiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    StatsSection(tab, {}, {}, {}, {})
                }
            }
        }
        compose.onAllNodesWithText(context.getString(R.string.stats_recs_title)).assertCountEquals(0)
        compose.onAllNodesWithText(retryText).assertCountEquals(2)

        compose.runOnIdle { tab = failedTab().copy(recs = Load.Pending) }
        compose.waitForIdle()
        compose.onAllNodesWithText(retryText).assertCountEquals(2)
        compose.onAllNodesWithText(context.getString(R.string.stats_recs_title)).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.stats_lifeline_title)).assertIsDisplayed()
    }

    private fun show(
        tab: SubjectStatsTab,
        onRetryRatingSplit: () -> Unit = {},
        onRetryRecs: () -> Unit = {},
        onRetryRecentCollectTimes: () -> Unit = {},
    ) {
        compose.setContent {
            ChogumiTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    StatsSection(
                        tab = tab,
                        onSubjectClick = {},
                        onRetryRatingSplit = onRetryRatingSplit,
                        onRetryRecs = onRetryRecs,
                        onRetryRecentCollectTimes = onRetryRecentCollectTimes,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun failedTab() = SubjectStatsTab(
        subject = SubjectBo(
            id = 8,
            type = SubjectType.ANIME,
            name = "work",
            nameCn = "作品",
            ratingCounts = listOf(0, 0, 0, 0, 0, 0, 0, 2, 8, 10),
            ratingScore = 8.4,
            ratingTotal = 20,
        ),
        score = ScoreCard(
            counts = listOf(0, 0, 0, 0, 0, 0, 0, 2, 8, 10),
            byCollection = emptyList(),
            mine = null,
        ),
        ratingSplit = Load.Failed(Failure.Http(500)),
        episodeHeat = Load.Ready(null),
        series = Load.Ready(SubjectSeries(emptyList(), emptyList())),
        lifeline = Load.Ready(
            LifelineCard(
                phases = emptyList(),
                episodes = listOf(
                    LifelineEpisode(1, 1.0, LocalDate.of(2026, 1, 1), 12),
                    LifelineEpisode(2, 2.0, LocalDate.of(2026, 1, 8), 18),
                ),
                ended = true,
                sequels = emptyList(),
                pace = null,
            ),
        ),
        recentCollectTimes = Load.Failed(Failure.Http(501)),
        flow = null,
        recs = Load.Failed(Failure.Http(502)),
    )
}
