package io.github.touhougo.chogumi.feature.home

import io.github.touhougo.chogumi.core.foundation.Load
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.HomeProgressItemBo
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.ui.composables.SelectableChipCheckTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * 「继续」区类型 chip：清单由 ViewModel 从实际条目派生，界面只渲染给定的 [HomeUiState.continueChips]。
 */
@RunWith(AndroidJUnit4::class)
class ContinueFilterChipsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun continueItem(id: Int, type: SubjectType) = HomeContinueItem(
        card = HomeProgressCard(
            subject = SubjectBo(id = id, type = type, name = "作品$id", nameCn = "作品$id"),
            epButtonState = EpButtonState.Empty,
            progressSubject = ProgressSubject(subjectId = id, subjectType = type, episodes = emptyList()),
            progressCount = ProgressCount.Known(BookUnit.EP, 0, 12),
        ),
        collection = UserCollectionBo(
            subjectId = id,
            subjectType = type,
            type = CollectionType.DOING,
            subject = SubjectBo(id = id, type = type, name = "作品$id", nameCn = "作品$id"),
        ),
    )

    private fun state(chips: List<SubjectType>, filter: SubjectType? = null) = HomeUiState(
        today = HomeSkeletonFixtures.today,
        progress = Load.Ready(Unit),
        continueItems = listOf(continueItem(1, SubjectType.BOOK), continueItem(2, SubjectType.REAL)),
        continueChips = chips,
        continueFilter = filter,
    )

    private fun setContent(
        state: HomeUiState,
        onOpenProgress: (SubjectType?) -> Unit = {},
        onContinueFilterChange: (SubjectType?) -> Unit = {},
    ) {
        composeRule.setContent {
            TodayHomeContent(
                state = state,
                onRefresh = {},
                topPadding = 0.dp,
                onSubjectClick = {},
                onEdit = {},
                onOpenProgress = onOpenProgress,
                onContinueFilterChange = onContinueFilterChange,
                onEpButtonTap = {},
                onMarkFinished = {},
                onFeedClick = {},
            )
        }
    }

    @Test
    fun 两个以上分类时按派生清单渲染chip行_清单外的分类一个都不出现() {
        var picked: SubjectType? = SubjectType.GAME
        var pickedCalled = false
        setContent(
            state = state(chips = listOf(SubjectType.BOOK, SubjectType.REAL)),
            onContinueFilterChange = { picked = it; pickedCalled = true },
        )

        composeRule.onNodeWithTag("home_continue_filter_chips").assertExists()
        composeRule.onNodeWithTag("home_continue_filter_chip_ALL").assertExists()
        composeRule.onNodeWithTag("home_continue_filter_chip_BOOK").assertExists()
        composeRule.onNodeWithTag("home_continue_filter_chip_REAL").assertExists()
        // 派生清单里没有的分类（被待看/时间表吸走），界面上不出现
        composeRule.onNodeWithTag("home_continue_filter_chip_ANIME").assertDoesNotExist()
        composeRule.onNodeWithTag("home_continue_filter_chip_GAME").assertDoesNotExist()
        composeRule.onNodeWithTag("home_continue_filter_chip_MUSIC").assertDoesNotExist()

        composeRule.onNodeWithTag("home_continue_filter_chip_BOOK").performClick()
        composeRule.waitForIdle()
        assertEquals(true, pickedCalled)
        assertEquals(SubjectType.BOOK, picked)
    }

    /** 某个 chip 内的勾选图标节点数（图标的语义不并入 chip，需要读未合并的语义树）。 */
    private fun checkIconCount(chipTag: String) = composeRule.onAllNodes(
        hasTestTag(SelectableChipCheckTag) and hasAnyAncestor(hasTestTag(chipTag)),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    @Test
    fun 选中的chip带勾选图标_未选中的不带() {
        setContent(state = state(chips = listOf(SubjectType.BOOK, SubjectType.REAL), filter = SubjectType.BOOK))

        composeRule.onNodeWithTag("home_continue_filter_chip_BOOK").assertIsSelected()
        assertEquals(1, checkIconCount("home_continue_filter_chip_BOOK"))
        assertEquals(0, checkIconCount("home_continue_filter_chip_ALL"))
        assertEquals(0, checkIconCount("home_continue_filter_chip_REAL"))
    }

    @Test
    fun 派生chip清单为空时整行不渲染_继续列表仍展示() {
        setContent(state = state(chips = emptyList()).copy(continueItems = listOf(continueItem(1, SubjectType.BOOK))))

        composeRule.onNodeWithTag("home_continue_filter_chips").assertDoesNotExist()
        // 区块本身仍在（有继续条目，区块头与「全部」入口照常）
        composeRule.onNodeWithTag("section_header_trailing").assertExists()
    }

    @Test
    fun 退回全部时全部chip选中_区块头的全部入口拿到实际生效过滤() {
        var opened: SubjectType? = SubjectType.GAME
        var openedCalled = false
        setContent(
            state = state(chips = listOf(SubjectType.BOOK, SubjectType.REAL), filter = null),
            onOpenProgress = { opened = it; openedCalled = true },
        )

        composeRule.onNodeWithTag("home_continue_filter_chip_ALL").assertIsSelected()
        composeRule.onNodeWithTag("section_header_trailing").performClick()
        composeRule.waitForIdle()
        assertEquals(true, openedCalled)
        assertNull(opened)
    }

    @Test
    fun 候选被顶部全覆盖时收成一行查看全部入口_点击带全部类型() {
        var opened: SubjectType? = SubjectType.GAME
        var openedCalled = false
        val item = HomeProgressItemBo(
            subject = SubjectBo(id = 9, type = SubjectType.ANIME, name = "待看动画", nameCn = "待看动画"),
            eps = listOf(EpisodeBo(id = 901, subjectId = 9, type = 0, sort = 1.0, name = "第一集", nameCn = "", airdate = "2026-09-29")),
            updatedAt = 0,
        )
        val sections = sectionsOf(
            prepareSectionCandidates(
                listOf(item), listOf(UserCollectionBo(subjectId = 9, subjectType = SubjectType.ANIME, type = CollectionType.DOING)),
                emptyMap(), LocalDate.of(2026, 9, 29), broadcastStarts = emptyMap(),
            ), null,
        )
        setContent(
            // 经真实分流生成顶部与收成状态，不能手造「顶部为空但全被顶部覆盖」的矛盾输入。
            state = HomeUiState(
                today = HomeSkeletonFixtures.today,
                progress = Load.Ready(Unit), toWatch = sections.toWatch, scheduleGroups = sections.scheduleGroups,
                continueItems = sections.continueItems, continueChips = sections.continueChips,
                continueCollapsed = sections.continueCollapsed,
            ),
            onOpenProgress = { opened = it; openedCalled = true },
        )

        composeRule.onNodeWithTag("home_continue_filter_chips").assertDoesNotExist()
        composeRule.onNodeWithTag("home_continue_filter_chip_ALL").assertDoesNotExist()
        composeRule.onNodeWithTag("section_header_arrow").performClick()
        composeRule.waitForIdle()
        assertEquals(true, openedCalled)
        assertNull(opened)
    }
}
