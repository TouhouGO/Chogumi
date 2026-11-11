package io.github.touhougo.chogumi.core.ui.chart

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 图上的每个标记也能被读屏用到：每个标记是自己的一个节点，读出气泡文字，点击动作出气泡。
 * 标记再多也不塞进图这一个节点（一个节点的自定义动作有上限，超过时读屏取节点信息就崩）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class TapChartSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `每个标记是自己的读屏节点_点击动作出气泡`() {
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.width(200.dp).height(100.dp)) {
                    TapChart("示意图") {
                        ChartLayout(
                            listOf(
                                ChartMark(Rect(0f, 0f, 50f, 50f), "甲 3 人") {},
                                ChartMark(Rect(60f, 0f, 110f, 50f), "乙 5 人") {},
                            ),
                        )
                    }
                }
            }
        }

        val chart = composeRule.onNode(hasContentDescription("示意图")).fetchSemanticsNode()
        assertEquals("示意图", chart.config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        val first = composeRule.onNodeWithContentDescription("甲 3 人").fetchSemanticsNode()
        val second = composeRule.onNodeWithContentDescription("乙 5 人").fetchSemanticsNode()
        assertEquals("节点盖在算作点中它的范围上", Rect(0f, 0f, 50f, 50f), first.boundsInRoot.translate(-chart.boundsInRoot.topLeft))
        assertEquals(Rect(60f, 0f, 110f, 50f), second.boundsInRoot.translate(-chart.boundsInRoot.topLeft))

        composeRule.runOnUiThread { first.config[SemanticsActions.OnClick].action!!() }
        composeRule.onNodeWithText("甲 3 人").assertExists()
    }

    @Test
    fun `标记上百个时读屏取图与标记的节点信息不崩`() {
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.width(200.dp).height(100.dp)) {
                    TapChart("百格图") {
                        ChartLayout((0 until 100).map { i -> ChartMark(Rect(i.toFloat(), 0f, i + 1f, 10f), "第 $i 格") {} })
                    }
                }
            }
        }
        val chart = composeRule.onNode(hasContentDescription("百格图")).fetchSemanticsNode()
        val provider = (chart.root as ViewRootForTest).view.accessibilityNodeProvider
        val infos = mutableListOf<AccessibilityNodeInfo?>()
        composeRule.runOnUiThread { infos += provider.createAccessibilityNodeInfo(chart.id) }
        val mark = composeRule.onNodeWithContentDescription("第 99 格").fetchSemanticsNode()
        composeRule.runOnUiThread { infos += provider.createAccessibilityNodeInfo(mark.id) }
        assertEquals(listOf("百格图", "第 99 格"), infos.map { it?.contentDescription?.toString() })
    }
}
