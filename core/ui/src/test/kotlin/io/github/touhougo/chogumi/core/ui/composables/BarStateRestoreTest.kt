package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 重建后顶栏还是原来收起的那几成：从下一页返回（同一密度）时第一帧就摆好，改了显示大小（可收起的高度按新密度重量）时按比例重摆。
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
// 矮视口让列表一屏放不下、能往上滚；尺寸是测试设计决定（not in M3）。
@Config(sdk = [34], qualifiers = "w360dp-h400dp")
class BarStateRestoreTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * 顶栏可收起的高度（像素）；顶栏组件组合时把它设成上限，同 M3 顶栏。不是快照状态：改它不让旧的组合重量，
     * 新的高度只有重建后的组合才看到，同换密度时 Activity 重建。
     */
    private var barHeight = 200f
    private lateinit var state: TopAppBarState

    private fun StateRestorationTester.showBar() = setContent {
        state = rememberBarState()
        val height = barHeight
        SideEffect { state.heightOffsetLimit = -height }
    }

    /**
     * 首页这类让位顶栏的页面：往下读时顶栏收走，打开下一页再返回，页面重新组合。顶栏若先全开一帧，Scaffold 给列表的上边距
     * 多出一个顶栏高，列表先下移再上移；容器转换把这段位移加在飞回来的整页上，整页上跳、底部露出空白。
     */
    @Test
    fun `收走的顶栏从下一页返回时列表第一帧就在原处`() {
        val restoration = StateRestorationTester(compose)
        lateinit var list: LazyListState
        val firstTopAfterRestore = HashMap<Int, Float>()
        var restored = false
        restoration.setContent {
            ChogumiTheme {
                PageScaffold(title = TITLE, contentWindowInsets = WindowInsets(0)) { padding ->
                    list = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize().testTag(LIST), state = list, contentPadding = padding) {
                        items(ROWS) { row ->
                            Text(
                                text = "第 $row 行",
                                modifier = Modifier.fillMaxWidth().height(48.dp).onGloballyPositioned {
                                    if (restored) firstTopAfterRestore.putIfAbsent(row, it.positionInRoot().y)
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag(LIST).performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText(TITLE).assertIsNotDisplayed()
        val row = list.firstVisibleItemIndex + 1
        val topBefore = compose.onNodeWithText("第 $row 行").fetchSemanticsNode().positionInRoot.y

        restored = true
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        val firstTop = firstTopAfterRestore[row]
        assertNotNull("恢复后第 $row 行没有排版", firstTop)
        assertEquals("恢复后第一帧第 $row 行的位置", topBefore, firstTop!!, 0.5f)
        compose.onNodeWithText(TITLE).assertIsNotDisplayed()
    }

    @Test
    fun `收满的顶栏换了密度重建后仍收满`() {
        val restoration = StateRestorationTester(compose)
        restoration.showBar()
        compose.runOnIdle { state.heightOffset = -200f }

        barHeight = 300f
        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle { assertEquals(-300f, state.heightOffset) }
    }

    @Test
    fun `收起一半的顶栏换了密度重建后仍收起一半`() {
        val restoration = StateRestorationTester(compose)
        restoration.showBar()
        compose.runOnIdle { state.heightOffset = -100f }

        barHeight = 300f
        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle { assertEquals(-150f, state.heightOffset) }
    }

    @Test
    fun `全开的顶栏换了密度重建后仍全开`() {
        val restoration = StateRestorationTester(compose)
        restoration.showBar()

        barHeight = 300f
        restoration.emulateSavedInstanceStateRestore()

        compose.runOnIdle {
            assertEquals(-300f, state.heightOffsetLimit)
            assertEquals(0f, state.heightOffset)
        }
    }

    private companion object {
        const val TITLE = "标题"
        const val LIST = "list"
        const val ROWS = 40
    }
}
