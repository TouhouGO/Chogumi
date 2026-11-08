package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 数字行在小屏、大字号、英文标签加四位数下：三项一个不丢，都在行宽之内，折行后每一项仍是 40dp 以上的点按区。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "en-w380dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileNumberLineLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `380dp两倍字号英文四位数 三项都在行宽内`() {
        val items = listOf(
            ProfileNumberItem.FRIENDS(1234),
            ProfileNumberItem.BLOGS(5678),
            ProfileNumberItem.INDEXES(IndexListCounts(created = 4321, collected = 8765)),
        )
        compose.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Box(Modifier.width(380.dp).testTag("line")) { ProfileNumberLine(items = items, onSelect = {}) }
                }
            }
        }
        val line = compose.onNodeWithTag("line").getUnclippedBoundsInRoot()
        val slots = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        assertEquals("三项都在", 3, slots)
        (0 until slots).forEach { index ->
            val bounds = compose.onAllNodes(hasClickAction())[index].getUnclippedBoundsInRoot()
            assertTrue("第 ${index + 1} 项右缘 ${bounds.right} 越出行宽 ${line.right}", bounds.right <= line.right)
            assertTrue("第 ${index + 1} 项高 ${bounds.height} 不足 40dp", bounds.height >= 40.dp)
        }
    }
}
