package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 聚焦引导开着时：点遮罩不收起，下面的页面摸不到（亮出来的那一块也一样），只有卡上的按钮往下走；收起之后页面照常可点。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class SpotlightGuideTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var shown by mutableStateOf(true)
    private var underClicks = 0
    private var targetClicks = 0

    private fun show() {
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                val state = rememberSpotlightGuideState()
                val step = SpotlightStep(
                    target = TARGET,
                    shape = MaterialTheme.shapes.large,
                    title = "标题",
                    text = "说明",
                    actions = listOf(SpotlightAction("知道了") { shown = false }),
                )
                Box(Modifier.fillMaxSize()) {
                    Column {
                        TextButton(onClick = { underClicks++ }, modifier = Modifier.testTag(UNDER)) { Text("下面的按钮") }
                        TextButton(
                            onClick = { targetClicks++ },
                            modifier = Modifier.size(120.dp).spotlightTarget(state, TARGET).testTag(TARGET),
                        ) { Text("亮出来的") }
                    }
                    SpotlightScrim(state, step.takeIf { shown })
                    SpotlightGuide(state, step.takeIf { shown })
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `点遮罩不收起_下面摸不到_只有卡上的按钮收起`() {
        show()
        composeRule.onNodeWithTag(UNDER).performTouchInput { click() }
        composeRule.onNodeWithTag(TARGET).performTouchInput { click() }
        composeRule.onRoot().performTouchInput { click(bottomRight - Offset(10f, 10f)) }
        composeRule.waitForIdle()
        assertEquals("下面的页面摸不到", 0, underClicks)
        assertEquals("亮出来的那一块也摸不到", 0, targetClicks)
        composeRule.onNodeWithText("标题").assertIsDisplayed()

        composeRule.onNodeWithText("知道了").performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("标题").assertDoesNotExist()
        composeRule.onNodeWithTag(UNDER).performTouchInput { click() }
        composeRule.waitForIdle()
        assertEquals("收起之后照常可点", 1, underClicks)
    }

    private companion object {
        const val TARGET = "target"
        const val UNDER = "under"
    }
}
