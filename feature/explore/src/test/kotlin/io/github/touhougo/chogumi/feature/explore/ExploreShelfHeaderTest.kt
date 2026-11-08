package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.exploreEntriesFor
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 「长按任一货架标题进入整理」：动画页默认版面上的每一条货架（近期热门、各样式的作品货架、各画法的方块货架与刻度尺），
 * 标题都带着「整理货架」这个长按动作，长按一次就进一次整理。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ExploreShelfHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 15)
    private val anime = SubjectType.ANIME

    @Test
    fun 每一条货架的标题_长按都进整理() {
        val shelves = exploreShelvesFor(anime, today, defaultShelfLayout(anime))
        var entered = 0
        val actions = object : ArrangeListActions by NoArrangeActions {
            override fun enter() {
                entered++
            }
        }
        compose.setContent {
            ChogumiTheme {
                ExploreContent(
                    state = stateWith(shelves),
                    onSelectType = {},
                    onRefresh = {},
                    onTilesShown = { _, _, _ -> },
                    onSubjectClick = {},
                    onOpenSearch = { _, _, _ -> },
                    onOpenCalendar = {},
                    arrangeActions = actions,
                    guide = null,
                )
            }
        }
        val list = compose.onNodeWithTag("explore_list_${anime.name}")
        val arrangeLabel = "整理模块"

        shelves.forEachIndexed { index, shelf ->
            val (key, tag) = when (shelf) {
                is ExploreShelf.Trending -> "trending_$anime" to "explore_shelf_trending"
                is ExploreShelf.Works -> "works_${shelf.id}_$anime" to "explore_shelf_${shelf.id}"
                is ExploreShelf.Tiles -> "tiles_${shelf.id}_$anime" to "explore_shelf_${shelf.id}"
            }
            list.performScrollToKey(key)
            compose.onNode(hasAnyAncestor(hasTestTag(tag)) and longClickLabelled(arrangeLabel))
                .performSemanticsAction(SemanticsActions.OnLongClick)
            assertEquals("「${shelf.id}」的标题", index + 1, entered)
        }
    }

    private fun longClickLabelled(label: String) = SemanticsMatcher("长按动作「$label」") {
        it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label
    }

    /** 每条按词取的货架都有 6 部作品。 */
    private fun stateWith(shelves: List<ExploreShelf>): ExploreUiState {
        val works = { seed: Int -> (1..6).map { SubjectBo(id = seed + it, type = anime, name = "作品$it", nameCn = "作品$it") } }
        val layout = ExploreLayout(entries = exploreEntriesFor(anime, today), shelves = shelves)
        val content = ExploreTypeContent(
            shelves = shelves.associate { shelf -> shelf.id to Load.Ready(works(shelf.id.hashCode())) },
        )
        return ExploreUiState(today = today, layouts = mapOf(anime to layout), contents = mapOf(anime to content))
    }
}
