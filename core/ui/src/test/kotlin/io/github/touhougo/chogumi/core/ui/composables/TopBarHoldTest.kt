package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 会让位的顶栏遇上列表里吸住的 `stickyHeader`（帖子页的评论区排序行）：吸着时往回翻楼层，顶栏不出来；
 * 往回翻到排序行落回原位、上面的正文露出来，顶栏照常回来。[PageScaffold] 的顶栏与 [HeaderTabsFrame] 让位的那截顶栏是同一条规则（[TopBarHold]）。
 */
@RunWith(AndroidJUnit4::class)
// 矮视口让列表一屏放不下、能往上滚；尺寸是测试设计决定（not in M3）。
@Config(sdk = [34], qualifiers = "w360dp-h400dp")
class TopBarHoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `排序行吸着时往回拉顶栏不出来，回到正文才出来`() {
        composeRule.setContent {
            ChogumiTheme {
                PageScaffold(title = TITLE) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) { commentList() }
                }
            }
        }
        composeRule.waitForIdle()

        readIntoCommentsThenPullBack()
        assertEquals("往回翻楼层时排序行仍吸在最上面", 0f, pinnedTop(), 0.5f)
        composeRule.onNodeWithText(TITLE).assertIsNotDisplayed()

        backToTop()
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        assertEquals(
            "回到正文后顶栏回来，排序行落回正文下面",
            TopAppBarDefaults.TopAppBarExpandedHeight.value + 300f,
            pinnedTop(),
            0.5f,
        )
    }

    @Test
    fun `头部页的排序行吸着时往回拉让位的顶栏不出来，回到正文才出来`() {
        composeRule.setContent {
            ChogumiTheme {
                val frame = rememberCollapsibleHeaderState()
                Box(Modifier.fillMaxSize()) {
                    HeaderTabsFrame(
                        state = frame,
                        header = { Box(Modifier.fillMaxWidth().height(HEADER_DP.dp)) },
                        tabs = { Text("选项卡", Modifier.testTag(TABS)) },
                    ) {
                        commentList()
                    }
                    HeaderTopAppBar(title = TITLE, frame = frame, onBack = null)
                }
            }
        }
        composeRule.waitForIdle()

        readIntoCommentsThenPullBack()
        assertEquals("往回翻楼层时顶栏仍让着，选项卡在最上面", 0f, tabsTop(), 0.5f)
        assertEquals("往回翻楼层时排序行仍吸在选项卡下面", tabsBottom(), pinnedTop(), 0.5f)

        backToTop()
        assertEquals("回到正文后头部全开，选项卡落回头部下面", HEADER_DP.toFloat(), tabsTop(), 0.5f)
    }

    /** 正文 + 吸顶排序行 + 楼层的列表，像帖子页一样。 */
    @Composable
    private fun commentList() {
        val list = rememberLazyListState()
        val edge = rememberStickyHeaderEdge(list)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag(LIST).stickyHeaderFade(edge),
            overscrollEffect = edge.overscrollEffect,
        ) {
            item(key = "post") { Text("正文", Modifier.height(300.dp)) }
            stickyHeader(key = "sort") { Text("排序行", Modifier.testTag(PINNED)) }
            items((1..120).toList()) { Text("第 $it 楼", Modifier.height(48.dp)) }
        }
    }

    /** 往下读到评论区（顶栏让位），再慢慢往下拉一小段（不甩动）在楼层里往回翻。 */
    private fun readIntoCommentsThenPullBack() {
        repeat(3) { composeRule.onNodeWithTag(LIST).performTouchInput { swipeUp() } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(TITLE).assertIsNotDisplayed()
        composeRule.onNodeWithTag(LIST).performTouchInput {
            swipe(start = Offset(centerX, centerY), end = Offset(centerX, centerY + 150.dp.toPx()), durationMillis = 1000)
        }
        composeRule.waitForIdle()
    }

    private fun backToTop() {
        repeat(6) { composeRule.onNodeWithTag(LIST).performTouchInput { swipeDown() } }
        composeRule.waitForIdle()
    }

    private fun pinnedTop() = composeRule.onNodeWithTag(PINNED).getBoundsInRoot().top.value

    private fun tabsTop() = composeRule.onNodeWithTag(TABS).getBoundsInRoot().top.value

    private fun tabsBottom() = composeRule.onNodeWithTag(TABS).getBoundsInRoot().bottom.value
}

private const val TITLE = "标题"
private const val LIST = "list"
private const val PINNED = "pinned"
private const val TABS = "tabs"

/** 头部高度：比顶栏高，收满后还要让出顶栏那一截。测试设计决定（not in M3）。 */
private const val HEADER_DP = 200
