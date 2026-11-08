package io.github.touhougo.chogumi.core.ui.composables

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ChogumiPullToRefreshBox] 的指示器收起时不进无障碍树。
 *
 * 收起的官方指示器平移到下拉区上方、只在绘制上藏起来；它若还有语义，就会把正上方的文字整个盖住，
 * 无障碍树剔除被盖住的节点，那段文字读屏读不到、UI 测试也找不到。这里在下拉区正上方居中放一个短标签，
 * 和小屏进度页上「动画」tab 落在指示器下面的位置关系相同。
 */
@RunWith(AndroidJUnit4::class)
class PullToRefreshIndicatorSemanticsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(isRefreshing: Boolean) {
        composeRule.setContent {
            ChogumiTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        Text(Label)
                    }
                    ChogumiPullToRefreshBox(isRefreshing = isRefreshing, onRefresh = {}, modifier = Modifier.weight(1f)) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    @Test
    fun 收起时下拉区上方的文字仍在无障碍树里() {
        setContent(isRefreshing = false)
        composeRule.waitForIdle()

        val id = composeRule.onNodeWithText(Label).fetchSemanticsNode().id
        val view = (composeRule.onNodeWithText(Label).fetchSemanticsNode().root as ViewRootForTest).view
        var text: CharSequence? = null
        composeRule.runOnUiThread {
            val info: AccessibilityNodeInfo? = view.accessibilityNodeProvider.createAccessibilityNodeInfo(id)
            text = info?.text
        }
        assertEquals(Label, text?.toString())
    }

    @Test
    fun 刷新时指示器有进度条语义() {
        setContent(isRefreshing = true)

        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
    }

    private companion object {
        const val Label = "动画"
    }
}
