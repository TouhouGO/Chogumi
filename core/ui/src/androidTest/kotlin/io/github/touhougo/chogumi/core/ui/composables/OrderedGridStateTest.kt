package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [rememberOrderedGridState] 的契约：顺序计数变了回到顶部；离屏的页在离屏期间计数变了，再出现时也从顶部开始；
 * 计数没变时，翻页翻回来、组合重建（返回、旋转）都停在原处。
 */
@RunWith(AndroidJUnit4::class)
class OrderedGridStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var order by mutableLongStateOf(1L)
    private var selected by mutableIntStateOf(0)
    private val states = arrayOfNulls<LazyGridState>(PAGES)

    @Composable
    private fun Page(page: Int) {
        val state = rememberOrderedGridState(order)
        states[page] = state
        LazyVerticalGrid(columns = GridCells.Fixed(1), state = state, modifier = Modifier.fillMaxSize()) {
            items(200) { Text("第 $page 页 $it", Modifier.height(48.dp)) }
        }
    }

    private fun setPages() {
        composeRule.setContent {
            ChogumiTheme {
                val pager = rememberTabPagerState(selected = selected, pageCount = PAGES, onSelect = { selected = it })
                TabPager(pager, Modifier.fillMaxSize(), key = { "page-$it" }) { page -> Page(page) }
            }
        }
        composeRule.waitForIdle()
    }

    private fun scrollFirstPageDown() {
        composeRule.runOnIdle { states[0]!!.requestScrollToItem(SCROLLED) }
        composeRule.waitForIdle()
        assertEquals(SCROLLED, states[0]!!.firstVisibleItemIndex)
    }

    private fun goTo(page: Int) {
        composeRule.runOnIdle { selected = page }
        composeRule.waitForIdle()
    }

    @Test
    fun 顺序变了回到顶部() {
        setPages()
        scrollFirstPageDown()
        composeRule.runOnIdle { order++ }
        composeRule.waitForIdle()
        assertEquals(0, states[0]!!.firstVisibleItemIndex)
    }

    @Test
    fun 离屏期间顺序变了_再出现时从顶部开始() {
        setPages()
        scrollFirstPageDown()
        goTo(2)
        composeRule.runOnIdle { order++ }
        composeRule.waitForIdle()
        goTo(0)
        assertEquals("离屏时各页一起重排过，翻回来从顶部开始", 0, states[0]!!.firstVisibleItemIndex)
    }

    @Test
    fun 顺序没变_翻走再翻回来停在原处() {
        setPages()
        scrollFirstPageDown()
        goTo(2)
        goTo(0)
        assertEquals(SCROLLED, states[0]!!.firstVisibleItemIndex)
    }

    @Test
    fun 顺序没变_组合重建后停在原处() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { ChogumiTheme { Page(0) } }
        scrollFirstPageDown()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertEquals(SCROLLED, states[0]!!.firstVisibleItemIndex)
    }

    private companion object {
        const val PAGES = 3
        const val SCROLLED = 50
    }
}
