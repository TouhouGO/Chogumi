package io.github.touhougo.chogumi.core.ui.theme

import android.content.Context
import android.content.res.Configuration
import io.github.touhougo.chogumi.core.model.DisplayScale

/**
 * 系统给这块屏的最小宽度（dp），不受界面大小影响：取应用级的配置，Activity 自己的配置被 [displayScaleOverride] 换过。
 * 设置页算可选档位、Activity 定实际档位都读这一个，两边永远落在同一档。
 */
fun Context.systemSmallestWidthDp(): Int = applicationContext.resources.configuration.smallestScreenWidthDp

/**
 * 界面大小落到 Activity 上的唯一一处：在 `attachBaseContext` 里交给 `applyOverrideConfiguration`，
 * 跟随系统时为 null（不覆盖）。接收者是系统给的基础 Context。
 *
 * 只换 `densityDpi`。Compose 的 `LocalDensity`、对话框与弹出菜单（独立窗口，从 Activity 的资源取密度）、
 * 窗口尺寸档（窗口的 px ÷ 这个密度）都由它推出来，没有哪一处要单独缩放；系统字号（`fontScale` 为 0 即不覆盖）照常叠加。
 * 每次创建 Activity 时算一遍：系统密度、档位变了 Activity 都会重建。
 */
fun Context.displayScaleOverride(storedPercent: Int): Configuration? {
    val percent = DisplayScale.effective(storedPercent, systemSmallestWidthDp())
    if (percent == DisplayScale.SYSTEM) return null
    return Configuration().apply {
        fontScale = 0f
        densityDpi = DisplayScale.densityDpiOf(resources.configuration.densityDpi, percent)
    }
}
