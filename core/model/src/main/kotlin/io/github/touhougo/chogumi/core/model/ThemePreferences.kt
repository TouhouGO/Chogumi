package io.github.touhougo.chogumi.core.model

/**
 * 配色从哪里来，一次只用一种：[SEED] 是种子色按 M3 生成，[PRESET] 是手工定的配色方案（[ThemePreset]），[SYSTEM] 是系统壁纸取色。
 * 与明暗主题独立；换来源不丢另外两种的选择。
 */
enum class ThemeColorMode { SEED, PRESET, SYSTEM }

/**
 * 内置的配色方案：每套的浅色与深色角色色都是手工定的（core:ui `presetColorScheme`），不由种子色生成。
 * 背景接近中性、只带一点色调，主色、次色、点缀色各取不同色系。存储按名字，改顺序不影响已存的选择。
 */
enum class ThemePreset { MONO, INK, CINNABAR, FUCHSIA, VIOLET, INDIGO, AZURE, PEACOCK, PINE, MOCHA }

/**
 * 主题设置：配色来源与各来源的选择，加上 [glass]（玻璃效果：浮在内容上的控件画成液态玻璃，默认关；系统低于 Android 12 时不起作用，
 * 判定在 core:ui 主题里）。
 */
data class ThemePreferences(
    val mode: ThemeColorMode = ThemeColorMode.SEED,
    val seedArgb: Long = DEFAULT_SEED,
    val preset: ThemePreset = ThemePreset.INK,
    val glass: Boolean = false,
) {
    companion object {
        const val DEFAULT_SEED = 0xFFF09199L
    }
}
