package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TabPager] 跟着选中项走：连着快速点几个 tab，后一次点选打断前一次的翻页，接着前一次此刻的速度走，
 * 停下时整页停在最后点的那一页上，不卡在两页之间。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabPagerFollowTest {
    @get:Rule
    val compose = createComposeRule()

    private var selected by mutableIntStateOf(0)
    private lateinit var state: TabPagerState

    private fun setPager() {
        compose.setContent {
            state = rememberTabPagerState(selected = selected, pageCount = PAGES, onSelect = { selected = it })
            TabPager(state, Modifier.fillMaxSize(), key = { "page-$it" }) { page -> Text("第 $page 页") }
        }
        compose.waitForIdle()
    }

    /** 翻页此刻的连续位置：页序 + 偏移比例。 */
    private val position: Float get() = state.pager.currentPage + state.pager.currentPageOffsetFraction

    /** 一帧一帧往前走，直到 [reached]；走满 [MAX_FRAMES] 帧还没到就是翻页没在动画里经过那里。 */
    private fun stepUntil(what: String, reached: () -> Boolean) {
        repeat(MAX_FRAMES) {
            if (compose.runOnIdle(reached)) return
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        throw AssertionError("翻页没有经过「$what」，此刻在 $position")
    }

    /** 走一帧，返回这一帧里翻页挪了多少页。 */
    private fun stepFrame(): Float {
        val before = compose.runOnIdle { position }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        return compose.runOnIdle { position } - before
    }

    /** 放开时钟走到底：翻页停下时整页停在 [page] 上（不在两页之间），选中项还是它。 */
    private fun assertRestsOn(page: Int) {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("翻页停下了", !state.pager.isScrollInProgress)
            assertEquals("停在哪一页（此刻在 $position）", page, state.pager.currentPage)
            assertEquals("整页停住，不在两页之间（此刻在 $position）", 0f, state.pager.currentPageOffsetFraction)
            assertEquals("选中项没被改写", page, selected)
        }
    }

    @Test
    fun 点选的翻页被下一次点选打断时离新点的那页已经最近_仍整页停到新点的那页() {
        setPager()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = 2 }
        // 从第 0 页翻向第 2 页刚过第 1 页：离第 1 页最近（currentPage 已是 1），却还在第 1、2 页之间
        stepUntil("刚翻过第 1 页") { state.pager.currentPage == 1 && state.pager.currentPageOffsetFraction > 0f }
        compose.runOnIdle { selected = 1 }

        assertRestsOn(1)
    }

    @Test
    fun 点选的翻页被下一次点选打断时离新点的那页还远_同样整页停到新点的那页() {
        setPager()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = 2 }
        // 从第 0 页翻向第 2 页还没到第 1 页：离新点的第 3 页还远，照常翻过去
        stepUntil("离开第 0 页") { position > 0.2f && state.pager.currentPage == 0 }
        compose.runOnIdle { selected = 3 }

        assertRestsOn(3)
    }

    @Test
    fun 点选的翻页被下一次点选打断时接着当时的速度走_不刹停也不空一帧() {
        setPager()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { selected = 1 }
        // 从第 0 页翻向第 1 页、正在加速时，改点更远的第 2 页（同一个方向）
        stepUntil("翻过两成") { position > 0.2f }
        val moving = stepFrame()
        compose.runOnIdle { selected = 2 }

        val after = List(3) { stepFrame() }
        assertTrue("打断后的每一帧都接着往前走，不低于打断前一帧的一半（打断前 $moving，之后 $after）", after.all { it >= moving / 2 })
        assertRestsOn(2)
    }

    private companion object {
        const val PAGES = 5
        const val MAX_FRAMES = 120
    }
}
