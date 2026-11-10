package io.github.touhougo.chogumi.feature.home.widget

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle as GlanceTextStyle
import androidx.glance.unit.ColorProvider
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.RING_LEVELS
import io.github.touhougo.chogumi.core.model.ThemeMode
import io.github.touhougo.chogumi.core.model.ThemePreferences
import io.github.touhougo.chogumi.core.ui.chart.inkOn
import io.github.touhougo.chogumi.core.ui.chart.ordinalTones
import io.github.touhougo.chogumi.core.ui.common.SystemNotices
import io.github.touhougo.chogumi.core.ui.theme.appColorScheme
import io.github.touhougo.chogumi.core.ui.theme.themeColorSource
import kotlinx.coroutines.flow.first
import androidx.compose.ui.text.TextStyle as ComposeTextStyle

/**
 * 小组件的配色：App 设置里的关键色与明暗（[ThemePreferences]、[ThemeMode]），经 App 主题同一个 [appColorScheme] 生成，
 * 所以桌面上的颜色跟 App 里选的配色一致。每个颜色带白天与夜间两份，跟随系统时由桌面按系统明暗挑；
 * App 里固定了浅色或深色时两份相同。
 *
 * 底色取 surfaceContainer（与原型一致，设计决定 not in M3：M3 的小组件底色是 secondaryContainer 推出来的）。
 */
internal class WidgetPalette(private val light: ColorScheme, private val dark: ColorScheme) {
    private fun pick(role: ColorScheme.() -> Color) = ColorProvider(day = light.role(), night = dark.role())

    val background = pick { surfaceContainer }
    val onSurface = pick { onSurface }
    val onSurfaceVariant = pick { onSurfaceVariant }
    val primary = pick { primary }
    val onPrimary = pick { onPrimary }
    val track = pick { surfaceContainerHighest }
    val tonal = pick { secondaryContainer }
    val onTonal = pick { onSecondaryContainer }
    val error = pick { error }

    /** 热力图里还没到的日子：只画轮廓（与生涯年轮同一个颜色）。 */
    val pending = pick { outlineVariant }

    /** 热力图的格子：0 档是空格（surfaceContainerHighest），1 档起取生涯年轮同一组 [ordinalTones]（[RING_LEVELS] 档）。 */
    val heat: List<ColorProvider> = listOf(track) + ordinalTones(light, RING_LEVELS).zip(ordinalTones(dark, RING_LEVELS)) { day, night ->
        ColorProvider(day = day, night = night)
    }

    /** 写在热力图格子上的字（这个月的日期）：0 档同次要文字，有数的按格子颜色取深字或浅字（[inkOn]）。 */
    val heatInk: List<ColorProvider> = listOf(onSurfaceVariant) +
        ordinalTones(light, RING_LEVELS).zip(ordinalTones(dark, RING_LEVELS)) { day, night -> ColorProvider(day = inkOn(day), night = inkOn(night)) }

    companion object {
        suspend fun of(context: Context, preferences: PreferencesRepository): WidgetPalette {
            // 偏好读不出来时用默认配色：小组件照样能画，不为配色留白
            val theme = outcomeOf { preferences.themePreferences.first() }.valueOrNull() ?: ThemePreferences()
            val mode = outcomeOf { preferences.themeMode.first() }.valueOrNull() ?: ThemeMode.FOLLOW_SYSTEM
            fun scheme(dark: Boolean) = appColorScheme(themeColorSource(context, theme, dark), dark)
            // 固定了明暗时两份相同；跟随系统时白天、夜间各一份，由桌面按系统明暗挑
            return mode.fixedDark?.let { dark -> scheme(dark).let { WidgetPalette(it, it) } }
                ?: WidgetPalette(scheme(false), scheme(true))
        }
    }
}

internal val LocalWidgetPalette = staticCompositionLocalOf<WidgetPalette> { error("小组件内容要在 WidgetPalette 里画") }

/**
 * 小组件的字阶：取 App 用的 M3 字阶（`MaterialExpressiveTheme` 没有自定义字阶，就是 [Typography] 的默认值）的字号与字重，
 * RemoteViews 只认系统字体与三档字重，字重就近取。
 */
internal object WidgetType {
    private val scale = Typography()
    val title = scale.titleSmall
    val body = scale.bodyMedium
    val meta = scale.bodySmall
    val label = scale.labelLarge
    val tiny = scale.labelSmall
    val number = scale.headlineMedium
}

/** 一个 M3 字阶样式在小组件上的写法。 */
internal fun widgetText(style: ComposeTextStyle, color: ColorProvider): GlanceTextStyle =
    GlanceTextStyle(color, style.fontSize, glanceWeightOf(style.fontWeight))

private fun glanceWeightOf(weight: ComposeFontWeight?): FontWeight = when {
    weight == null || weight < ComposeFontWeight.Medium -> FontWeight.Normal
    weight < ComposeFontWeight.Bold -> FontWeight.Medium
    else -> FontWeight.Bold
}

/** 小组件最外层：铺满、底色、系统给小组件的圆角（Android 12 起桌面统一的那个值）。 */
@Composable
internal fun widgetFrame(): GlanceModifier =
    GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .background(LocalWidgetPalette.current.background)
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)

/** 小组件里一行、一块、一个按钮的圆角（系统给小组件内部元素的那个值；Glance 在 Android 12 以下不裁圆角）。 */
internal fun GlanceModifier.innerCorner(): GlanceModifier = cornerRadius(android.R.dimen.system_app_widget_inner_radius)

/**
 * 系统给小组件内部元素的圆角，画进位图用（封面）：Android 12 起读系统的值，以前没有这个值，取 M3 的小圆角 8dp。
 */
internal fun widgetInnerRadius(context: Context): Dp =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.resources.getDimension(android.R.dimen.system_app_widget_inner_radius) / context.resources.displayMetrics.density).dp
    } else {
        8.dp
    }

/** 打开 App（[SystemNotices.launchIntent]：[url] 为 null 时只打开 App）。 */
internal fun openApp(context: Context, url: String? = null): Action = actionStartActivity(SystemNotices.launchIntent(context, url))

/** 打开一部作品的作品页。 */
internal fun openSubject(context: Context, subjectId: Int): Action = openApp(context, BgmWeb.subject(subjectId))
