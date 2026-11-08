package io.github.touhougo.chogumi.shell

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.appFeatures
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.topLevelDestinationsFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 底栏上目的地的名字整个显示：最窄的验收屏宽（380dp）、英文、五个目的地时，1 倍、1.1 倍（小屏验收字号）与 2 倍字号下
 * 每个名字都写全（不裁成「Communit」），且都落在底栏里面（底栏跟着长高）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w380dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopLevelLabelFitTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val destinations = topLevelDestinationsFor(appFeatures(ExperienceMode.FULL, showPublicRatings = true, showCoverScores = true, showFriendMarks = true))

    @Test
    fun `380dp宽五个目的地时各字号下名字都写全且在底栏里`() {
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                // 系统字号一变 Activity 就重建、从头量；原地只换 LocalDensity 时按字号重新组合，效果相同
                key(fontScale) {
                    ChogumiTheme {
                        TopLevelNavigationBar(currentTab = Route.Home, destinations = destinations, isVisible = true, onSelect = {})
                    }
                }
            }
        }
        listOf(1f, 1.1f, 2f).forEach { scale ->
            fontScale = scale
            composeRule.waitForIdle()
            val bar = composeRule.onNodeWithTag("TopLevelNavigationBar").getUnclippedBoundsInRoot()
            destinations.forEach { destination ->
                val title = context.getString(destination.title)
                val label = composeRule.onNodeWithText(title, useUnmergedTree = true)
                val layout = label.layout()
                assertEquals("$scale 倍字号：「$title」写全", title.length, layout.getLineEnd(layout.lineCount - 1))
                assertFalse("$scale 倍字号：「$title」的行不被裁掉", layout.didOverflowHeight)
                val bounds = label.getUnclippedBoundsInRoot()
                assertTrue("$scale 倍字号：「$title」落在底栏里（$bounds，底栏 $bar）", bounds.bottom <= bar.bottom && bounds.top >= bar.top)
            }
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }
}
