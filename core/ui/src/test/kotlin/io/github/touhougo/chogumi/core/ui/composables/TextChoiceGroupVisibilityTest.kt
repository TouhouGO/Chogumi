package io.github.touhougo.chogumi.core.ui.composables

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 文字版选项增删必须继续由 [ConnectedButtonGroup] 的状态动画负责：退场中的格先禁用并按宽度收放，
 * 全部动画完成后才移出组合；普通邻居仍在同一个按钮组里自然重新分配宽度。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextChoiceGroupVisibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    @Before
    fun enableAnimations() {
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @After
    fun restoreSystemAnimations() {
        compose.mainClock.autoAdvance = true
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test
    fun `可用项隐藏时由原按钮组收放并在中途禁用`() {
        val labels = listOf("甲", "乙", "丙")
        var hideMiddle by mutableStateOf(false)
        fun itemTag(label: String) = "choice-visible-$label"

        compose.setContent {
            ChogumiTheme {
                Box(Modifier.width(360.dp)) {
                    ConnectedChoiceGroup(
                        options = labels,
                        selected = "甲",
                        label = { it },
                        onSelect = {},
                        visible = { option -> option != "乙" || !hideMiddle },
                        modifier = Modifier.testTag(GROUP),
                        itemModifier = { Modifier.testTag(itemTag(it)) },
                    )
                }
            }
        }
        compose.waitForIdle()

        val restingWidth = compose.onNodeWithTag(itemTag("乙"), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .width
            .value
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { hideMiddle = true }
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

        val exiting = compose.onNodeWithTag(itemTag("乙"), useUnmergedTree = true)
            .assertExists()
            .assertIsNotEnabled()
        val exitingWidth = exiting.getUnclippedBoundsInRoot().width.value
        assertTrue(
            "退场项应在中间帧保留并按宽度收放：rest=$restingWidth mid=$exitingWidth",
            exitingWidth > 0.5f && exitingWidth < restingWidth - 0.5f,
        )

        compose.onNode(
            hasTestTag(itemTag("丙")) and hasAnyAncestor(hasTestTag(GROUP)),
            useUnmergedTree = true,
        ).assertExists().assertIsDisplayed().assertIsEnabled()

        compose.mainClock.advanceTimeBy(1_000)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag(itemTag("乙"), useUnmergedTree = true).assertDoesNotExist()
    }

    private companion object {
        const val GROUP = "choice-visibility-group"
    }
}
