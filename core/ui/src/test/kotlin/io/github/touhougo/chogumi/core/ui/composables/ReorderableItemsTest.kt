package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 整行长按后拖动（[ReorderableItemScope.dragOnLongPress]）：行自己的点按、长按（菜单）照常；长按后手指一动才抬起，
 * 抬起时调 onLift（收起菜单）；放进带别的项（吸顶行）的列表里也只在这些行之间换位。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReorderableItemsTest {
    @get:Rule val compose = createComposeRule()

    private var rows by mutableStateOf(listOf("a", "b", "c"))
    private var enabled by mutableStateOf(true)
    private var accept = true
    private val moves = mutableListOf<List<String>>()
    private val clicks = mutableListOf<String>()
    private val longPresses = mutableListOf<String>()
    private var lifts = 0

    private fun setList() {
        compose.setContent {
            ChogumiTheme {
                val list = rememberLazyListState()
                val reorder = rememberReorderState(
                    items = rows,
                    key = { it },
                    list = list,
                    onMove = { keys ->
                        moves += keys
                        if (accept) rows = keys
                        accept
                    },
                    onLift = { lifts++ },
                )
                LazyColumn(state = list) {
                    stickyHeader(key = "header") { Text("header", Modifier.testTag("header")) }
                    reorderableItems(reorder, enabled = enabled) { _, item ->
                        Box(Modifier.dragOnLongPress()) {
                            SegmentedItem(
                                index = segmentIndex,
                                count = segmentCount,
                                onClick = { clicks += item },
                                onLongClick = { longPresses += item },
                                modifier = Modifier.testTag("row_$item"),
                            ) { Text(item) }
                        }
                    }
                }
            }
        }
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun rowHeight() = compose.onNodeWithTag("row_a").fetchSemanticsNode().boundsInRoot.height

    /** 长按 [tag] 那一行，再上下移动 [dy]（0 = 不动）后松手。 */
    private fun longPressAndMove(tag: String, dy: Float) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            if (dy != 0f) {
                moveBy(Offset(0f, if (dy < 0) -viewConfiguration.touchSlop - 1f else viewConfiguration.touchSlop + 1f))
                moveBy(Offset(0f, dy))
            }
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun 点一下是行自己的点按() {
        setList()
        compose.onNodeWithTag("row_b").performClick()
        compose.runOnIdle {
            assertEquals(listOf("b"), clicks)
            assertTrue(longPresses.isEmpty())
            assertTrue(moves.isEmpty())
        }
    }

    @Test
    fun 长按不动就松手_只是行自己的长按_不抬起不排序() {
        setList()
        longPressAndMove("row_b", dy = 0f)
        compose.runOnIdle {
            assertEquals(listOf("b"), longPresses)
            assertEquals(0, lifts)
            assertTrue(moves.isEmpty())
            assertTrue("长按后松手不算点按", clicks.isEmpty())
        }
    }

    @Test
    fun 长按后往上拖过一行_抬起时调onLift_松手交出新顺序() {
        setList()
        longPressAndMove("row_b", dy = -rowHeight())
        compose.runOnIdle {
            assertEquals(listOf("b"), longPresses)
            assertEquals(1, lifts)
            assertEquals(listOf(listOf("b", "a", "c")), moves)
            assertTrue(clicks.isEmpty())
        }
        assertTrue("吸顶行不参与换位", top("header") < top("row_b"))
        assertTrue(top("row_b") < top("row_a"))
    }

    @Test
    fun 不能拖时长按后移动_不排序() {
        enabled = false
        setList()
        longPressAndMove("row_b", dy = -rowHeight())
        compose.runOnIdle {
            assertEquals(listOf("b"), longPresses)
            assertEquals(0, lifts)
            assertTrue(moves.isEmpty())
        }
        assertTrue(top("row_a") < top("row_b"))
    }

    @Test
    fun 松手后不被接受_屏上回到原来的顺序() {
        accept = false
        setList()
        longPressAndMove("row_b", dy = -rowHeight())
        compose.runOnIdle { assertEquals(listOf(listOf("b", "a", "c")), moves) }
        assertTrue("回到 a 在 b 前", top("row_a") < top("row_b"))
    }
}
