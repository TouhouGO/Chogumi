package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TileDrawing
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 合集方块（按题材浏览）里的小封面整叠左右居中：左右留白相等，不偏到一边。
 * 每张封面绕自己的中心转，转了中心不动，所以按中心量：最左、最右两张中心的中点就是方块的正中。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w600dp-h640dp")
class ExploreStackTileLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val subjects = (1..6).map { SubjectBo(id = it, type = SubjectType.ANIME, name = "作品$it", nameCn = "") }

    @Test
    fun 三张两张一张封面都在方块里左右居中() {
        val shelf = exploreShelvesFor(SubjectType.ANIME, LocalDate.of(2026, 10, 15), defaultShelfLayout(SubjectType.ANIME))
            .filterIsInstance<ExploreShelf.Tiles>()
            .first { it.drawing == TileDrawing.STACK }
        val counts = listOf(3, 2, 1)
        // 前三块各有自己的 3、2、1 部（一行里不重复），其余几块没有作品：整行都到了才画
        val slices = listOf(subjects.subList(0, 3), subjects.subList(3, 5), subjects.subList(5, 6))
        val works = shelf.tiles.mapIndexed { index, tile ->
            tileWorksKey(shelf.id, tile) to Load.Ready(slices.getOrElse(index) { emptyList() })
        }.toMap()
        compose.setContent {
            ChogumiTheme {
                TilesShelf(
                    shelf = shelf,
                    typeContent = ExploreTypeContent(tileWorks = works),
                    onShown = {},
                    onSubjectClick = {},
                    onOpenSearch = { _, _, _ -> },
                    onArrange = {},
                )
            }
        }
        compose.waitForIdle()

        val row = hasTestTag("explore_tiles_row_${shelf.id}")
        val tiles = compose.onAllNodes(hasClickAction() and hasAnyAncestor(row), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .sortedBy { it.left }
        val covers = compose.onAllNodesWithTag(STACK_COVER_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
        val density = compose.density.density

        counts.forEachIndexed { index, count ->
            val tile = tiles[index]
            val inTile = covers.filter { it.center.x in tile.left..tile.right }.map { it.center.x }
            assertEquals("第 ${index + 1} 块的封面张数", count, inTile.size)
            val stackCenter = (inTile.min() + inTile.max()) / 2
            assertEquals(
                "第 ${index + 1} 块（$count 张）的封面整叠在方块正中",
                tile.center.x / density,
                stackCenter / density,
                0.5f,
            )
        }
    }
}
