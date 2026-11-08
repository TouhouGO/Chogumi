package io.github.touhougo.chogumi.feature.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.FixedWorkShelf
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.model.fixedWorkShelfEntry
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 整理与浏览切换时保留退场内容到动画结束，顶栏与正文一起切换。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ExploreArrangeTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun 进入和退出整理_旧内容留到转场结束() {
        val today = LocalDate.of(2026, 10, 23)
        val type = SubjectType.ANIME
        val entry = fixedWorkShelfEntry(FixedWorkShelf.RANKING)
        val shelves = exploreShelvesFor(type, today, listOf(entry))
        val works = listOf(SubjectBo(id = 1, type = type, name = "作品", nameCn = ""))
        var state by mutableStateOf(ExploreUiState(
            today = today,
            layouts = mapOf(type to ExploreLayout(emptyList(), shelves, listOf(entry))),
            contents = mapOf(type to ExploreTypeContent(shelves = shelves.associate { it.id to Load.Ready(works) })),
        ))
        compose.setContent {
            ChogumiTheme {
                ExploreContent(state, {}, {}, { _, _, _ -> }, {}, { _, _, _ -> }, {}, NoArrangeActions, guide = null)
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(arrange = state.arrange.copy(active = true)) }
        compose.waitForIdle()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag("explore_list_ANIME").assertExists()
        compose.onNodeWithTag("explore_arrange_ANIME").assertExists()
        compose.onNodeWithTag("explore_search_bar").assertExists()
        compose.onNodeWithTag("explore_arrange_done").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("explore_list_ANIME").assertDoesNotExist()
        compose.onNodeWithTag("explore_arrange_ANIME").assertExists()

        compose.runOnIdle { state = state.copy(arrange = state.arrange.copy(active = false)) }
        compose.waitForIdle()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.onNodeWithTag("explore_list_ANIME").assertExists()
        compose.onNodeWithTag("explore_arrange_ANIME").assertExists()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("explore_arrange_ANIME").assertDoesNotExist()
        compose.onNodeWithTag("explore_list_ANIME").assertExists()
    }
}
