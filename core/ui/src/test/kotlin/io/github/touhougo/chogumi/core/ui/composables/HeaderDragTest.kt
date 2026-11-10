package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 头部与选项卡上按住拖也收起、展开头部（[CollapsibleHeader]）：作品详情展开了标签行时，头部能占满一屏，
 * 只在内容里拖得动的话几乎无处可拖；头部比一屏还高时，它最下面的「收起」要能拖上来、停在那里点到。
 */
@RunWith(AndroidJUnit4::class)
// 矮视口：头部能比一屏还高；尺寸是测试设计决定（not in M3）。
@Config(sdk = [34], qualifiers = "w360dp-h400dp")
class HeaderDragTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `头部比一屏还高时按在头部上往上拖，最下面的按钮拖上来后停在屏幕里点得到`() {
        var clicks = 0
        frame(headerHeight = TALL_HEADER_DP) {
            Text("收起", Modifier.testTag(BOTTOM).clickable { clicks++ })
        }

        // 慢慢拖、不甩：松手的位置就是停的位置
        dragOnRoot(fromY = 380f, toY = 120f)

        val bottom = composeRule.onNodeWithTag(BOTTOM).getBoundsInRoot()
        assertTrue(
            "最下面的按钮停在顶栏下面、屏幕之内：${bottom.top}..${bottom.bottom}",
            bottom.top.value >= TopAppBarDefaults.TopAppBarExpandedHeight.value && bottom.bottom.value < VIEWPORT_DP && bottom.bottom > bottom.top,
        )
        composeRule.onNodeWithTag(BOTTOM).performClick()
        assertEquals("停住之后点得到", 1, clicks)
    }

    @Test
    fun `按在选项卡上往上拖过一半，头部收满、选项卡停在顶栏下面`() {
        frame(headerHeight = SHORT_HEADER_DP) {}

        val tabsTop = composeRule.onNodeWithTag(TABS).getBoundsInRoot().top.value
        dragOnRoot(fromY = tabsTop + 10f, toY = tabsTop + 10f - 100f)

        assertEquals(
            "收满后选项卡停在顶栏下面",
            TopAppBarDefaults.TopAppBarExpandedHeight.value,
            composeRule.onNodeWithTag(TABS).getBoundsInRoot().top.value,
            0.5f,
        )
    }

    @Test
    fun `头部里的点按照常是点按`() {
        var clicks = 0
        frame(headerHeight = SHORT_HEADER_DP) {
            Text("资料", Modifier.testTag(BOTTOM).clickable { clicks++ })
        }

        val tabsTop = composeRule.onNodeWithTag(TABS).getBoundsInRoot().top.value
        composeRule.onNodeWithTag(BOTTOM).performClick()
        composeRule.waitForIdle()

        assertEquals(1, clicks)
        assertEquals("点按不动头部", tabsTop, composeRule.onNodeWithTag(TABS).getBoundsInRoot().top.value, 0.5f)
    }

    private fun frame(headerHeight: Int, bottom: @Composable () -> Unit) {
        composeRule.setContent {
            ChogumiTheme {
                val state = rememberCollapsibleHeaderState()
                Box(Modifier.fillMaxSize()) {
                    HeaderTabsFrame(
                        state = state,
                        header = {
                            Column(Modifier.fillMaxWidth()) {
                                Box(Modifier.fillMaxWidth().height(headerHeight.dp))
                                bottom()
                            }
                        },
                        tabs = { Text("选项卡", Modifier.testTag(TABS)) },
                    ) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items((1..60).toList()) { Text("第 $it 行", Modifier.height(48.dp)) }
                        }
                    }
                    HeaderTopAppBar(title = "标题", frame = state, onBack = null)
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** 在根上从 [fromY] 慢慢拖到 [toY]（dp，横向居中），松手时几乎没有速度。 */
    private fun dragOnRoot(fromY: Float, toY: Float) {
        composeRule.onRoot().performTouchInput {
            swipe(Offset(centerX, fromY.dp.toPx()), Offset(centerX, toY.dp.toPx()), durationMillis = 1500)
        }
        composeRule.waitForIdle()
    }
}

private const val TABS = "tabs"
private const val BOTTOM = "bottom"
private const val VIEWPORT_DP = 400f

/** 比 [VIEWPORT_DP] 高的头部（小屏上展开了标签行）。测试设计决定（not in M3）。 */
private const val TALL_HEADER_DP = 600

/** 放得下的头部：比顶栏高，收满后还要让出顶栏那一截。测试设计决定（not in M3）。 */
private const val SHORT_HEADER_DP = 200
