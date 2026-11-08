package io.github.touhougo.chogumi.core.ui.composables

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetRegistry
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 页面面板的一个周期：打开 → 关闭回调恰好一次 → 面板撤掉、下面的页面能点、「开着」复位。
 *
 * 关闭按面板回到隐藏位置判定，所以刚开就关（首开动画还没开始、或刚开始）也会关掉；面板一开始收起就不再算开着，
 * 底栏与读屏不等它滑完。时钟手动推进，每一步都是确定的帧。
 */
@RunWith(AndroidJUnit4::class)
class PageModalSheetLifecycleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val registry = PageSheetRegistry()
    private var open by mutableStateOf(false)
    private var dismissed by mutableIntStateOf(0)
    private var pageClicks by mutableIntStateOf(0)
    private val closeLabel by lazy { ApplicationProvider.getApplicationContext<Context>().getString(R.string.close) }

    private fun setContent() {
        composeRule.setContent {
            ChogumiTheme {
                PageSheetTestHost(registry) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.size(120.dp).testTag(PageTag).clickable { pageClicks++ })
                        if (open) {
                            PageModalSheet(onDismiss = { dismissed++; open = false }, fullHeight = false) {
                                Text("sheet", Modifier.testTag(SheetTag))
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun tapScrim() {
        composeRule.onNodeWithContentDescription(closeLabel).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun advanceFrames(count: Int) = repeat(count) { composeRule.mainClock.advanceTimeByFrame() }

    private fun assertClosedAndPageTouchable() {
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("关闭回调恰好一次", 1, dismissed)
        composeRule.onNodeWithTag(SheetTag).assertDoesNotExist()
        assertFalse("面板撤掉后不再算开着（底栏据此回来）", registry.anyOpen)
        composeRule.onNodeWithTag(PageTag).performClick()
        assertEquals("下面的页面能点", 1, pageClicks)
    }

    @Test
    fun 首开动画还没开始就点遮罩_面板直接撤掉() {
        setContent()
        composeRule.mainClock.autoAdvance = false
        open = true
        advanceFrames(1)
        tapScrim()
        assertClosedAndPageTouchable()
    }

    @Test
    fun 首开动画半路点遮罩_收回隐藏位置后关闭() {
        setContent()
        composeRule.mainClock.autoAdvance = false
        open = true
        // 首开动画已经开始、还没停稳：停稳的那一档从头到尾都是「隐藏」
        advanceFrames(4)
        tapScrim()
        assertClosedAndPageTouchable()
    }

    @Test
    fun 开始收起时就不算开着_不等滑完() {
        setContent()
        open = true
        composeRule.waitForIdle()
        assertTrue("面板开着", registry.anyOpen)

        composeRule.mainClock.autoAdvance = false
        tapScrim()
        advanceFrames(2)
        composeRule.onNodeWithTag(SheetTag).assertExists()
        assertFalse("收起中已不算开着", registry.anyOpen)
        assertEquals("还没滑完，不回调", 0, dismissed)

        assertClosedAndPageTouchable()
    }

    private companion object {
        const val PageTag = "page"
        const val SheetTag = "sheet"
    }
}
