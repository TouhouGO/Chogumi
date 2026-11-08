package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.common.navigation.LocalTabReselect
import io.github.touhougo.chogumi.core.ui.common.navigation.ScrollToTopOnTabReselect
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TabPager] 的页：上过屏的页翻走再翻回是现成的，不重新组合；没上过屏的页不组合内容；
 * 翻页器变高（头部收起）时不在屏幕上的页不重新量、不重新放，回到屏幕上时按此刻的高度量；
 * 翻走的页往整个页面登记的东西（再点 tab 回顶、吸顶条扣住顶栏）不算数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabPagerPagesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 看过的页翻走再翻回不重新组合_没上过屏的页不组合() {
        var selected by mutableIntStateOf(0)
        val compositions = IntArray(PAGES)
        compose.setContent {
            val state = rememberTabPagerState(selected = selected, pageCount = PAGES, onSelect = { selected = it })
            TabPager(state, Modifier.fillMaxSize(), key = { "page-$it" }) { page ->
                remember { compositions[page]++ }
                Text("第 $page 页")
            }
        }
        listOf(1, 0, 1, 0).forEach { page ->
            compose.runOnIdle { selected = page }
            compose.waitForIdle()
        }

        assertEquals("第 0、1 页各组合一次，其余没上过屏", listOf(1, 1, 0, 0, 0), compositions.toList())
    }

    @Test
    fun 头部收起时翻走的页不重新量也不重新放_回到屏幕上按此刻的高度量() {
        var selected by mutableIntStateOf(0)
        var header by mutableStateOf(120.dp)
        val measures = IntArray(PAGES)
        val positioned = IntArray(PAGES)
        val heights = IntArray(PAGES)
        compose.setContent {
            val state = rememberTabPagerState(selected = selected, pageCount = PAGES, onSelect = { selected = it })
            // 头部收起：翻页器的顶边上移、高度变大，和页面里 CollapsibleHeader 下面的内容一样
            Column(Modifier.height(480.dp)) {
                Spacer(Modifier.height(header))
                TabPager(state, Modifier.weight(1f), key = { "page-$it" }) { page ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { positioned[page]++ }
                            .layout { measurable, constraints ->
                                measures[page]++
                                val placeable = measurable.measure(constraints)
                                heights[page] = placeable.height
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            },
                    )
                }
            }
        }
        listOf(1, 0).forEach { page ->
            compose.runOnIdle { selected = page }
            compose.waitForIdle()
        }
        val measuredBefore = compose.runOnIdle { measures.toList() }
        val positionedBefore = compose.runOnIdle { positioned.toList() }

        compose.runOnIdle { header = 40.dp }
        compose.waitForIdle()

        compose.runOnIdle {
            assertTrue("当前页跟着重新量、重新放", measures[0] > measuredBefore[0] && positioned[0] > positionedBefore[0])
            assertEquals("翻走的第 1 页不重新量", measuredBefore[1], measures[1])
            assertEquals("翻走的第 1 页不重新放", positionedBefore[1], positioned[1])
        }
        compose.runOnIdle { selected = 1 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("回到屏幕上的第 1 页按此刻的高度量", heights[0], heights[1]) }
    }

    @Test
    fun 再点当前_tab_只有在屏幕上的页滚回顶部_翻走的页停在原处() {
        var selected by mutableIntStateOf(0)
        val reselects = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val lists = arrayOfNulls<LazyListState>(PAGES)
        compose.setContent {
            val state = rememberTabPagerState(selected = selected, pageCount = PAGES, onSelect = { selected = it })
            CompositionLocalProvider(LocalTabReselect provides reselects) {
                TabPager(state, Modifier.fillMaxSize(), key = { "page-$it" }) { page ->
                    val list = rememberLazyListState().also { lists[page] = it }
                    ScrollToTopOnTabReselect(list)
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                        items((1..100).toList()) { Text("第 $page 页第 $it 行", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        compose.runOnIdle { checkNotNull(lists[0]).requestScrollToItem(30) }
        compose.runOnIdle { selected = 1 }
        compose.waitForIdle()
        compose.runOnIdle { checkNotNull(lists[1]).requestScrollToItem(30) }
        compose.waitForIdle()

        compose.runOnIdle { reselects.tryEmit(Unit) }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals("在屏幕上的第 1 页滚回顶部", 0, checkNotNull(lists[1]).firstVisibleItemIndex)
            assertEquals("翻走的第 0 页停在原处", 30, checkNotNull(lists[0]).firstVisibleItemIndex)
        }
    }

    @Test
    fun 翻走的页里吸住的排序行不扣住顶栏_翻回来照常扣住() {
        var selected by mutableIntStateOf(0)
        val hold = TopBarHold()
        val lists = arrayOfNulls<LazyListState>(PAGES)
        compose.setContent {
            val state = rememberTabPagerState(selected = selected, pageCount = PAGES, onSelect = { selected = it })
            CompositionLocalProvider(LocalTopBarHold provides hold) {
                TabPager(state, Modifier.fillMaxSize(), key = { "page-$it" }) { page ->
                    val list = rememberLazyListState().also { lists[page] = it }
                    val edge = rememberStickyHeaderEdge(list)
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().stickyHeaderFade(edge)) {
                        item(key = "post") { Text("正文", Modifier.height(300.dp)) }
                        // 只有第 0 页有吸顶的排序行，像帖子页的评论区
                        if (page == 0) stickyHeader(key = "sort") { Text("排序行") }
                        items((1..100).toList()) { Text("第 $page 页第 $it 楼", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        compose.runOnIdle { checkNotNull(lists[0]).requestScrollToItem(10) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("第 0 页读到评论区，排序行吸住、扣住顶栏", hold.held) }

        compose.runOnIdle { selected = 1 }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("翻到第 1 页，看不见的排序行不扣住顶栏", hold.held) }

        compose.runOnIdle { selected = 0 }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("翻回第 0 页，排序行照常扣住顶栏", hold.held) }
    }

    private companion object {
        const val PAGES = 5
    }
}
