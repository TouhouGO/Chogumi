package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 十格评分条：点第几格是几分，再点当前分清掉；拖过去一路报分；读屏念当前分、能直接设值。 */
@RunWith(AndroidJUnit4::class)
class RatingTilesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var value by mutableIntStateOf(0)
    private val reported = mutableListOf<Int>()

    private fun setTiles() {
        composeRule.setContent {
            ChogumiTheme {
                RatingTiles(
                    value = value,
                    onValueChange = {
                        reported += it
                        value = it
                    },
                    description = "rating",
                    stateDescription = "score $value",
                    modifier = Modifier.testTag(TilesTag),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private val tiles get() = composeRule.onNodeWithTag(TilesTag)

    /** 第 [score] 格的中心（按整条宽度十等分）。 */
    private fun androidx.compose.ui.test.TouchInjectionScope.tile(score: Int) = Offset(width * (score - 0.5f) / 10, centerY)

    @Test
    fun 点第八格是八分_再点一下清掉() {
        setTiles()
        tiles.performTouchInput { click(tile(8)) }
        composeRule.waitForIdle()
        assertEquals(8, value)
        tiles.performTouchInput { click(tile(8)) }
        composeRule.waitForIdle()
        assertEquals(0, value)
    }

    @Test
    fun 拖过去一路报分_停在手指下那一格() {
        setTiles()
        tiles.performTouchInput { swipe(tile(3), tile(7), durationMillis = 400) }
        composeRule.waitForIdle()
        assertEquals(7, value)
        assertEquals("一格一格报出来：$reported", listOf(3, 4, 5, 6, 7), reported)
    }

    @Test
    fun 读屏念当前分_能直接设值() {
        setTiles()
        tiles.performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        composeRule.waitForIdle()
        assertEquals(6, value)
        tiles.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "score 6"))
    }

    private companion object {
        const val TilesTag = "rating"
    }
}
