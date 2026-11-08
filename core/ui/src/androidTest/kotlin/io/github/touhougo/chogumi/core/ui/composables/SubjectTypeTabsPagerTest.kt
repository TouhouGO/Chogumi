package io.github.touhougo.chogumi.core.ui.composables

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 分类 tab 栏跟着翻页走：手指拖到两页正中时，指示条停在两个 tab 正中；松手落定后落在新的那一项上，高亮也跟过去。
 * 停稳时指示条的中心对准选中项文字的中心，等宽排开与放不下要滚动的两种栏都一样。
 * 等分放不下时第一项顶格、每项宽度跟着文字走，总宽放得下时铺满一行。
 * 量的是画出来的指示条（tab 栏底边 primary 色的那一段）的中心。
 */
@RunWith(AndroidJUnit4::class)
class SubjectTypeTabsPagerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var selected by mutableIntStateOf(0)
    private var indicator = Color.Unspecified

    @Test
    fun 拖到一半时指示条在两个tab正中_落定后到新的一项() {
        setTabs(types = subjectTypeTabsOf(includeAll = false), width = 420.dp)
        val first = indicatorCenter()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(PAGER).performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.25f, 0f))
            moveBy(Offset(-width * 0.25f, 0f))
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        val halfway = indicatorCenter()
        composeRule.onNodeWithTag(PAGER).performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        val second = indicatorCenter()

        assertEquals("落定后选中第二项", 1, selected)
        val step = second - first
        assertTrue("指示条从第一项移到第二项：$first → $second", step > 20f)
        assertTrue("拖到一半时指示条在两项正中：$first / $halfway / $second", abs(halfway - (first + step / 2)) < step * 0.2f)
    }

    @Test
    fun 等宽排开时指示条对准选中项文字() {
        setTabs(types = subjectTypeTabsOf(includeAll = false), width = 420.dp)
        assertTrue("420dp 下五个分类等宽排开、不滚动", !tabRowScrolls())
        assertIndicatorUnderTab(0)
        selectTab(2)
        assertIndicatorUnderTab(2)
    }

    @Test
    fun 放不下要滚动时指示条对准选中项文字() {
        setTabs(types = subjectTypeTabsOf(includeAll = true), width = 360.dp)
        assertTrue("360dp 下六个分类放不下、改成可滚动", tabRowScrolls())
        assertIndicatorUnderTab(0)
        selectTab(2)
        assertIndicatorUnderTab(2)
    }

    @Test
    fun 放不下要滚动时第一项顶格_每项按文字宽() {
        val types = subjectTypeTabsOf(includeAll = true)
        setTabs(types = types, width = 360.dp)
        assertTrue("360dp 下六个分类放不下、改成可滚动", tabRowScrolls())
        val row = composeRule.onNodeWithTag(TABS).getUnclippedBoundsInRoot()
        val tabs = composeRule.onAllNodes(isTab).let { all -> types.indices.map { all[it].getUnclippedBoundsInRoot() } }
        assertEquals("第一项贴着 tab 栏起始边", row.left.value, tabs.first().left.value, 0.5f)
        val widths = tabs.map { it.right - it.left }
        assertTrue("两个字的「全部」比三个字的「三次元」窄：$widths", widths.first() < widths.last())
    }

    @Test
    fun 等分放不下但按文字宽放得下时铺满一行() {
        val types = subjectTypeTabsOf(includeAll = false)
        setTabs(types = types, width = 340.dp)
        val row = composeRule.onNodeWithTag(TABS).getUnclippedBoundsInRoot()
        val tabs = composeRule.onAllNodes(isTab).let { all -> types.indices.map { all[it].getUnclippedBoundsInRoot() } }
        val widths = tabs.map { it.right - it.left }
        assertTrue("按文字宽排，不是等分：$widths", widths.first() < widths.last())
        assertEquals("第一项贴着 tab 栏起始边", row.left.value, tabs.first().left.value, 0.5f)
        assertEquals("最后一项伸到 tab 栏末端，右边不空出一块", row.right.value, tabs.last().right.value, 0.5f)
    }

    private fun setTabs(types: List<SubjectType?>, width: Dp) {
        composeRule.setContent {
            // 受控测试视口（not in M3）：420dp 的等宽条件须完整落入设备窗口，不跟随设备字号。
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 1f)) {
                ChogumiTheme {
                    indicator = MaterialTheme.colorScheme.primary
                    val pager = rememberTabPagerState(selected = selected, pageCount = types.size, onSelect = { selected = it })
                    Column(Modifier.width(width).fillMaxSize()) {
                        SubjectTypeTabs(types = types, pager = pager, onSelect = { selected = types.indexOf(it) }, modifier = Modifier.testTag(TABS))
                        TabPager(pager, Modifier.fillMaxSize().testTag(PAGER)) { page -> Text("第 $page 页") }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun tabRowScrolls(): Boolean = composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and hasAnyAncestor(hasTestTag(TABS)))
        .fetchSemanticsNodes().isNotEmpty()

    private fun selectTab(index: Int) {
        composeRule.onAllNodes(isTab)[index].performClick()
        composeRule.waitForIdle()
        assertEquals(index, selected)
    }

    /** 指示条中心与第 [index] 个 tab（文字在 tab 里居中）的中心相差不到 2px。 */
    private fun assertIndicatorUnderTab(index: Int) {
        val row = composeRule.onNodeWithTag(TABS).fetchSemanticsNode().boundsInRoot
        val tab = composeRule.onAllNodes(isTab)[index].fetchSemanticsNode().boundsInRoot
        val expected = tab.center.x - row.left
        val actual = indicatorCenter()
        assertTrue("指示条中心 $actual 应对准第 $index 项中心 $expected", abs(actual - expected) < 2f)
    }

    /** tab 栏底边几行里 primary 色像素的平均横坐标。 */
    private fun indicatorCenter(): Float {
        val bitmap: Bitmap = composeRule.onNodeWithTag(TABS).captureToImage().asAndroidBitmap()
        val target = indicator.toArgb()
        var sum = 0L
        var count = 0
        for (y in bitmap.height - 6 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val p = bitmap.getPixel(x, y)
                if (listOf(16, 8, 0).all { abs((p shr it and 0xFF) - (target shr it and 0xFF)) <= 8 }) {
                    sum += x
                    count++
                }
            }
        }
        check(count > 0) { "tab 栏底边找不到指示条" }
        return sum.toFloat() / count
    }

    private companion object {
        const val TABS = "type_tabs"
        const val PAGER = "type_pager"
        val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    }
}
