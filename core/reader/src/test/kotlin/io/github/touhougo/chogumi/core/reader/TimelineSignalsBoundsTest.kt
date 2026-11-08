package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 回复与贴贴一段，窄到连回复数都放不下时，从起点画起，不被夹到容器左边之外。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimelineSignalsBoundsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `窄到放不下回复数时它的左缘不越过容器左边`() {
        val item = TimelineItemBo(id = 1, uid = 1, user = null, cat = 5, type = 1, createdAt = 0, text = "", replies = 1234)
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Box(Modifier.width(24.dp).testTag(BOX)) { TimelineSignals(item) }
                }
            }
        }
        val box = composeRule.onNodeWithTag(BOX).getUnclippedBoundsInRoot()
        val replies = composeRule.onNode(hasContentDescription("1234", substring = true), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("回复数左缘 ${replies.left} 不在容器左缘 ${box.left} 之左", replies.left >= box.left)
    }

    private companion object {
        const val BOX = "box"
    }
}
