package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 内容网格（[ContentGrid] / [AdaptiveGridRows]）的一行：最左一项到左缘、最右一项到右缘的留白相等，都是网格的左右留白；
 * 相邻两项之间只有格距——也就是每一项铺满一格。项目若自带定宽、靠起始边，格子多出来的宽就全堆在右边，
 * 最右一列外的留白比左边大。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1000dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentGridGutterTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `海报网格里的条目海报卡铺满一格 一行左右留白相等`() {
        assertRowsAcrossWidths(GridCell.Poster) {
            ContentGrid(cell = GridCell.Poster) {
                items(ids) { id -> SubjectCardVertical(subject(id), onClick = {}, modifier = Modifier.testTag(itemTag(id))) }
            }
        }
    }

    @Test
    fun `人物网格里的人物卡铺满一格 一行左右留白相等`() {
        assertRowsAcrossWidths(GridCell.Person) {
            ContentGrid(cell = GridCell.Person) {
                items(ids) { id -> PersonGridItem(title = "人物 $id", imageUrl = null, onClick = null, modifier = Modifier.testTag(itemTag(id))) }
            }
        }
    }

    /** 非惰性网格由网格定格宽：项目自己写了定宽也被撑成一格。 */
    @Test
    fun `非惰性网格里定宽的项目也铺满一格 一行左右留白相等`() {
        assertRowsAcrossWidths(GridCell.Poster) {
            AdaptiveGridRows(items = ids, cell = GridCell.Poster) { id ->
                Box(Modifier.size(40.dp).testTag(itemTag(id)))
            }
        }
    }

    private fun assertRowsAcrossWidths(cell: GridCell, grid: @Composable () -> Unit) {
        var screen by mutableStateOf(widths.first())
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.width(screen).testTag(GRID)) { grid() }
            }
        }
        widths.forEach { width ->
            screen = width
            composeRule.waitForIdle()
            assertFirstRow(width, cell)
        }
    }

    private fun assertFirstRow(width: Dp, cell: GridCell) {
        val grid = composeRule.onNodeWithTag(GRID).getUnclippedBoundsInRoot()
        val items: List<DpRect> = ids.map { composeRule.onNodeWithTag(itemTag(it), useUnmergedTree = true).getUnclippedBoundsInRoot() }
        val row = items.takeWhile { it.top == items.first().top }
        val left = row.first().left - grid.left
        val right = grid.right - row.last().right
        // 格宽按像素分，余数逐格多 1px：两侧留白最多差 1px。
        val tolerance = 1f
        assertEquals("$width：左侧留白应为网格左右留白", GridPadding.value, left.value, tolerance)
        assertEquals("$width：右侧留白应与左侧相等（左 $left，右 $right）", left.value, right.value, tolerance)
        row.zipWithNext { a, b ->
            assertEquals("$width：相邻两项之间只有格距", cell.spacing.value, (b.left - a.right).value, tolerance)
        }
    }

    private companion object {
        const val GRID = "content_grid"
        val widths = listOf(360.dp, 380.dp, 411.dp, 448.dp, 600.dp, 800.dp)

        /** 800dp 下人物卡一行的列数也不超过它：第一行总是排满。 */
        val ids = (1..16).toList()

        fun itemTag(id: Int) = "grid_item_$id"

        fun subject(id: Int) = SubjectBo(id = id, type = SubjectType.ANIME, name = "作品 $id", nameCn = "作品 $id")
    }
}
