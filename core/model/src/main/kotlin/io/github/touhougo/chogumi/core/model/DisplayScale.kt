package io.github.touhougo.chogumi.core.model

import kotlin.math.roundToInt

/**
 * 界面大小（设置 →「界面大小」）：应用的密度 = 系统密度 × 档位 / 100，存的是这个百分比。
 *
 * 换的是整个应用的 dp：页面、对话框、菜单、窗口尺寸档（宽度按 px ÷ 密度算）都跟着走，没有哪一处单独缩放。
 * [SYSTEM]（100）就是系统密度本身，也是缺省；系统「字体大小」照常叠加在上面，这里不碰字号。
 * 按比例而不按绝对 dp 存：同一个设置在折叠屏内外屏、换机恢复后都还是「比系统大一点 / 小一点」。
 *
 * 设计决策（not in M3）：80%–120%、每档 5%；缩完的最小宽度低于 [MIN_SMALLEST_WIDTH_DP] 的放大档在这块屏上不给选
 * （320dp 是 Android 手机的最小宽度下限，再窄排版就不再成立）。
 */
object DisplayScale {
    /** 跟随系统：不改密度。 */
    const val SYSTEM: Int = 100

    /** 全部档位，从小到大；设置页的滑块与读盘时的收敛都从这里取。 */
    val OPTIONS: List<Int> = (80..120 step 5).toList()

    /** 缩完的最小宽度不低于这个值。 */
    const val MIN_SMALLEST_WIDTH_DP: Int = 320

    /** 在系统最小宽度为 [systemSmallestWidthDp] 的屏上可选的档位：[SYSTEM] 永远可选。 */
    fun optionsFor(systemSmallestWidthDp: Int): List<Int> =
        OPTIONS.filter { it <= SYSTEM || smallestWidthDpOf(systemSmallestWidthDp, it) >= MIN_SMALLEST_WIDTH_DP }

    /**
     * 存下的值 → 这块屏上实际用的档位：不认识的值（缺省、改过档位留下的旧值）是 [SYSTEM]；
     * 这块屏放不下的放大档退到能放下的最大一档（换到小屏、从备份恢复时）。
     */
    fun effective(stored: Int?, systemSmallestWidthDp: Int): Int {
        if (stored == null || stored !in OPTIONS) return SYSTEM
        return optionsFor(systemSmallestWidthDp).last { it <= stored }
    }

    /** 这一档下的密度（dpi）。 */
    fun densityDpiOf(systemDensityDpi: Int, percent: Int): Int = (systemDensityDpi * percent / 100f).roundToInt()

    /** 这一档下屏幕的最小宽度（dp）：密度放大，同样的 px 折成的 dp 变少。 */
    fun smallestWidthDpOf(systemSmallestWidthDp: Int, percent: Int): Int =
        (systemSmallestWidthDp * 100f / percent).roundToInt()
}
