package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 文字选择组在空间不足时的可访问回退：当前项的文字始终可见，其余尾部项仍从「更多」进入；
 * 通知标题行在 2× 字号放不下时先让动作换到第二行，让选择组拿到整行宽度。
 */
@RunWith(AndroidJUnit4::class)
class ConnectedChoiceMinimumTest {

    @get:Rule
    val compose = createComposeRule()

    private val moreLabel: String
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.choice_group_more)

    @Test
    fun `448dp普通字号保持动作与两个页签同一行`() {
        setHeader(width = 448, fontScale = 1f, labels = listOf("Notifications", "Messages"))

        compose.onNodeWithText("Notifications").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Messages").assertIsDisplayed()
        assertSameLine("Notifications")
    }

    @Test
    fun `380dp字号1点1时动作先换行并保留两个页签`() {
        setHeader(width = 380, fontScale = 1.1f, labels = listOf("Notifications", "Messages"))

        compose.onNodeWithText("Notifications").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Messages").assertIsDisplayed()
        assertActionBelowChoice("Notifications")
    }

    @Test
    fun `英文两倍字号时动作先移到第二行且当前页文字留在第一行`() {
        setHeader(width = 380, fontScale = 2f, labels = listOf("Notifications", "Messages"))

        compose.onNodeWithText("Notifications").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Messages").assertIsDisplayed()
        assertActionBelowChoice("Notifications")
    }

    @Test
    fun `中文两倍字号时动作移到第二行且两个短页名都可读`() {
        setHeader(width = 380, fontScale = 2f, labels = listOf("通知", "短信"))

        compose.onNodeWithText("通知").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("短信").assertIsDisplayed()
        assertActionBelowChoice("通知")
    }

    @Test
    fun `末尾项已选中时显示它而不是让更多格代替选中态`() {
        setChoice(
            width = 220,
            fontScale = 2f,
            labels = listOf("Administration", "Configuration", "Preferences", "Selected"),
            selected = { "Selected" },
        )

        compose.onNodeWithText("Selected").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription(moreLabel).assertIsDisplayed().assertIsNotSelected()
    }

    @Test
    fun 任意位置选中时该文字都留在组里() {
        val labels = listOf("Administration", "Configuration", "Preferences", "Selected")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ChogumiTheme {
                    Column {
                        labels.indices.forEach { selectedIndex ->
                            Box(Modifier.width(220.dp)) {
                                ConnectedChoiceGroup(
                                    options = labels,
                                    selected = labels[selectedIndex],
                                    label = { it },
                                    onSelect = {},
                                    itemModifier = { option ->
                                        Modifier.testTag("choice-$selectedIndex-${labels.indexOf(option)}")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        labels.indices.forEach { selectedIndex ->
            compose.onNodeWithTag("choice-$selectedIndex-$selectedIndex", useUnmergedTree = true)
                .assertIsDisplayed()
                .assertIsSelected()
        }
    }

    @Test
    fun `从更多菜单选中末尾项后可见文字随选中项切换`() {
        var selected by mutableStateOf("Administration")
        setChoice(
            width = 220,
            fontScale = 2f,
            labels = listOf("Administration", "Configuration", "Preferences", "Selected"),
            selected = { selected },
            onSelect = { selected = it },
        )

        compose.onNodeWithContentDescription(moreLabel).performClick()
        compose.onNode(hasText("Selected") and hasClickAction()).performClick()
        compose.waitForIdle()

        assertEquals("Selected", selected)
        compose.onNodeWithText("Selected").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription(moreLabel).assertIsDisplayed().assertIsNotSelected()
    }

    @Test
    fun `窄组仍完整显示当前项的长文字且不裁切`() {
        val selected = "Selected notification"
        setChoice(
            width = 300,
            fontScale = 2f,
            labels = listOf("Administration", "Configuration", selected),
            selected = { selected },
        )

        val text = compose.onNodeWithText(selected, useUnmergedTree = true)
        text.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        text.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertEquals(selected.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(
            "当前项文字被裁到布局外：lineRight=${layout.getLineRight(layout.lineCount - 1)} width=${layout.size.width}",
            layout.getLineRight(layout.lineCount - 1) <= layout.size.width + 0.5f,
        )
    }

    private fun setHeader(width: Int, fontScale: Float, labels: List<String>) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(width.dp).testTag(HEADER)) {
                        ChoiceSheetTitleRow(
                            options = labels,
                            selected = labels.first(),
                            label = { it },
                            onSelect = {},
                            badge = { if (it == labels.first()) 1 else 0 },
                            trailingText = "Mark all as read",
                            trailingEnabled = true,
                            onTrailing = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun setChoice(
        width: Int,
        fontScale: Float,
        labels: List<String>,
        selected: () -> String,
        onSelect: (String) -> Unit = {},
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChogumiTheme {
                    Box(Modifier.width(width.dp)) {
                        ConnectedChoiceGroup(
                            options = labels,
                            selected = selected(),
                            label = { it },
                            onSelect = onSelect,
                            modifier = Modifier.testTag(CHOICE),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertSameLine(selectedLabel: String) {
        val choice = selectedLabelBounds(selectedLabel)
        val action = actionBounds()
        assertTrue(
            "放得下时页签与动作应保持同一行：choice=$choice action=$action",
            action.top < choice.bottom && action.bottom > choice.top,
        )
    }

    private fun assertActionBelowChoice(selectedLabel: String) {
        val choice = selectedLabelBounds(selectedLabel)
        val action = actionBounds()
        val header = compose.onNodeWithTag(HEADER, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "动作应先换到选择组下方并靠右：choice=$choice action=$action",
            action.top >= choice.bottom - 0.5.dp && header.right - action.right <= 17.dp,
        )
    }

    private fun selectedLabelBounds(label: String) =
        compose.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun actionBounds() =
        compose.onNode(hasText("Mark all as read") and hasClickAction()).getUnclippedBoundsInRoot()

    private companion object {
        const val CHOICE = "choice"
        const val HEADER = "choice-header"
    }
}
