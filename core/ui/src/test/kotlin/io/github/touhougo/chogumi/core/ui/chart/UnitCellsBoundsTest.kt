package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 单位格放进比它矮的有界容器时，不居中后顶出容器上沿。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class UnitCellsBoundsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `放进矮容器时不越过容器上沿`() {
        composeRule.setContent {
            ChogumiTheme {
                Box(Modifier.width(200.dp).height(20.dp)) {
                    UnitCells(
                        groups = listOf(UnitGroup(CollectionType.DONE, 100, "看过")),
                        columns = 10,
                        description = "单位格",
                    )
                }
            }
        }
        val chart = composeRule.onNode(hasContentDescription("单位格")).getUnclippedBoundsInRoot()
        assertTrue("图的上沿 ${chart.top} 不在容器上沿之上", chart.top >= 0.dp)
        assertTrue("图的下沿 ${chart.bottom} 不超过容器下沿", chart.bottom <= 20.dp)
    }

    @Test
    fun `高度恰好够排的行数就全画，矮一行就少画一行`() {
        // 10 行、格 10px、行距 2px：满高 = 10 * 10 + 9 * 2 = 118px
        assertEquals(10, visibleUnitRows(118f, 10f, 2f))
        assertEquals(10, visibleUnitRows(117.6f, 10f, 2f))
        assertEquals(9, visibleUnitRows(105f, 10f, 2f))
        assertEquals(1, visibleUnitRows(10f, 10f, 2f))
        assertEquals(0, visibleUnitRows(5f, 10f, 2f))
    }
}
