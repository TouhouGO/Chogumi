package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [FactChip]：字放大到 2 倍时芯片随行高长高，字不被上下裁掉。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FactChipLargeFontTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `字号从一倍到两倍时芯片随字长高且正文完整显示`() {
        composeRule.setContent {
            ChogumiTheme {
                Column {
                    listOf(1f, 2f).forEach { scale ->
                        CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = scale)) {
                            FactChip(text = "事实$scale", icon = null, modifier = Modifier.testTag("$CHIP$scale"))
                        }
                    }
                }
            }
        }
        listOf(1f, 2f).forEach { scale ->
            val chip = composeRule.onNodeWithTag("$CHIP$scale").getUnclippedBoundsInRoot()
            val text = composeRule.onNodeWithText("事实$scale")
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse("${scale}倍字号正文没有被限高：size=${layout.size}", layout.didOverflowHeight)
            assertFalse("${scale}倍字号正文没有省略", layout.isLineEllipsized(0))
            val body = text.getUnclippedBoundsInRoot()
            assertTrue("${scale}倍字号正文上下完整位于芯片内", body.top >= chip.top && body.bottom <= chip.bottom)
        }
        val normal = composeRule.onNodeWithTag("${CHIP}1.0").getUnclippedBoundsInRoot()
        val large = composeRule.onNodeWithTag("${CHIP}2.0").getUnclippedBoundsInRoot()
        assertTrue("两倍字号芯片随正文长高", large.height > normal.height)
    }

    private companion object {
        const val CHIP = "chip"
    }
}
