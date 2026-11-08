package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [SegmentedItem] 的 `details` 走主内容槽位（进副行槽位会在快滑时闪退），但排出来必须与放在 M3 副行槽位一样：
 * 同样的文字，整行高度（M3 二行 72 / 三行 88 的最小高度）与副行文字的位置都不能变。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentedItemDetailsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `单行副信息与副行槽位等高同位`() = assertSameAsSupporting("副行", Alignment.CenterVertically)

    @Test
    fun `两行副信息与副行槽位等高同位`() = assertSameAsSupporting("第一行\n第二行", Alignment.Top)

    private fun assertSameAsSupporting(text: String, alignment: Alignment.Vertical) {
        composeRule.setContent {
            ChogumiTheme {
                // 360dp 固定视口只是测试设计决定（not in M3）。
                Column(Modifier.width(360.dp)) {
                    SegmentedItem(
                        index = 0,
                        count = 2,
                        onClick = {},
                        modifier = Modifier.testTag("supporting-item"),
                        verticalAlignment = alignment,
                        supportingContent = { Text(text, Modifier.testTag("supporting-text")) },
                    ) { Text("标题") }
                    SegmentedItem(
                        index = 1,
                        count = 2,
                        onClick = {},
                        modifier = Modifier.testTag("details-item"),
                        verticalAlignment = alignment,
                        details = { Text(text, Modifier.testTag("details-text")) },
                    ) { Text("标题") }
                }
            }
        }
        val supportingItem = bounds("supporting-item")
        val detailsItem = bounds("details-item")
        assertEquals("整行高度", supportingItem.bottom - supportingItem.top, detailsItem.bottom - detailsItem.top)
        val supportingText = bounds("supporting-text")
        val detailsText = bounds("details-text")
        assertEquals("副行文字距行顶", supportingText.top - supportingItem.top, detailsText.top - detailsItem.top)
        assertEquals("副行文字左缘", supportingText.left, detailsText.left)
        assertEquals("副行文字高度", supportingText.bottom - supportingText.top, detailsText.bottom - detailsText.top)
    }

    private fun bounds(tag: String): DpRect =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
}
