package io.github.touhougo.chogumi.core.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import io.github.touhougo.chogumi.core.model.ThemeColorMode
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.model.ThemePreset
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * App 主题：配色、动效、形状与字阶的唯一入口。
 *
 * 配色只有一条路：先定**来源**（[ThemeColorSource]：种子色、配色方案或系统壁纸），再经 [appColorScheme] 得到整套角色色；
 * 动效只有一套，系统关掉动画时一切瞬时（[appMotionFor]），方案交给 Material，整套取值放进 [LocalAppMotion]；形状与字阶交给 Material。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChogumiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themePreferences: ThemePreferences = ThemePreferences(),
    content: @Composable () -> Unit,
) {
    val source = themeColorSource(themePreferences, darkTheme)
    val colors = remember(source, darkTheme) { appColorScheme(source, darkTheme) }
    val systemAnimationsOff by rememberSystemAnimationsOff()
    val motion = appMotionFor(systemAnimationsOff)
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = motion.scheme) {
        CompositionLocalProvider(LocalAppMotion provides motion, content = content)
    }
}

/**
 * 配色的来源。[Seed] 是用户选的种子色，按 M3 生成；[Preset] 是手工定的配色方案（[presetColorScheme]），不经 M3 生成；
 * [SystemPrimary] 是「系统取色」时系统壁纸配色的 primary——它已经是成品色调，所以按 primary 用，不当种子再取一次调。
 */
sealed interface ThemeColorSource {
    data class Seed(val color: Color) : ThemeColorSource
    data class Preset(val preset: ThemePreset) : ThemeColorSource
    data class SystemPrimary(val color: Color) : ThemeColorSource
}

@Composable
private fun themeColorSource(preferences: ThemePreferences, darkTheme: Boolean): ThemeColorSource =
    themeColorSource(LocalContext.current, preferences, darkTheme)

/**
 * 设置里的来源；选了「系统取色」而系统不支持壁纸取色（Android 12 以下）时退回种子色。
 * App 的主题与桌面小组件都从这里取来源。
 */
fun themeColorSource(context: Context, preferences: ThemePreferences, darkTheme: Boolean): ThemeColorSource =
    when (preferences.mode) {
        ThemeColorMode.PRESET -> ThemeColorSource.Preset(preferences.preset)
        ThemeColorMode.SYSTEM if Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val system = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            ThemeColorSource.SystemPrimary(system.primary)
        }
        else -> ThemeColorSource.Seed(Color(preferences.seedArgb))
    }

/** 来源 → App 配色；对比度测试（含 core:reader 的 BBCode 具名色测试）也从这里取底色，与真机同源。 */
fun appColorScheme(source: ThemeColorSource, isDark: Boolean): ColorScheme = when (source) {
    is ThemeColorSource.Seed ->
        dynamicColorScheme(seedColor = source.color, isDark = isDark, style = PaletteStyle.TonalSpot, specVersion = COLOR_SPEC)
    is ThemeColorSource.Preset -> presetColorScheme(source.preset, isDark)
    is ThemeColorSource.SystemPrimary ->
        dynamicColorScheme(primary = source.color, isDark = isDark, style = PaletteStyle.TonalSpot, specVersion = COLOR_SPEC)
}

/**
 * 从封面取色的配色（M3 content-based color）：源色来自封面（`rememberCoverScheme`），与 App 配色同一套规范版本，
 * 风格取 Content——色调贴着源图走，而不是把源色当作种子再调和（color-schemes.md「Dynamic color schemes」）。
 */
internal fun contentColorScheme(source: Color, isDark: Boolean): ColorScheme =
    dynamicColorScheme(seedColor = source, isDark = isDark, style = PaletteStyle.Content, specVersion = COLOR_SPEC)

internal val COLOR_SPEC = ColorSpec.SpecVersion.SPEC_2025

/** 主题模式 → 这一帧用不用深色：固定了明暗的按 [ThemeMode.fixedDark]，跟随系统时问系统。App 与小组件的选作品页都经这里。 */
@Composable
fun darkThemeOf(mode: ThemeMode): Boolean = mode.fixedDark ?: isSystemInDarkTheme()

/**
 * 当前主题是否为深色（**全仓唯一**明暗判定）。
 *
 * 取自 [MaterialTheme] 的 surface 亮度，而不是 `isSystemInDarkTheme()`：
 * App 有自己的主题设置（跟随系统 / 浅 / 深），用户在浅色系统下强制深色时，
 * 系统判断会挑错档。surface 是所有内容落底的基准色，其亮度即「这个主题整体是亮是暗」
 * 的可靠信号，BBCode 具名色等需要随主题分档的地方一律用它。
 */
@Composable
fun isDarkThemeActive(): Boolean = MaterialTheme.colorScheme.isDark()

/** [isDarkThemeActive] 的判定本身：给不在组合里、手上只有配色的纯函数用（如收藏状态色）。 */
fun ColorScheme.isDark(): Boolean = surface.luminance() < 0.5f

/**
 * 浮在容器上的一块（进度页的筛选格、生涯统计的大数字格、获得焦点的输入框）的底色：比它所在的面板、卡片、页面亮一档。
 * 浅色下取最亮的 `surfaceContainerLowest`（近白）；深色下 M3 的容器越高越亮，`surfaceContainerLowest` 反而是最暗的一档、
 * 接近纯黑，放在面板上就是一块黑，所以取 `surfaceContainerHigh`。浅深各取哪一档是设计决策（not in M3）。
 * `surfaceContainerLowest` 只在这里读（门禁 check_surface_roles.py）。
 */
val ColorScheme.raisedContainer: Color get() = if (isDark()) surfaceContainerHigh else surfaceContainerLowest
