package io.github.touhougo.chogumi.core.ui.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 点条一摞的行数与一个点代表几个。 */
class DotStripScaleTest {

    @Test
    fun `点条收藏再多也不超过上限行数，摞不下时一个点代表 1、2、5、10、20、50 部里够用的最小那个`() {
        assertEquals(listOf(1, 2, 50), listOf(24, 25, 1030).map { dotScaleOf(it, centered = false) })
        // 分差以中线上下交替叠：22 个点占 23 行，23 个点要 25 行
        assertEquals(listOf(1, 2, 20), listOf(22, 23, 250).map { dotScaleOf(it, centered = true) })
        listOf(0, 1, 7, 99, 1030, 20_000).forEach { tallest ->
            listOf(false, true).forEach { centered ->
                val scale = dotScaleOf(tallest, centered)
                assertTrue(stackRowsOf((tallest + scale - 1) / scale, centered) <= DOT_MAX_ROWS, "$tallest 部、居中 $centered")
            }
        }
    }
}
