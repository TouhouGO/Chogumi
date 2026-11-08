package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.exploreEntriesFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 固定入口行：小屏 2× 字号下标签折行显示完整，各格仍等高。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExploreEntriesLargeFontTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setLarge(fontScale: Float = 2f) {
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        ExploreEntriesRow(
                            entries = exploreEntriesFor(SubjectType.ANIME, LocalDate.of(2026, 10, 15)),
                            onOpenSearch = { _, _, _ -> },
                            onOpenCalendar = {},
                        )
                    }
                }
            }
        }
    }

    private fun assertLabelsNotCut() {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodes(hasText("每日放送"), useUnmergedTree = true).fetchSemanticsNodes().forEach {
            it.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        }
        assertEquals(1, layouts.size)
        assertFalse("入口标签被截断", layouts.single().hasVisualOverflow)
    }

    @Test
    fun 两倍字号下标签折行而不是截断_各格等高() {
        setLarge()
        assertLabelsNotCut()
        val heights = compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().map { it.size.height }
        assertEquals("入口格等高", 1, heights.toSet().size)
    }
}
