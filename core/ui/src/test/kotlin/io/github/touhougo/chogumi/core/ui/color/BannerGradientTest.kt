package io.github.touhougo.chogumi.core.ui.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 顶部渐变曲线的形状契约。
 *
 * 这条曲线坏掉时，界面上表现为"顶部的色块"或"横幅与内容之间的接缝"，
 * 两者都很难靠肉眼回归（尤其是在不同主题色下），所以把三个几何性质直接钉住。
 */
class BannerGradientTest {

    @Test
    fun `两端精确命中否则会与状态栏或内容出现色差`() {
        assertEquals(0f, fadeAmount(0f), 1e-6f)
        assertEquals(1f, fadeAmount(1f), 1e-6f)
    }

    @Test
    fun `顶部导数大于零即顶部不得是等色平台`() {
        // 顶部变化太慢时，横幅最上面一段几乎等色，会被读成一块生硬的纯色，而不是渐变的一部分。
        val atTop = fadeAmount(0f)
        val shortlyAfter = fadeAmount(0.05f)
        assertTrue(
            shortlyAfter - atTop > 0.02f,
            "最上 5% 必须已经有肉眼可见的变化，实测仅 ${shortlyAfter - atTop}",
        )
    }

    @Test
    fun `整条曲线严格单调不得回退`() {
        var previous = fadeAmount(0f)
        for (i in 1..100) {
            val current = fadeAmount(i / 100f)
            assertTrue(current > previous, "曲线在 t=${i / 100f} 处未前进：$previous → $current")
            previous = current
        }
    }

    @Test
    fun `底部趋平以免留下可见接缝`() {
        // 落点附近的变化必须远小于顶部附近的变化
        val deltaAtTop = fadeAmount(0.10f) - fadeAmount(0f)
        val deltaAtBottom = fadeAmount(1f) - fadeAmount(0.90f)
        assertTrue(
            deltaAtBottom < deltaAtTop / 2f,
            "底部斜率应明显小于顶部：顶部 $deltaAtTop vs 底部 $deltaAtBottom",
        )
    }

    @Test
    fun `过渡要铺得开而不是挤在前段`() {
        // 这是本曲线**唯一**关于"消失点落在哪"的断言。颜色退得太快，过渡就挤在紧贴状态栏的窄带里；
        // 意图是让颜色顺着横幅铺开、接近底沿才收干净：
        //   中段 0.55~0.75 —— 已过半（否则顶部拖着一块色），但远未退完（过渡才有长度）
        //   90% 处 > 0.95  —— 收干净（否则与内容交界处留色差）
        val mid = fadeAmount(0.5f)
        assertTrue(
            mid in 0.55f..0.75f,
            "中段应仍在过渡中（0.55~0.75），实测 $mid —— 过高说明挤在前段了",
        )
        val nearBottom = fadeAmount(0.9f)
        assertTrue(
            nearBottom > 0.95f,
            "90% 处应基本落到 surface（> 0.95），实测 $nearBottom —— 否则底部会留色差",
        )
    }

    @Test
    fun `越界输入被夹紧不产生外插`() {
        assertEquals(fadeAmount(0f), fadeAmount(-1f))
        assertEquals(fadeAmount(1f), fadeAmount(2f))
    }
}
