package io.github.touhougo.chogumi.core.ui.tour

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 手势引导的翻页与结束：「下一步」一页页翻到最后，最后一页换成「开始使用」、不再有「跳过」；「跳过」随时结束。
 * 演示一直在循环，测试时钟手动推（自动推进会追着循环跑不完）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class GestureTourTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var done = 0

    @Before
    fun show() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            ChogumiTheme(darkTheme = false) {
                GestureTour(onDone = { done++ })
            }
        }
        composeRule.mainClock.advanceTimeBy(FRAME_MILLIS)
    }

    @Test
    fun `下一步翻到最后一页_换成开始使用且没有跳过_点了结束`() {
        composeRule.onNodeWithText("按住封面，滑一下就标好").assertIsDisplayed()
        repeat(PAGES - 1) {
            composeRule.onNodeWithText("下一步").performClick()
            composeRule.mainClock.advanceTimeBy(PAGE_MILLIS)
        }
        composeRule.onNodeWithText("探索页按你的口味排").assertIsDisplayed()
        composeRule.onNodeWithText("跳过").assertDoesNotExist()
        composeRule.onNodeWithText("下一步").assertDoesNotExist()
        assertEquals("翻页不算结束", 0, done)

        composeRule.onNodeWithText("开始使用").performClick()
        assertEquals(1, done)
    }

    @Test
    fun `跳过随时结束`() {
        composeRule.onNodeWithText("下一步").performClick()
        composeRule.mainClock.advanceTimeBy(PAGE_MILLIS)
        composeRule.onNodeWithText("每一集都有讨论").assertIsDisplayed()

        composeRule.onNodeWithText("跳过").performClick()
        assertEquals(1, done)
    }

    private companion object {
        const val PAGES = 4
        const val FRAME_MILLIS = 16L

        /** 一次翻页的弹簧走完（standard default spatial 远不到 1 秒）。 */
        const val PAGE_MILLIS = 1_000L
    }
}
