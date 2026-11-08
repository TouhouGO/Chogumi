package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.ExploreShelf
import io.github.touhougo.chogumi.core.model.ExploreTile
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TileDrawing
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.model.exploreShelvesFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 探索货架几种样式的点击与手势：封面流点侧边只转到正中、点正中才进详情；卡堆甩够了换下一张、不够弹回、点卡进顶上那部；
 * 书脊点立着的只抽出、点抽出的才进详情；刻度尺通过读屏语义换年代只取落定后的那一格。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class ExploreShelfStylesTest {

    @get:Rule
    val compose = createComposeRule()

    private val subjects = (1..6).map { SubjectBo(id = it, type = SubjectType.ANIME, name = "作品$it", nameCn = "") }
    private val clicked = mutableListOf<Int>()

    @Test
    fun 封面流_点侧边那张转到正中不进详情_点正中进详情() {
        compose.setContent { ChogumiTheme { CoverFlow(subjects, onSubjectClick = { clicked += it }) } }
        // 一开始停在第三张，两边都有封面
        compose.onNodeWithText("作品3").assertIsDisplayed()

        compose.onNodeWithContentDescription("作品4").performClick()
        compose.waitForIdle()
        assertTrue("点侧边那张不进详情", clicked.isEmpty())
        compose.onNodeWithText("作品4").assertIsDisplayed()

        compose.onNodeWithContentDescription("作品4").performClick()
        compose.waitForIdle()
        assertEquals(listOf(4), clicked)
    }

    @Test
    fun 卡堆_甩够了换下一张_不够弹回_点卡进顶上那部() {
        compose.setContent { ChogumiTheme { DeckShelf(subjects, onSubjectClick = { clicked += it }) } }
        compose.onNodeWithText("作品1").assertIsDisplayed()

        // 甩得不够（过了触摸阈值，没到甩出的距离）：弹回，顶上还是第一张
        compose.onNodeWithTag("explore_deck_pile", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(-viewConfiguration.touchSlop - 20f, 0f))
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithText("作品1").assertIsDisplayed()

        compose.onNodeWithTag("explore_deck_pile", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("作品2").assertIsDisplayed()
        compose.onNodeWithText("作品1").assertDoesNotExist()
        assertTrue("甩卡不是点击", clicked.isEmpty())

        compose.onNodeWithText("作品2").performClick()
        compose.waitForIdle()
        assertEquals(listOf(2), clicked)
    }

    @Test
    fun 书脊_点立着的只抽出_点抽出的进详情() {
        compose.setContent { ChogumiTheme { SpineRow(subjects.take(4), onSubjectClick = { clicked += it }) } }

        compose.onNodeWithContentDescription("作品2").performClick()
        compose.waitForIdle()
        assertTrue("点立着的书脊只抽出", clicked.isEmpty())

        compose.onNodeWithContentDescription("作品2").performClick()
        compose.waitForIdle()
        assertEquals(listOf(2), clicked)

        // 先前抽出的第一本收回去了：点它又只是抽出
        compose.onNodeWithContentDescription("作品1").performClick()
        compose.waitForIdle()
        assertEquals(listOf(2), clicked)
    }

    @Test
    fun 刻度尺_语义设置到另一个年代_只取落定的那一格() {
        val shelf = exploreShelvesFor(SubjectType.ANIME, LocalDate.of(2026, 10, 15), defaultShelfLayout(SubjectType.ANIME))
            .filterIsInstance<ExploreShelf.Tiles>()
            .first { it.drawing == TileDrawing.RULER }
        val shown = mutableListOf<ExploreTile>()
        compose.setContent {
            ChogumiTheme {
                DecadeRuler(
                    shelf = shelf,
                    title = "年代",
                    onArrange = {},
                    typeContent = ExploreTypeContent(),
                    onShown = { shown += it },
                    onSubjectClick = {},
                    onOpenSearch = { _, _, _ -> },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(shelf.tiles[0]), shown)

        compose.onNodeWithTag("explore_decade_ruler").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.waitForIdle()
        assertEquals("途经的那一格不取", listOf(shelf.tiles[0], shelf.tiles[2]), shown)
    }
}
