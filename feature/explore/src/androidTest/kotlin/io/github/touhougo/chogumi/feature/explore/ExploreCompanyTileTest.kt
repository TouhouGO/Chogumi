package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.ExploreTile
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TRENDING_SHELF_ID
import io.github.touhougo.chogumi.core.model.TileLabel
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.exploreEntriesFor
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.model.isTagBrowse
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 公司方块货架的仪器测试：
 * 1. 公司货架渲染出「按动画公司浏览」标题与全部动画公司方块（按 tag 滚到货架，不靠滑动次数碰运气）；
 * 2. 经生产接线（ExploreContent 的 onOpenSearch 回调链）点击第一个公司方块（京都动画），
 *    搜索条件与 `toCriteria` 的产物完全相等——不在测试里自己构造方块、
 *    自己重写路由映射，接线断了这条就会红。
 */
class ExploreCompanyTileTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 1)

    /** 动画货架清单（与生产同一来源），公司货架与第一个公司方块都从这里取。 */
    private val shelves = exploreShelvesFor(SubjectType.ANIME, today, defaultShelfLayout(SubjectType.ANIME))

    private val companyShelf = shelves
        .first { it.title is ExploreTitle.CompanyBrowse } as ExploreShelf.Tiles

    private val companyShelfTag = "explore_shelf_${companyShelf.id}"

    /** 公司货架内**可滚动**的 LazyRow 节点（ExploreTilesRow 自带 testTag）。 */
    private val companyRowTag = "explore_tiles_row_${companyShelf.id}"

    private fun mockSubject(id: Int, name: String) = SubjectBo(
        id = id,
        type = SubjectType.ANIME,
        name = name,
        nameCn = name,
    )

    private val companyTile = companyShelf.tiles.first()

    /** 公司方块的文字就是公司名。 */
    private val ExploreTile.name: String get() = (label as TileLabel.Word).word

    private fun setState(searchCapture: (SearchCriteria, String?, Boolean) -> Unit) {
        compose.setContent {
            ChogumiTheme {
                ExploreContent(
                    state = ExploreUiState(
                        today = FIXTURE_TODAY,
                        selectedType = SubjectType.ANIME,
                        layouts = mapOf(SubjectType.ANIME to ExploreLayout(entries = exploreEntriesFor(SubjectType.ANIME, today), shelves = shelves)),
                        contents = mapOf(
                            SubjectType.ANIME to ExploreTypeContent(
                                shelves = mapOf(TRENDING_SHELF_ID to Load.Ready((1..5).map { mockSubject(it, "热门 $it") })),
                                todayOnAir = Load.Ready((6..15).map { CalendarItemBo(mockSubject(it, "今日 $it"), watchers = 100) }),
                            ),
                        ),
                    ),
                    onSelectType = {},
                    onRefresh = {},
                    onTilesShown = { _, _, _ -> },
                    onSubjectClick = {},
                    onOpenSearch = searchCapture,
                    onOpenCalendar = {},
                    arrangeActions = NoArrangeActions,
                )
            }
        }
    }

    @Test
    fun 公司货架渲染标题与全部公司方块() {
        setState(searchCapture = { _, _, _ -> })

        // 按 tag 滚到公司货架：滑动次数在不同屏幕高度上不可靠
        compose.onNodeWithTag("explore_list_${SubjectType.ANIME.name}").performScrollToNode(hasTestTag(companyShelfTag))
        compose.waitForIdle()

        compose.onNodeWithText("按动画公司浏览").assertIsDisplayed()
        // 全部公司方块（生产货架产出，非测试自造）都要渲染出来。
        // 方块行是横向 LazyRow，屏幕只放得下前几个：对可滚动的 LazyRow 节点逐个滚到目标方块
        // 再断言可见。货架外层 Column 的 tag 不是可滚动节点，直接对它 scrollTo 会报
        // 「No node found … in scrollable container」（模拟器实跑踩过）。
        val rowNode = compose.onNodeWithTag(companyRowTag)
        for (tile in companyShelf.tiles) {
            rowNode.performScrollToNode(hasText(tile.name))
            compose.waitForIdle()
            compose.onNodeWithText(tile.name).assertIsDisplayed()
        }
        assertTrue(
            "公司货架的方块组 tag 应该存在",
            compose.onAllNodesWithTag(companyShelfTag).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun 经生产接线点击公司方块交出与toCriteria相同的搜索语义() {
        var captured: Triple<SearchCriteria, String?, Boolean>? = null
        setState(searchCapture = { criteria, title, openFilters -> captured = Triple(criteria, title, openFilters) })

        compose.onNodeWithTag("explore_list_${SubjectType.ANIME.name}").performScrollToNode(hasTestTag(companyShelfTag))
        compose.waitForIdle()

        // 点第一个公司方块的标签文字——探索页的点击链是 ExploreTileCard.onClick → onOpenSearch
        compose.onNodeWithText(companyTile.name).performClick()

        val search = captured
        assertTrue("点击公司方块必须交出搜索语义", search != null)
        val (criteria, title, openFilters) = checkNotNull(search)
        assertEquals(
            "搜索标题必须是公司名",
            companyTile.name,
            title,
        )
        assertEquals(
            "搜索条件必须与货架方块的条件完全相等（生产唯一构造点 toCriteria）",
            companyTile.criteria,
            criteria,
        )
        assertTrue(
            "公司方块的条件必须走单用户标签浏览",
            criteria.isTagBrowse(),
        )
        assertEquals(false, openFilters)
    }
}
