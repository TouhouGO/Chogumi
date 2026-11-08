package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.IndexEntryBo
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 目录详情的序号列：骨架行与真实行按同一个最大序号实测，数据到了以后封面和标题不横向移动。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IndexRankColumnTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `六位数目录里骨架序号列与真实序号列一样宽`() {
        composeRule.setContent {
            ChogumiTheme {
                Column(Modifier.width(360.dp)) {
                    EntryRowSkeleton(index = 0, count = 2, maxRank = 123456)
                    EntryRow(entry = IndexEntryBo(id = 1, targetId = 1, name = "条目"), rank = 1, segmentIndex = 0, segmentCount = 2, maxRank = 123456, onClick = {})
                }
            }
        }
        val columns = composeRule.onAllNodesWithTag(IndexRankColumnTag, useUnmergedTree = true)
        val widths = (0 until columns.fetchSemanticsNodes().size).map { columns[it].getUnclippedBoundsInRoot().width }
        assertEquals("骨架与真实各有一个序号列", 2, widths.size)
        assertEquals("骨架序号列与真实序号列等宽", widths[0].value, widths[1].value, 0.5f)
        assertTrue("六位数的序号列比最窄的一档宽", widths[0] > 20.dp)
    }
}
