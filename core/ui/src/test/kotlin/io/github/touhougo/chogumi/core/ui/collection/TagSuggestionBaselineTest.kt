package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 「大家常用」的建议 chip 里，标签名和人数是两种字号，2× 字号下共用一条基线。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagSuggestionBaselineTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `标签名与人数基线相等`() {
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState())) {
                        TagsSection(tags = emptyList(), suggestions = listOf("热血" to 7), input = TextFieldState(), onChange = {})
                    }
                }
            }
        }
        assertEquals(baseline("热血"), baseline("7"), 0.5f)
    }

    private fun baseline(text: String): Float {
        val node = composeRule.onNode(hasText(text), useUnmergedTree = true)
        return (node.getUnclippedBoundsInRoot().top + node.getAlignmentLinePosition(FirstBaseline)).value
    }
}
