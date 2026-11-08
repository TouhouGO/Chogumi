package io.github.touhougo.chogumi.core.model

import kotlin.math.roundToInt

/** 屏幕的一个显示模式：系统 `Display.Mode` 里刷新率判定要用的几项。宽高是面板的物理像素，不随转屏交换。 */
data class DisplayModeSpec(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/** 一块屏此刻的模式与它报的全部模式（系统 `Display.mode` 与 `Display.supportedModes`）。 */
data class ScreenModes(val current: DisplayModeSpec, val supported: List<DisplayModeSpec>)

/**
 * 刷新率（设置 →「刷新率」）：存的是整数 Hz，[SYSTEM]（0）是跟随系统、不向系统要任何模式，也是缺省。
 *
 * 应用只能从这块屏自己报的模式里请求一个（窗口的 `preferredDisplayModeId`），系统的省电、温控与厂商设置仍可以压下它。
 * 所以档位从屏幕来，不写死一张 30–165 的表：只列当前分辨率下屏幕有的刷新率，换分辨率不归这里管。
 * 只有一种刷新率的屏没有可选的，设置页不显示这一行。
 *
 * 存原值不收敛：换到别的屏（折叠屏内外屏、从备份恢复到另一台手机）时，这块屏没有的档位按 [SYSTEM] 处理，回到有的屏上选择还在。
 */
object RefreshRate {
    /** 跟随系统：不请求模式。 */
    const val SYSTEM: Int = 0

    /** 这块屏上可选的档位：[SYSTEM] 在前，后面是当前分辨率下的刷新率（取整 Hz，从低到高）；只有一种刷新率时只有 [SYSTEM]。 */
    fun optionsFor(screen: ScreenModes): List<Int> {
        val rates = sameResolution(screen).map { it.refreshRate.roundToInt() }.distinct().sorted()
        return if (rates.size < 2) listOf(SYSTEM) else listOf(SYSTEM) + rates
    }

    /** 存下的档位 → 这块屏上实际用的档位：这块屏没有的（含缺省、负数）是 [SYSTEM]。设置页的选中项与请求的模式都从这里来。 */
    fun effective(stored: Int, screen: ScreenModes): Int = stored.takeIf { it in optionsFor(screen) } ?: SYSTEM

    /**
     * 存下的档位 → 要向系统请求的模式 id；0 是不请求（窗口 `preferredDisplayModeId` 的「没有偏好」）。
     * 取整后同一档有几个模式（59.94 与 60）时取刷新率高的那个。
     */
    fun modeIdFor(stored: Int, screen: ScreenModes): Int {
        val rate = effective(stored, screen)
        if (rate == SYSTEM) return 0
        return sameResolution(screen).filter { it.refreshRate.roundToInt() == rate }.maxBy { it.refreshRate }.id
    }

    private fun sameResolution(screen: ScreenModes): List<DisplayModeSpec> =
        screen.supported.filter { it.width == screen.current.width && it.height == screen.current.height }
}
