package io.github.touhougo.chogumi.feature.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CalendarItemBo
import io.github.touhougo.chogumi.core.model.FixedWorkShelf
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TRENDING_SHELF_ID
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.exploreEntriesFor
import io.github.touhougo.chogumi.core.model.fixedWorkShelfEntry
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 探索页界面契约测试：搜索框随列表内容收起（往上滚浮现、往下滚收起），
 * 分类 Tab 紧贴其下、收起后固定在顶部；初始顺序是 搜索框 → 分类 Tab → 入口行。
 */
class ExploreContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 1)

    private fun mockSubject(id: Int, name: String) = SubjectBo(
        id = id,
        type = SubjectType.ANIME,
        name = name,
        nameCn = name,
    )

    private fun setExploreContent(state: ExploreUiState) {
        compose.setContent {
            ChogumiTheme {
                ExploreContent(
                    state = state,
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
    }

    @Test
    fun 初始状态_类型Tab与搜索框与入口行均可见() {
        val state = ExploreUiState(
            today = FIXTURE_TODAY,
            selectedType = SubjectType.ANIME,
            layouts = mapOf(SubjectType.ANIME to ExploreLayout(entries = exploreEntriesFor(SubjectType.ANIME, today), shelves = emptyList())),
        )
        setExploreContent(state)

        compose.onNodeWithTag("explore_type_tabs").assertIsDisplayed()
        compose.onNodeWithTag("explore_search_bar").assertIsDisplayed()
        compose.onNodeWithTag("explore_entries_row").assertIsDisplayed()
    }

    @Test
    fun 顶部三件套的上下顺序是_搜索框_类型Tab_入口行() {
        val state = ExploreUiState(
            today = FIXTURE_TODAY,
            selectedType = SubjectType.ANIME,
            layouts = mapOf(SubjectType.ANIME to ExploreLayout(entries = exploreEntriesFor(SubjectType.ANIME, today), shelves = emptyList())),
        )
        setExploreContent(state)

        val searchBottom = compose.onNodeWithTag("explore_search_bar").getUnclippedBoundsInRoot().bottom
        val tabsBounds = compose.onNodeWithTag("explore_type_tabs").getUnclippedBoundsInRoot()
        val entriesTop = compose.onNodeWithTag("explore_entries_row").getUnclippedBoundsInRoot().top

        assertTrue("搜索框应排在类型 Tab 上方", searchBottom <= tabsBounds.top)
        assertTrue("类型 Tab 应排在入口行上方", tabsBounds.bottom <= entriesTop)
    }

    /**
     * 左右翻页换分类；在货架上横滑动的只是货架，滑到头也不顺势翻页；在货架以外（入口行）横滑才换分类。
     */
    @Test
    fun 在货架上横滑不换分类_在入口行横滑才换() {
        var selected by mutableStateOf(SubjectType.ANIME)
        val selections = mutableListOf<SubjectType>()
        val base = ExploreUiState(
            today = FIXTURE_TODAY,
            layouts = ExploreLayout.forDay(today, stored = emptyMap()),
            contents = mapOf(
                SubjectType.ANIME to ExploreTypeContent(
                    todayOnAir = Load.Ready((1..12).map { CalendarItemBo(mockSubject(it, "今日 $it"), watchers = 100) }),
                ),
            ),
        )
        compose.setContent {
            ChogumiTheme {
                ExploreContent(
                    state = base.copy(selectedType = selected),
                    onSelectType = { selections += it; selected = it },
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
        compose.waitForIdle()

        // 今日放送那一排海报：连滑几次，先滑到头，之后剩下的滑动也不能翻页
        val todayOnAirId = fixedWorkShelfEntry(FixedWorkShelf.TODAY_ON_AIR).id
        repeat(5) {
            compose.onNodeWithTag("explore_shelf_$todayOnAirId").performTouchInput {
                swipeLeft(startX = right - 8f, endX = left + 8f, durationMillis = 200)
            }
            compose.waitForIdle()
        }
        assertTrue("在货架上横滑不换分类：$selections", selections.isEmpty())

        compose.onNodeWithTag("explore_entries_row").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(SubjectType.BOOK), selections)
    }

    @Test
    fun 滚动后_搜索框收起而类型Tab固定在顶部() {
        val state = ExploreUiState(
            today = FIXTURE_TODAY,
            selectedType = SubjectType.ANIME,
            layouts = mapOf(SubjectType.ANIME to ExploreLayout(entries = exploreEntriesFor(SubjectType.ANIME, today), shelves = exploreShelvesFor(SubjectType.ANIME, today, defaultShelfLayout(SubjectType.ANIME)))),
            contents = mapOf(
                SubjectType.ANIME to ExploreTypeContent(
                    shelves = mapOf(TRENDING_SHELF_ID to Load.Ready((1..5).map { mockSubject(it, "热门 $it") })),
                    todayOnAir = Load.Ready((6..15).map { CalendarItemBo(mockSubject(it, "今日 $it"), watchers = 100) }),
                ),
            ),
        )
        setExploreContent(state)

        val tabsTopBefore = compose.onNodeWithTag("explore_type_tabs").getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("explore_search_bar").assertIsDisplayed()

        fun swipeList() {
            compose.onNodeWithTag("explore_list_${SubjectType.ANIME.name}").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        fun tabsTop() = compose.onNodeWithTag("explore_type_tabs").getUnclippedBoundsInRoot().top

        // 上滑：搜索框收起，分类 Tab 随之顶上去（槽位高度归零，不是只做视觉平移）。
        swipeList()
        compose.onNodeWithTag("explore_search_bar").assertIsNotDisplayed()
        val tabsTopCollapsed = tabsTop()
        assertTrue("搜索框收起后类型 Tab 应上移（前=$tabsTopBefore 后=$tabsTopCollapsed）", tabsTopCollapsed < tabsTopBefore)

        // 继续上滑：列表照常滚动，分类 Tab 固定在顶部不再移动。
        repeat(2) { swipeList() }
        compose.onNodeWithTag("explore_type_tabs").assertIsDisplayed()
        assertTrue(
            "类型 Tab 应固定在顶部（收起时=$tabsTopCollapsed 再滚后=${tabsTop()}）",
            kotlin.math.abs(tabsTop().value - tabsTopCollapsed.value) < 1f,
        )
    }
}
