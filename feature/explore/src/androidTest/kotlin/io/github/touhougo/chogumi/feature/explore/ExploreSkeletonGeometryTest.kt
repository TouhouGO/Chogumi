package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TRENDING_SHELF_ID
import io.github.touhougo.chogumi.core.model.WorkShelfContent
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.ui.composables.SkeletonPosterTag
import io.github.touhougo.chogumi.core.ui.testing.SkeletonAnchor
import io.github.touhougo.chogumi.core.ui.testing.assertSkeletonMatchesReal
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 探索页与搜索页的骨架与真实内容**几何同源**：内容出现时，骨架里已画出的东西不许挪位置。
 *
 * 做法同 core:ui 的 `SkeletonGeometryTest`：同一个窗口里先渲染骨架、读锚点，再翻转 state 换成真实内容、
 * 读同一批锚点，上、下、左三边逐一比较（容差 0.5dp），不一致攒到最后一次性报告。
 * 比对走全仓共用的 `io.github.touhougo.chogumi.core.ui.testing`；轮播里的空 Surface 若不撑满槽位会塌成零宽，
 * 左缘照旧、右缘贴到左缘，故本文件所有锚点右缘也一起比（[SkeletonAnchor.compareRight]）。
 * 每组都配了一条 fontScale = 2 的用例：海报卡标题的行高随字号缩放，写死 dp 的占位在 1 倍下可能碰巧对上。
 */
@RunWith(AndroidJUnit4::class)
class ExploreSkeletonGeometryTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var showReal by mutableStateOf(false)

    private fun setContent(fontScale: Float, content: @Composable () -> Unit) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme { content() }
            }
        }
        composeRule.waitForIdle()
    }

    private fun flipToReal() {
        composeRule.runOnIdle { showReal = true }
        composeRule.waitForIdle()
    }

    /** 同名节点里的第 [index] 个（懒加载容器里按组合顺序即条目顺序）。 */
    private fun node(tag: String, index: Int) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)[index]

    /** 骨架与真实的同一个锚点：[skeletonTag] / [realTag] 指向两边各自的节点。 */
    private fun anchor(name: String, skeletonTag: String, realTag: String = skeletonTag, index: Int = 0) =
        SkeletonAnchor(
            name,
            { node(skeletonTag, index) },
            { node(realTag, index) },
            compareRight = true,
        )

    private fun subject(id: Int, ratingScore: Double = 0.0) = SubjectBo(
        id = id,
        type = SubjectType.ANIME,
        name = "作品$id",
        nameCn = "作品$id",
        ratingScore = ratingScore,
    )

    // ---- 热门轮播：TrendingHeroSkeleton 对比 TrendingHeroCarousel ----

    private fun assertHeroGeometry(fontScale: Float) {
        setContent(fontScale) {
            Box(Modifier.width(400.dp)) {
                if (showReal) {
                    TrendingHeroCarousel(items = (1..8).map { subject(it) }, onSubjectClick = {}, guide = null, coverDemo = false)
                } else {
                    TrendingHeroSkeleton()
                }
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(
                anchor("热门大卡", HeroItemTagPrefix + 0),
                anchor("热门第二项", HeroItemTagPrefix + 1),
            ),
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun 热门轮播骨架与真实轮播的前两项同框() = assertHeroGeometry(fontScale = 1f)

    @Test
    fun 字体放大两倍时热门轮播骨架与真实轮播的前两项同框() = assertHeroGeometry(fontScale = 2f)

    /** 经 ExploreContent（与线上同一路径）：页面里的热门骨架大卡必须和真实大卡同框同宽，右缘一起比。 */
    private fun trendingState(real: Boolean): ExploreUiState {
        val criteria = SearchCriteria(subjectType = SubjectType.ANIME)
        val content = if (real) Load.Ready((1..8).map { subject(it) }) else Load.Pending
        return ExploreUiState(
            today = FIXTURE_TODAY,
            selectedType = SubjectType.ANIME,
            layouts = mapOf(SubjectType.ANIME to ExploreLayout(entries = emptyList(), shelves = listOf(ExploreShelf.Trending(criteria)))),
            contents = mapOf(SubjectType.ANIME to ExploreTypeContent(shelves = mapOf(TRENDING_SHELF_ID to content))),
        )
    }

    @Test
    fun 探索页热门骨架的大卡与真实大卡同框同宽() {
        setContent(1f) {
            Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                ExploreContent(
                    state = trendingState(showReal),
                    onSelectType = {},
                    onRefresh = {},
                    onTilesShown = { _, _, _ -> },
                    onSubjectClick = {},
                    onOpenSearch = { _, _, _ -> },
                    onOpenCalendar = {},
                    arrangeActions = NoArrangeActions,
                    guide = null,
                )
            }
        }
        assertSkeletonMatchesReal(
            anchors = listOf(anchor("页面热门大卡", HeroItemTagPrefix + 0)),
            flipToReal = ::flipToReal,
        )
    }

    // ---- 货架行：骨架行对比真实货架行（经 ExploreContent，货架标题行也在其中） ----

    private val shelfId = "custom:新番"

    private fun shelfState(ranked: Boolean, real: Boolean): ExploreUiState {
        val criteria = SearchCriteria(subjectType = SubjectType.ANIME)
        val shelf = ExploreShelf.Works(
            id = shelfId,
            title = ExploreTitle.NewSeason(10),
            content = WorkShelfContent.Search(criteria),
            style = if (ranked) WorkShelfStyle.CHART else WorkShelfStyle.POSTER_ROW,
        )
        val content = if (real) Load.Ready((1..8).map { subject(it, ratingScore = if (ranked) 8.6 else 0.0) }) else Load.Pending
        return ExploreUiState(
            today = FIXTURE_TODAY,
            selectedType = SubjectType.ANIME,
            layouts = mapOf(SubjectType.ANIME to ExploreLayout(entries = emptyList(), shelves = listOf(shelf))),
            contents = mapOf(SubjectType.ANIME to ExploreTypeContent(shelves = mapOf(shelfId to content))),
        )
    }

    private fun assertShelfGeometry(fontScale: Float, ranked: Boolean) {
        setContent(fontScale) {
            Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                ExploreContent(
                    state = shelfState(ranked, real = showReal),
                    onSelectType = {},
                    onRefresh = {},
                    onTilesShown = { _, _, _ -> },
                    onSubjectClick = {},
                    onOpenSearch = { _, _, _ -> },
                    onOpenCalendar = {},
                    arrangeActions = NoArrangeActions,
                    guide = null,
                )
            }
        }
        val shelfTag = "explore_shelf_$shelfId"
        assertSkeletonMatchesReal(
            anchors = listOf(
                anchor("货架整体（标题行 + 海报行，底边即整行底边）", shelfTag),
                anchor("货架第一张海报框", SkeletonPosterTag, "subject_cover_placeholder", index = 0),
                anchor("货架第二张海报框", SkeletonPosterTag, "subject_cover_placeholder", index = 1),
            ),
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun 海报货架骨架行与真实行同位置_同整行底边() = assertShelfGeometry(fontScale = 1f, ranked = false)

    @Test
    fun 字体放大两倍时海报货架骨架行与真实行同位置_同整行底边() = assertShelfGeometry(fontScale = 2f, ranked = false)

    /** 榜单样式：名次列、元信息与分数行不得让真实行与骨架行错位或高出。 */
    @Test
    fun 榜单货架骨架行与真实行同位置_同整行底边() = assertShelfGeometry(fontScale = 1f, ranked = true)

    @Test
    fun 字体放大两倍时榜单货架骨架行与真实行同位置_同整行底边() = assertShelfGeometry(fontScale = 2f, ranked = true)

    // ---- 搜索结果网格：骨架格子对比真实 SubjectCardVertical 格子 ----

    private fun assertSearchGridGeometry(fontScale: Float) {
        setContent(fontScale) {
            Box(Modifier.size(width = 400.dp, height = 800.dp)) {
                SearchContent(
                    currentYear = FIXTURE_TODAY.year,
                    state = if (showReal) {
                        SearchUiState(results = PagedList((1..12).map { subject(it) }, isLoading = false))
                    } else {
                        SearchUiState(results = PagedList(isLoading = true))
                    },
                    actions = ReducingSearchActions(),
                    onSubjectClick = {},
                    onBack = {},
                )
            }
        }
        // 第 4 格是第二行的第一格：它的顶边 = 第一行卡片高度 + 行距，卡片高度不对这里就错位。
        assertSkeletonMatchesReal(
            anchors = listOf(
                anchor("搜索第一格海报框", SkeletonPosterTag, "subject_cover_placeholder", index = 0),
                anchor("搜索第二格海报框", SkeletonPosterTag, "subject_cover_placeholder", index = 1),
                anchor("搜索第二行第一格海报框", SkeletonPosterTag, "subject_cover_placeholder", index = 3),
            ),
            flipToReal = ::flipToReal,
        )
    }

    @Test
    fun 搜索结果骨架网格与真实网格同格同宽同高() = assertSearchGridGeometry(fontScale = 1f)

    @Test
    fun 字体放大两倍时搜索结果骨架网格与真实网格同格同宽同高() = assertSearchGridGeometry(fontScale = 2f)
}
