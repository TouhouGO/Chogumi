package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LoadMoreFooter] 三态与行为规范 UI 仪器测试。
 */
@RunWith(AndroidJUnit4::class)
class LoadMoreFooterTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 加载中态在200ms延迟后显示指示器且具备读屏与进度语义() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ChogumiTheme {
                LoadMoreFooter(
                    loadingMore = true,
                    hasMore = true,
                    error = null,
                    onLoadMore = {},
                )
            }
        }

        // 199ms 内未达 M3 等待时长阈值，不显示指示器
        composeRule.mainClock.advanceTimeBy(199L)
        composeRule.onNodeWithTag(LoadMoreIndicatorTag).assertDoesNotExist()

        // 达到 200ms 阈值，指示器显示并具备「正在加载更多」读屏与进度条语义
        composeRule.mainClock.advanceTimeBy(1L)
        composeRule.onNodeWithTag(LoadMoreIndicatorTag)
            .assertIsDisplayed()
            .assert(hasContentDescription("正在加载更多"))
            .assert(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
    }

    @Test
    fun 出错态显示没加载出来与重试按钮且点击触发重试() {
        var retried = false
        composeRule.setContent {
            ChogumiTheme {
                LoadMoreFooter(
                    loadingMore = false,
                    hasMore = true,
                    error = Failure.Offline,
                    onLoadMore = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText("没加载出来").assertIsDisplayed()
        composeRule.onNodeWithTag(LoadMoreRetryButtonTag).assertIsDisplayed().performClick()
        assertEquals(true, retried)
    }

    @Test
    fun 到底态有文案时居中显示小字() {
        composeRule.setContent {
            ChogumiTheme {
                LoadMoreFooter(
                    loadingMore = false,
                    hasMore = false,
                    error = null,
                    onLoadMore = {},
                    endText = "没有更多结果了",
                )
            }
        }

        composeRule.onNodeWithTag(LoadMoreEndTextTag)
            .assertIsDisplayed()
            .assertTextEquals("没有更多结果了")
    }

    @Test
    fun 到底文案为空或null时不渲染且不占高度() {
        var heightPx = -1
        composeRule.setContent {
            ChogumiTheme {
                Box(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        heightPx = coordinates.size.height
                    },
                ) {
                    LoadMoreFooter(
                        loadingMore = false,
                        hasMore = false,
                        error = null,
                        onLoadMore = {},
                        endText = null,
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(0, heightPx)
        composeRule.onNodeWithTag(LoadMoreEndTextTag).assertDoesNotExist()
        composeRule.onNodeWithTag(LoadMoreIndicatorTag).assertDoesNotExist()
        composeRule.onNodeWithTag(LoadMoreRetryButtonTag).assertDoesNotExist()
    }
}
