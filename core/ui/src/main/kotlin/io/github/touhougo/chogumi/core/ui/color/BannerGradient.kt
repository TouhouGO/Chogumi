package io.github.touhougo.chogumi.core.ui.color

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.pow

/**
 * 停靠点数量。
 *
 * 三四个停靠点在"饱和色 → 浅色 surface"之间会显出 banding（色带），
 * 加密到 32 个即可消除。这只是一条线性渐变着色器的顶点数据，
 * 编译期算一次、绘制时仍是单次 draw call，没有逐帧成本。
 */
private const val GRADIENT_STOPS = 32

/**
 * 渐变曲线的幂次，决定颜色"留住"多久。
 *
 * 方向容易记反，这里写清楚：[fadeAmount] 的顶部斜率恰为 `p`（`f'(0) = p`），
 * 所以
 * - **p 越大 → 顶部离开纯色越快 → 颜色越淡、消失点越靠上**；
 * - **p 越小（趋近 1）→ 颜色留得越久 → 消失点越靠下、过渡越长**。
 *
 * 下限必须 > 1：p = 1 时曲线退化成直线，落点斜率为 1，会在横幅与内容之间留下接缝。
 *
 * ## 为什么是 1.8
 *
 * 资料块排在渐变**之下**，整条渐变都露在外面。p=3 时颜色在前 70% 就散尽，
 * 表现是"颜色在紧贴状态栏的一条窄带里就走完" —— 过渡被压得太短。
 * 1.8 让颜色顺着横幅铺开、接近底沿才收干净。
 */
private const val FADE_POWER = 1.8f

/**
 * 「我的」页顶部渐变：从 [base] 平滑落到 [surface]。
 *
 * ## 曲线形状是这里唯一的难点（三个约束）
 *
 * 1. **顶部导数必须 > 0**——否则最上面一截是同一种颜色。在照片横幅上无所谓，
 *    但这里是**纯色**，一块等色区域会被读成生硬的色块，而不是渐变的一部分。
 *    "顶部纯色太明显"就是这样来的：0→55% 之间 alpha 只从 1.0 降到 0.85。
 * 2. **底部导数必须趋近 0**——落到 [surface] 时若仍有斜率，会在横幅与内容交界处
 *    留下一条能看出方向的接缝。
 * 3. **两端必须精确命中** [base] 与 [surface]——否则顶部与状态栏、底部与内容
 *    各出现一道色差。
 *
 * 满足这三条的最简形式是 `fade(t) = 1 - (1-t)^p`（p > 1）：
 * 起步斜率 `p` > 0，落点斜率 `p*(1-t)^(p-1) → 0`。
 *
 * 用 [lerp] 直接混色而不是叠加 alpha：alpha 合成会与窗口底色发生关系，
 * 在不同主题下终点色不一致；直接插值到 [surface] 才能保证严丝合缝。
 */
fun bannerGradient(base: Color, surface: Color): Brush = Brush.verticalGradient(
    List(GRADIENT_STOPS) { i ->
        val t = i.toFloat() / (GRADIENT_STOPS - 1)
        lerp(base, surface, fadeAmount(t))
    }
)

/**
 * 曲线本体：返回 t 处"已朝 surface 走完的比例"，0 = 全 [base]，1 = 全 surface。
 *
 * 单独暴露是为了能被单测钉住形状——这三个性质（两端精确、顶部不平台、
 * 底部趋平）一旦破坏，界面上的表现是"色块"或"接缝"，靠肉眼回归很不可靠。
 */
internal fun fadeAmount(t: Float): Float {
    val clamped = t.coerceIn(0f, 1f)
    return 1f - (1f - clamped).pow(FADE_POWER)
}
