package io.github.touhougo.chogumi.feature.explore

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.exploreEntriesFor
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkState
import io.github.touhougo.chogumi.core.ui.testing.CompositionWork
import io.github.touhougo.chogumi.core.ui.testing.CountingCompositions
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 探索页一拍只改了与货架无关的字段（刷新指示、长按标记）时，货架列表不跟着重跑：
 * 这一拍重跑的作用域数与页上有几条货架无关。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ExploreRecompositionTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 15)

    @Test
    fun 与货架无关的一拍_重跑的作用域数与货架多少无关() {
        val work = CompositionWork()
        var state by mutableStateOf(stateWith(shelves = false))
        compose.setContent {
            CountingCompositions(work) {
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
                    )
                }
            }
        }

        fun beat(change: (ExploreUiState) -> ExploreUiState): Int {
            compose.waitForIdle()
            work.reset()
            compose.runOnIdle { state = change(state) }
            compose.waitForIdle()
            return work.entered
        }

        val marksBare = beat { it.copy(posterMarks = PosterMarkState(today = today)) }
        val refreshBare = beat { it.copy(refreshing = true) } + beat { it.copy(refreshing = false) }

        compose.runOnIdle { state = stateWith(shelves = true) }
        val marksFull = beat { it.copy(posterMarks = PosterMarkState(today = today.plusDays(1))) }
        val refreshFull = beat { it.copy(refreshing = true) } + beat { it.copy(refreshing = false) }

        assertEquals("长按标记变了", marksBare, marksFull)
        assertEquals("刷新指示开、关", refreshBare, refreshFull)
    }

    /** 动画页：入口行，[shelves] 为真时再加上今天的全部货架、每条 6 部。 */
    private fun stateWith(shelves: Boolean): ExploreUiState {
        val layout = ExploreLayout(
            entries = exploreEntriesFor(SubjectType.ANIME, today),
            shelves = if (shelves) exploreShelvesFor(SubjectType.ANIME, today, defaultShelfLayout(SubjectType.ANIME)) else emptyList(),
        )
        val content = ExploreTypeContent(
            shelves = layout.shelves.associate { shelf -> shelf.id to Load.Ready((1..6).map { subject(shelf.id.hashCode() + it) }) },
        )
        return ExploreUiState(
            today = today,
            layouts = mapOf(SubjectType.ANIME to layout),
            contents = mapOf(SubjectType.ANIME to content),
        )
    }

    private fun subject(id: Int) = SubjectBo(id = id, type = SubjectType.ANIME, name = "作品$id", nameCn = "作品$id")
}
