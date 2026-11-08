package io.github.touhougo.chogumi.feature.profile

import kotlin.test.Test
import kotlin.test.assertEquals

/** 统计页接到图表上的几处判断：收藏人数刻度、均分深浅档。 */
class CareerChartGeometryTest {

    @Test
    fun `收藏人数刻度取轴上的十的整数次幂，至多三个`() {
        // 轴从 300 × 0.8 到 80000 × 1.25：1000、10000、100000 都在轴上
        assertEquals(listOf(1_000, 10_000, 100_000), popularityTicks(300, 80_000))
        // 轴从 80 到 125000：100 到 100000 四个，取最大的三个
        assertEquals(listOf(1_000, 10_000, 100_000), popularityTicks(100, 100_000))
        // 只有 1000 一个：再加 5000（轴到 6250）
        assertEquals(listOf(1_000, 5_000), popularityTicks(1_200, 5_000))
        // 只有 1000 一个，5000 不在轴上
        assertEquals(listOf(1_000), popularityTicks(1_200, 2_000))
    }

    @Test
    fun `均分深浅分五档，六分及以下最浅、八点五分及以上最深`() {
        assertEquals(0, treemapToneOf(5.0))
        assertEquals(0, treemapToneOf(6.0))
        assertEquals(2, treemapToneOf(7.25))
        assertEquals(4, treemapToneOf(8.5))
        assertEquals(4, treemapToneOf(10.0))
    }
}
