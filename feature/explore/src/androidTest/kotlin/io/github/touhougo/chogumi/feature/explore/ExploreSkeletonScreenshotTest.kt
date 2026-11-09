package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
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
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.ui.testing.VisualCapture
import io.github.touhougo.chogumi.core.ui.testing.saveScreenshot
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TRENDING_SHELF_ID
import io.github.touhougo.chogumi.core.model.WorkShelfContent
import io.github.touhougo.chogumi.core.model.WorkShelfStyle

/**
 * 热门轮播与首个海报货架的骨架与真实内容同画面截图（[saveScreenshot] 的 `explore-skeleton/`），字号比例 1 与 2 各一对。
 * 固定容器是截图的窗口选择（非 M3 规范），两态在同一组合里切换。
 */
@VisualCapture
@RunWith(AndroidJUnit4::class)
class ExploreSkeletonScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private var showReal by mutableStateOf(false)
    private var dark by mutableStateOf(false)

    @Test
    fun 骨架与内容各存一张截图() = capturePair(fontScale = 1f, prefix = "explore")

    @Test
    fun 字体放大两倍时骨架与内容各存一张截图() = capturePair(fontScale = 2f, prefix = "explore-font2x")

    /** 导航接线不改视觉：同一真实 Explore 页面在当前设备宽度与字号的浅深色各留一张。 */
    @Test
    fun 导航接线视觉_浅深色() {
        composeRule.setContent {
            ChogumiTheme(darkTheme = dark) {
                Surface {
                    Box(Modifier.fillMaxSize()) {
                        ExploreContent(
                            state = state(real = true),
                            onSelectType = {},
                            onRefresh = {},
                            onTilesShown = { _, _, _ -> },
                            onSubjectClick = {},
                            onOpenSearch = { _, _, _ -> },
                            onOpenCalendar = {},
                            arrangeActions = NoArrangeActions,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val widthDp = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp
        listOf(false, true).forEach { isDark ->
            composeRule.runOnIdle { dark = isDark }
            composeRule.waitForIdle()
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, "navigation-${widthDp}dp-${if (isDark) "dark" else "light"}", bitmap)
        }
    }

    private fun state(real: Boolean): ExploreUiState {
        val criteria = SearchCriteria(subjectType = SubjectType.ANIME)
        val content = if (real) {
            Load.Ready((1..8).map { id -> SubjectBo(id = id, type = SubjectType.ANIME, name = "作品$id", nameCn = "作品$id") })
        } else {
            Load.Pending
        }
        return ExploreUiState(
            today = FIXTURE_TODAY,
            selectedType = SubjectType.ANIME,
            layouts = mapOf(SubjectType.ANIME to ExploreLayout(entries = emptyList(), shelves = listOf(ExploreShelf.Trending(criteria), newSeason(criteria)))),
            contents = mapOf(SubjectType.ANIME to ExploreTypeContent(shelves = mapOf(TRENDING_SHELF_ID to content, NEW_SEASON_ID to content))),
        )
    }

    private fun capturePair(fontScale: Float, prefix: String) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme(darkTheme = false) {
                    Surface {
                        Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                            ExploreContent(
                                state = state(showReal),
                                onSelectType = {},
                                onRefresh = {},
                                onTilesShown = { _, _, _ -> },
                                onSubjectClick = {},
                                onOpenSearch = { _, _, _ -> },
                                onOpenCalendar = {},
                                arrangeActions = NoArrangeActions,
                            )
                        }
                    }
                }
            }
        }
        listOf("skeleton" to false, "content" to true).forEach { (name, real) ->
            composeRule.runOnIdle { showReal = real }
            composeRule.waitForIdle()
            // 捕获真正的主题与页面根节点，避免仅保存空容器。
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            saveScreenshot(FOLDER, "$prefix-$name", bitmap)
        }
    }

    /** 一条「N 月新番」海报行。 */
    private fun newSeason(criteria: SearchCriteria) = ExploreShelf.Works(
        id = NEW_SEASON_ID,
        title = ExploreTitle.NewSeason(10),
        content = WorkShelfContent.Search(criteria),
        style = WorkShelfStyle.POSTER_ROW,
    )

    private companion object {
        const val FOLDER = "explore-skeleton"
        const val NEW_SEASON_ID = "custom:新番"
    }
}
