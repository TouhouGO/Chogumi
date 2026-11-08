package io.github.touhougo.chogumi.feature.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.AnyRes
import androidx.annotation.StringRes
import io.github.touhougo.chogumi.core.model.AppIcon
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 桌面图标的读写。当前图标是选了还没换上的那个，没有就是系统里开着的那个启动入口；不另存偏好。
 */
interface AppIcons {
    suspend fun current(): AppIcon

    /** 选一个图标：离开应用时才换上（见 [LauncherAliasIcons]）。 */
    suspend fun select(icon: AppIcon)
}

/**
 * 一个图标的启动入口与素材。[alias] 是 app 清单里 activity-alias 的全名，[launcher] 是它的 `android:icon`，
 * [preview] 是设置页画的那一层（整层 108dp 的前景位图），[label] 是读屏念的名字。
 * 清单与这里一一对应，由 app 的 `LauncherAliasContractTest` 钉住。
 */
data class AppIconSpec(
    val alias: String,
    @AnyRes val launcher: Int,
    @AnyRes val preview: Int,
    @StringRes val label: Int,
)

fun appIconSpecOf(icon: AppIcon): AppIconSpec = when (icon) {
    AppIcon.TV_NAVY -> AppIconSpec(
        "$ALIAS_PACKAGE.TvNavy", R.mipmap.ic_launcher_tv_navy, R.mipmap.ic_launcher_tv_navy_foreground, R.string.app_icon_tv_navy,
    )
    AppIcon.TV_PINK -> AppIconSpec(
        "$ALIAS_PACKAGE.TvPink", R.mipmap.ic_launcher_tv_pink, R.mipmap.ic_launcher_tv_pink_foreground, R.string.app_icon_tv_pink,
    )
    AppIcon.TV_ON_PINK -> AppIconSpec(
        "$ALIAS_PACKAGE.TvOnPink", R.mipmap.ic_launcher_tv_on_pink, R.mipmap.ic_launcher_tv_on_pink_foreground,
        R.string.app_icon_tv_on_pink,
    )
    AppIcon.SAKURA_PINK -> AppIconSpec(
        "$ALIAS_PACKAGE.SakuraPink", R.mipmap.ic_launcher_sakura_pink, R.mipmap.ic_launcher_sakura_pink_foreground,
        R.string.app_icon_sakura_pink,
    )
    AppIcon.SAKURA_RAINBOW -> AppIconSpec(
        "$ALIAS_PACKAGE.SakuraRainbow", R.mipmap.ic_launcher_sakura_rainbow, R.mipmap.ic_launcher_sakura_rainbow_foreground,
        R.string.app_icon_sakura_rainbow,
    )
}

/** 入口的类名按代码命名空间写死：调试包的 applicationId 带后缀，类名不带。 */
private const val ALIAS_PACKAGE = "io.github.touhougo.chogumi.launcher"

/**
 * 用清单里的 activity-alias 切换图标：同一时刻只开着一个入口。
 *
 * 关掉一个入口时，系统会把从它启动的页面一起收掉，正在用的应用约一秒后回到桌面。所以选了先记着，
 * [foreground] 变成 false（离开应用）时才换上；那时收掉的是后台的页面，下次打开重新启动。
 */
class LauncherAliasIcons(context: Context, foreground: Flow<Boolean>, scope: CoroutineScope) : AppIcons {
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    /** 选了还没换上的图标。 */
    @Volatile
    private var pending: AppIcon? = null

    /** 两次切换交错（先开 A、先开 B、A 关掉 B、B 关掉 A）会把入口全关掉，桌面上就没有图标了：切换一次只走一个。 */
    private val switching = Mutex()

    init {
        scope.launch { foreground.filter { !it }.collect { applyPending() } }
    }

    override suspend fun current(): AppIcon = pending ?: withContext(Dispatchers.IO) { enabled() }

    override suspend fun select(icon: AppIcon) {
        pending = icon
    }

    /** 先开新入口再关其余的，桌面上任何时刻都至少有一个入口；换上途中又选了别的，留给下一次离开。 */
    private suspend fun applyPending() = switching.withLock {
        val icon = pending ?: return@withLock
        withContext(Dispatchers.IO) {
            if (enabled() != icon) {
                setEnabled(icon, true)
                AppIcon.entries.filter { it != icon }.forEach { setEnabled(it, false) }
            }
        }
        if (pending == icon) pending = null
    }

    private fun enabled(): AppIcon = AppIcon.entries.firstOrNull(::isEnabled) ?: AppIcon.DEFAULT

    /** 没被改过的入口是清单里的默认状态：只有 [AppIcon.DEFAULT] 默认开着。 */
    private fun isEnabled(icon: AppIcon): Boolean = when (packageManager.getComponentEnabledSetting(componentOf(icon))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == AppIcon.DEFAULT
        else -> false
    }

    private fun setEnabled(icon: AppIcon, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(componentOf(icon), state, PackageManager.DONT_KILL_APP)
    }

    private fun componentOf(icon: AppIcon) = ComponentName(packageName, appIconSpecOf(icon).alias)
}
