package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `PriorityTextLine` 两端都按相对方向摆放：RTL 同行从右到左，换行都从右缘开始，end 位贴物理左缘。 */
@RunWith(AndroidJUnit4::class)
class PriorityTextLineRtlTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun RTL同行时姓名在动作右侧并共享基线() {
        setLine(width = 300.dp, primary = "姓名", trailing = "动作")
        val name = bounds(PRIMARY)
        val action = bounds(TRAILING)

        assertTrue("RTL 中姓名应在动作右侧", name.left > action.left)
        assertTrue("姓名与动作不互相覆盖", name.left >= action.right)
        assertEquals("姓名从容器右缘开始", bounds(LINE).right.value, name.right.value, Tolerance)
        assertEquals("RTL 同行基线不同", baseline(PRIMARY), baseline(TRAILING), Tolerance)
    }

    @Test
    fun RTL换行时姓名与动作都从右缘开始() {
        setLine(width = 100.dp, primary = "姓名姓名姓名", trailing = "动作")
        val name = bounds(PRIMARY)
        val action = bounds(TRAILING)

        assertTrue("动作应在姓名下一行", action.top >= name.bottom - Tolerance.dp)
        assertEquals("RTL 换行后两段应同靠右缘", name.right.value, action.right.value, Tolerance)
        assertEquals("换行后从容器右缘开始", bounds(LINE).right.value, name.right.value, Tolerance)
    }

    @Test
    fun RTL同行尾部贴logicalEnd() {
        setLine(width = 240.dp, primary = "姓名", trailing = "来源", trailingToEnd = true)
        val name = bounds(PRIMARY)
        val source = bounds(TRAILING)

        assertTrue("RTL 的 end 边在物理左侧，来源应在姓名左侧", source.left < name.left)
        assertEquals("来源贴到容器的物理左缘", bounds(LINE).left.value, source.left.value, Tolerance)
    }

    private fun setLine(width: Dp, primary: String, trailing: String, trailingToEnd: Boolean = false) {
        composeRule.setContent {
            ChogumiTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.width(width).testTag(LINE)) {
                        PriorityTextLine(
                            primary = {
                                Text(primary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag(PRIMARY))
                            },
                            trailing = {
                                Text(trailing, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(TRAILING))
                            },
                            gap = 4.dp,
                            trailingToEnd = trailingToEnd,
                        )
                    }
                }
            }
        }
    }

    private fun bounds(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun baseline(tag: String): Float {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        return node.getUnclippedBoundsInRoot().top.value + node.getAlignmentLinePosition(FirstBaseline).value
    }

    private companion object {
        const val PRIMARY = "primary"
        const val TRAILING = "trailing"
        const val LINE = "line"
        const val Tolerance = 1f
    }
}
