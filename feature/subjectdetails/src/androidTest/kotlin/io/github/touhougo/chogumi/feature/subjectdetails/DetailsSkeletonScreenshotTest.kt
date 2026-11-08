package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.AppFeatures
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.composables.rememberCollapsibleHeaderState
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 详情页首屏与章节网格的骨架、真实内容各一张截图（字体比例 1 与 2），叠起来看对不对得上
 * （[saveScreenshot] 的 `details-skeleton/`）。位置由 [DetailsSkeletonGeometryTest] 逐边断言。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class DetailsSkeletonScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)
    private var episodes by mutableStateOf(emptyList<EpisodeBo>())

    private companion object {
        const val FOLDER = "details-skeleton"
        val features = AppFeatures(community = true, publicRatings = true, coverScores = true, friendMarks = true)
    }

    @Test
    fun 详情页首屏骨架与内容各存一张截图() = captureFirstScreen(fontScale = 1f, prefix = "details")

    @Test
    fun 字体放大两倍时详情页首屏骨架与内容各存一张截图() = captureFirstScreen(fontScale = 2f, prefix = "details-font2x")

    @Test
    fun 章节网格骨架与内容各存一张截图() = captureEpisodes(fontScale = 1f, prefix = "episodes")

    @Test
    fun 字体放大两倍时章节网格骨架与内容各存一张截图() = captureEpisodes(fontScale = 2f, prefix = "episodes-font2x")

    /** 半截条目（列表点进来时本地那份）→ 完整条目：两态留证。 */
    @Test
    fun 半截条目与完整条目各存一张截图() =
        capturePartialToFull(fontScale = 1f, prefix = "details-partial")

    @Test
    fun 字体放大两倍时半截条目与完整条目各存一张截图() =
        capturePartialToFull(fontScale = 2f, prefix = "details-partial-font2x")

    private var firstScreenSubject by mutableStateOf(DetailsSkeletonFixtures.partialSubject)

    private fun capturePartialToFull(fontScale: Float, prefix: String) = capture(
        fontScale = fontScale,
        prefix = prefix,
        height = 1100,
        pairNames = "partial" to "full",
        flipToReal = { firstScreenSubject = DetailsSkeletonFixtures.fullSubject },
    ) {
        SubjectDetailsFirstScreen(
            subject = firstScreenSubject,
            topPadding = 24.dp,
            features = features,
            sections = visibleSectionsFor(firstScreenSubject, features),
            selectedSection = SubjectDetailsSection.OVERVIEW,
            onSelectSection = {},
            onInfoClick = {},
            onTagClick = {},
            headerState = rememberCollapsibleHeaderState(),
            content = {},
        )
    }

    private fun captureFirstScreen(fontScale: Float, prefix: String) = capture(
        fontScale = fontScale,
        prefix = prefix,
        height = 1100,
        flipToReal = {},
    ) {
        if (showReal) {
            SubjectDetailsFirstScreen(
                subject = DetailsSkeletonFixtures.subject,
                topPadding = 24.dp,
                features = features,
                sections = visibleSectionsFor(DetailsSkeletonFixtures.subject, features),
                selectedSection = SubjectDetailsSection.OVERVIEW,
                onSelectSection = {},
                onInfoClick = {},
                onTagClick = {},
                headerState = rememberCollapsibleHeaderState(),
                content = {},
            )
        } else {
            SubjectDetailsPageSkeleton(topPadding = 24.dp, features = features)
        }
    }

    private fun captureEpisodes(fontScale: Float, prefix: String) = capture(
        fontScale = fontScale,
        prefix = prefix,
        height = 360,
        flipToReal = { episodes = DetailsSkeletonFixtures.episodes(count = 14) },
    ) {
        EpisodesSection(
            subjectType = SubjectType.ANIME,
            episodes = AsyncList(if (showReal) Load.Ready(episodes) else Load.Pending, onRetry = {}),
            watchStates = emptyMap(),
            today = LocalDate.of(2026, 10, 3),
            expectedEpisodes = 14,
            pendingCommit = null,
            offerMarkDone = false,
            onWatchUpTo = gatedNoop1(),
            onToggleSpecial = gatedNoop1(),
            onMarkDone = gatedNoop(),
            onEpisodeLongPress = {},
        )
    }

    private fun capture(
        fontScale: Float,
        prefix: String,
        height: Int,
        pairNames: Pair<String, String> = "skeleton" to "content",
        flipToReal: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.size(width = 400.dp, height = height.dp)) { content() }
                }
            }
        }
        listOf(pairNames.first to false, pairNames.second to true).forEach { (name, real) ->
            composeRule.runOnIdle {
                if (real) flipToReal()
                showReal = real
            }
            composeRule.waitForIdle()
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, "$prefix-$name", bitmap)
        }
    }
}
