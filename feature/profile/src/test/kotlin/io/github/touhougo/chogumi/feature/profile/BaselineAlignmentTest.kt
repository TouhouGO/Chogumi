package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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

/** 同一行里大小两种字号的文字共用一条基线，2× 字号下也一样。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BaselineAlignmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `大数字的数与单位基线相等`() {
        setLarge { KpiRow(listOf(KpiText(value = "128", unit = "部", label = "看过")), Modifier.width(360.dp)) }
        assertEquals(baseline("128"), baseline("部"), TOLERANCE)
    }

    @Test
    fun `大数字的约等号与数基线相等`() {
        setLarge { KpiRow(listOf(KpiText(value = "128", unit = "部", label = "看过", approximate = true)), Modifier.width(360.dp)) }
        assertEquals(baseline("128"), baseline("≈"), TOLERANCE)
    }

    @Test
    fun `资料头数字行的数与名称基线相等`() {
        setLarge { ProfileNumberLine(items = listOf(ProfileNumberItem.FRIENDS(3)), onSelect = {}) }
        assertEquals("数 ${baseline("3")} 名称 ${baseline("好友")}", baseline("3"), baseline("好友"), TOLERANCE)
    }

    private fun setLarge(content: @Composable () -> Unit) {
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Column(Modifier.width(360.dp)) { content() }
                }
            }
        }
    }

    private fun baseline(value: String): Float {
        val node = composeRule.onNodeWithText(value, useUnmergedTree = true)
        return (node.getUnclippedBoundsInRoot().top + node.getAlignmentLinePosition(FirstBaseline)).value
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
