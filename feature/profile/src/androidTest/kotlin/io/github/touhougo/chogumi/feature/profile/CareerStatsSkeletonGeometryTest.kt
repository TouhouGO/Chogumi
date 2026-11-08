package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CareerStatistics
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.RatedSubject
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.WatchTime
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 完整统计页的骨架与真内容逐块同框：大数字一张、下面每一块的上下沿都对得上，数据到了页面不跳；放大两倍字号时
 * 标题、答案折成几行、图里的字变多高，骨架跟着一起变。
 *
 * 真内容是本人动画的一份常见满页：区块和各处数的位数与骨架样例相同，数和说法不同（均分比大众高还是持平、大数字、平均晚几年）。
 * 答案句的行数随数的位数变，位数不同时放大字号下多折一行是数据本身的差别，骨架不可能预知。
 * 量的地方可纵向滚动（不限高），与统计页一样。
 */
@RunWith(AndroidJUnit4::class)
class CareerStatsSkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    private fun setContent(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(PageWidth).verticalScroll(rememberScrollState())) {
                        val state = if (showReal) RealState else CareerState(isSelf = true)
                        CareerStatsContent(state, publicRatings = true, onSummarize = {}, onRetryOwner = {}, onSubjectClick = {}, onPersonClick = {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun sections() = composeRule.onAllNodesWithTag(StatSectionTag, useUnmergedTree = true)

    private fun assertSameFrames(fontScale: Float) {
        setContent(fontScale)
        val skeletonSections = sections().fetchSemanticsNodes().size
        val kpi = { composeRule.onAllNodesWithTag(CareerKpiRowTag, useUnmergedTree = true)[0] }
        assertSkeletonMatchesReal(
            anchors = listOf(SkeletonAnchor("大数字", kpi, kpi)) +
                (0 until skeletonSections).map { i -> SkeletonAnchor("第 ${i + 1} 块", { sections()[i] }, { sections()[i] }) },
            flipToReal = {
                composeRule.runOnIdle { showReal = true }
                composeRule.waitForIdle()
            },
        )
    }

    @Test
    fun 骨架与真内容逐块同框() = assertSameFrames(fontScale = 1f)

    @Test
    fun 字体放大两倍时骨架与真内容逐块同框() = assertSameFrames(fontScale = 2f)

    private companion object {
        /** 380dp 的手机：统计卡自带左右 16dp 外边距。 */
        val PageWidth = 380.dp

        val RealState: CareerState = run {
            val sample = careerSkeletonStats(SubjectType.ANIME, isSelf = true)
            val verdict = sample.ratingVerdict!!
            val rhythm = sample.rhythm!!
            val stats = sample.copy(
                statusCounts = mapOf(CollectionType.DONE to 204, CollectionType.DOING to 7, CollectionType.WISH to 315),
                episodeProgress = 2_048,
                watchTime = WatchTime(minutes = 2_048 * 24, estimated = false),
                scoreCounts = mapOf(6 to 3, 7 to 12, 8 to 31, 9 to 26, 10 to 12),
                topRated = sample.topRated.map { RatedSubject(it.subject, score = 10) },
                timeMachine = sample.timeMachine.copy(averageLag = 11.5),
                popularity = sample.popularity!!.copy(medianMembers = 6_215),
                ratingVerdict = verdict.copy(myAverage = 8.3, publicAverage = 7.1, gap = 1.2),
                rhythm = rhythm.copy(
                    rings = rhythm.rings.map { it.copy(episodes = 512, activeDays = 301, longestStreak = 97) },
                    nightShare = 41,
                    medianSpanDays = 45,
                ),
            )
            CareerState(isSelf = true, ownStats = CareerStatistics(byType = mapOf(SubjectType.ANIME to stats), overall = stats))
        }
    }
}
