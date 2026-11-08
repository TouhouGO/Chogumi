package io.github.touhougo.chogumi.core.model.statistics

import kotlin.test.Test
import kotlin.test.assertEquals

class PercentTest {

    @Test
    fun `百分比四舍五入且分母为零记零`() {
        assertEquals(33, percentOf(1, 3))
        assertEquals(67, percentOf(2, 3))
        assertEquals(0, percentOf(5, 0))
    }
}
