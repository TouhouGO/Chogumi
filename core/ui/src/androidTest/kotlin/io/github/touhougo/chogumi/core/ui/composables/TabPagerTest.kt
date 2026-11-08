package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TabPager] 与选中项的同步契约：选中项是唯一真源，翻页跟着它；手指翻页落定后才回写；
 * 翻页途中高亮跟着翻过一半的那一页；点远处的 tab 时高亮不逐个闪过中间各页；页内横滑滑到头不顺势翻页；
 * 松手后翻页在两页之间停一下再继续，以最终停下的那页为准；翻页途中页数变少时高亮不越界。
 */
@RunWith(AndroidJUnit4::class)
class TabPagerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var selected by mutableIntStateOf(0)
    private var shown = -1
    private val selections = mutableListOf<Int>()

    private fun setPager(pageContent: @androidx.compose.runtime.Composable (Int) -> Unit = { page -> Text("第 $page 页") }) {
        composeRule.setContent {
            ChogumiTheme {
                val pager = rememberTabPagerState(selected = selected, pageCount = PAGES, onSelect = { selections += it; selected = it })
                shown = pager.shownPage
                TabPager(pager, Modifier.fillMaxSize().testTag(PAGER), key = { "page-$it" }) { page ->
                    Box(Modifier.fillMaxSize().testTag("page_$page")) { pageContent(page) }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun 选中项变了翻页跟过去_不回写() {
        setPager()
        composeRule.runOnIdle { selected = 2 }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("page_2").assertIsDisplayed()
        assertEquals("翻页是跟着选中项走的，不是手指翻的，不回写", emptyList<Int>(), selections)
        assertEquals(2, shown)
    }

    @Test
    fun 手指翻页落定后回写选中项() {
        setPager()
        composeRule.onNodeWithTag(PAGER).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("page_1").assertIsDisplayed()
        assertEquals(listOf(1), selections)
        assertEquals(1, shown)
    }

    @Test
    fun 翻过一半时高亮先跟过去_松手落定才回写() {
        setPager()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(PAGER).performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.7f, 0f))
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertEquals("翻过一半，高亮已经是下一页", 1, shown)
        assertEquals("还没松手，选中项不变", 0, selected)

        composeRule.onNodeWithTag(PAGER).performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(listOf(1), selections)
    }

    @Test
    fun 点远处的选项时高亮不逐个闪过中间各页() {
        setPager()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { selected = 3 }
        val seen = (0 until 60).map {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            shown
        }
        composeRule.mainClock.autoAdvance = true
        assertTrue("高亮一直是点的那一项：$seen", seen.all { it == 3 })
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("page_3").assertIsDisplayed()
    }

    @Test
    fun 页内横滑滑到头后不顺势翻页() {
        setPager { page ->
            Column(Modifier.fillMaxSize()) {
                Text("第 $page 页")
                if (page == 0) {
                    val rowState = rememberLazyListState()
                    LazyRow(state = rowState, modifier = Modifier.fillMaxWidth().height(80.dp).testTag(ROW)) {
                        items(30) { Box(Modifier.size(80.dp)) { Text("$it") } }
                    }
                }
            }
        }
        // 连滑几次：先把横排滑到头，之后剩下的滑动也不能翻页
        repeat(6) {
            composeRule.onNodeWithTag(ROW).performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithTag("page_0").assertIsDisplayed()
        assertEquals("在横排上滑，动的只是横排", emptyList<Int>(), selections)
        assertEquals(0, selected)
    }

    @Test
    fun 松手后翻页在两页之间停了一下_最终停在哪页就回写哪页() {
        lateinit var state: TabPagerState
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            ChogumiTheme {
                scope = rememberCoroutineScope()
                state = rememberTabPagerState(selected = selected, pageCount = PAGES, onSelect = { selections += it; selected = it })
                TabPager(state, Modifier.fillMaxSize().testTag(PAGER)) { page -> Text("第 $page 页") }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(PAGER).performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.3f, 0f))
            up()
        }
        // 真机上松手到惯性接上之间，翻页有一刹那不在滚动、却还停在两页之间；这里打断惯性造出这个空档
        composeRule.runOnIdle { scope.launch { state.pager.scroll(MutatePriority.PreventUserInput) {} } }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertTrue("此刻停在两页之间、不在滚动", !state.pager.isScrollInProgress && state.pager.currentPageOffsetFraction != 0f)
        // 惯性接上，停到下一页
        composeRule.runOnIdle { scope.launch { state.pager.animateScrollToPage(1) } }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals("最终停在第二页，回写一次", listOf(1), selections)
        assertEquals(1, selected)
    }

    @Test
    fun 翻页途中页数变少_高亮不越界() {
        var pageCount by mutableIntStateOf(PAGES)
        selected = PAGES - 2
        val shownWhilePagesShrink = mutableListOf<Pair<Int, Int>>()
        composeRule.setContent {
            ChogumiTheme {
                val labels = (0 until pageCount).map { "第 $it 页" }
                val pager = rememberTabPagerState(selected = selected, pageCount = pageCount, onSelect = { selected = it })
                // 选择器按高亮项取页名：越界就是闪退
                Text(labels[pager.shownPage])
                shownWhilePagesShrink += pager.shownPage to pageCount
                TabPager(pager, Modifier.fillMaxSize().testTag(PAGER)) { page -> Text(labels[page]) }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        // 手指拖到最后一页，还没松手时最后一页消失（作品详情的人物页随数据隐藏）
        composeRule.onNodeWithTag(PAGER).performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.7f, 0f))
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.runOnIdle { pageCount = PAGES - 1 }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PAGER).performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertTrue(
            "高亮项始终在页数以内：$shownWhilePagesShrink",
            shownWhilePagesShrink.all { (shown, count) -> shown in 0 until count },
        )
    }

    private companion object {
        const val PAGES = 5
        const val PAGER = "tab_pager"
        const val ROW = "inner_row"
    }
}
