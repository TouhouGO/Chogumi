package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 外部增删和保存顺序后，已有行的拖动与读屏操作使用当前整份列表。 */
@RunWith(AndroidJUnit4::class)
class ReorderableListTest {
    @get:Rule val compose = createComposeRule()
    private var rows by mutableStateOf(listOf("a", "b", "c"))
    private val moves = mutableListOf<List<String>>()
    private val removed = mutableListOf<String>()

    private fun setList() {
        compose.setContent {
            ChogumiTheme {
                ReorderableList(items = rows, key = { it }, onMove = { moves += it; rows = it }, onRemove = { removed += it; rows = rows - it }) { item ->
                    SegmentedItem(
                        index = segmentIndex,
                        count = segmentCount,
                        onClick = {},
                        trailingContent = { Box(Modifier.size(48.dp).dragHandle().testTag("handle_$item")) },
                        modifier = Modifier.testTag("row_$item"),
                    ) { Text(item) }
                }
            }
        }
    }

    @Test
    fun 添加行后_原首行可以上移到新增行之前() {
        setList()
        compose.runOnIdle { rows = listOf("d", "a", "b", "c") }
        val actionLabel = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getString(io.github.touhougo.chogumi.core.res.R.string.reorder_move_up)
        val actions = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasAnyDescendant(hasTestTag("row_a")),
        ).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == actionLabel }.action() }
        compose.runOnIdle { assertEquals(listOf(listOf("a", "d", "b", "c")), moves) }
    }

    @Test
    fun 保存顺序后继续拖动_其他行在松手前让位() {
        setList()
        compose.runOnIdle { rows = listOf("b", "a", "c") }
        val displaced = compose.onNodeWithTag("row_b")
        val before = displaced.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("handle_a", useUnmergedTree = true)
        handle.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, -viewConfiguration.touchSlop - 1f))
            moveBy(Offset(0f, -before.height))
        }
        compose.waitForIdle()
        val during = displaced.fetchSemanticsNode().boundsInRoot
        assertTrue("手指未松开时，另一行已经让出位置", during.top > before.top + before.height / 2)
        assertTrue("松手才保存", moves.isEmpty())
        handle.performTouchInput { up() }
        compose.runOnIdle { assertEquals(listOf(listOf("a", "b", "c")), moves) }
    }

    @Test
    fun 左滑只露出删除按钮_点击才移除() {
        setList()
        val row = compose.onNodeWithTag("row_a")
        val closedRight = row.fetchSemanticsNode().boundsInRoot.right
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("滑动不能删除", removed.isEmpty())
            assertEquals(listOf("a", "b", "c"), rows)
        }
        assertTrue("条目停在滑开的位置", row.fetchSemanticsNode().boundsInRoot.right < closedRight)
        val label = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getString(io.github.touhougo.chogumi.core.res.R.string.reorder_remove)
        compose.onNodeWithContentDescription(label).performClick()
        compose.runOnIdle {
            assertEquals(listOf("a"), removed)
            assertEquals(listOf("b", "c"), rows)
        }
    }

    @Test
    fun 滑开后往回滑可收起_不移除条目() {
        setList()
        val row = compose.onNodeWithTag("row_a")
        val closedRight = row.fetchSemanticsNode().boundsInRoot.right
        row.performTouchInput { swipeLeft() }
        compose.waitForIdle()
        row.performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(closedRight, row.fetchSemanticsNode().boundsInRoot.right, 1f)
        compose.runOnIdle {
            assertTrue(removed.isEmpty())
            assertEquals(listOf("a", "b", "c"), rows)
        }
    }

    @Test
    fun 把手未长按就上下滑动_不会排序() {
        setList()
        val height = compose.onNodeWithTag("row_a").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("handle_b", useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop - 1f))
            moveBy(Offset(0f, -height))
            up()
        }
        compose.runOnIdle {
            assertTrue("直接上下滑不能触发排序", moves.isEmpty())
            assertEquals(listOf("a", "b", "c"), rows)
        }
    }
}
