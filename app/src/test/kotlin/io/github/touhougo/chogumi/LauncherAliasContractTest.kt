package io.github.touhougo.chogumi

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.AppIcon
import io.github.touhougo.chogumi.feature.settings.LauncherAliasIcons
import io.github.touhougo.chogumi.feature.settings.appIconSpecOf
import io.github.touhougo.chogumi.shell.AppActivity
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 桌面入口与设置页「应用图标」对得上：清单里每个图标一个 activity-alias，图标资源与设置页画的是同一套，
 * 装好时只开着默认的那个；选了之后在前台不换（关掉入口会让系统收掉正在用的页面），离开应用才换，换过之后桌面上也只剩一个。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LauncherAliasContractTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun aliasIcons(foreground: MutableStateFlow<Boolean> = MutableStateFlow(true)) =
        LauncherAliasIcons(context, foreground, scope)

    private fun enabledAliases() = launchers().map { it.activityInfo.name }

    private fun launchers(flags: Int = 0): List<ResolveInfo> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
        flags,
    )

    @Test
    fun `每个图标在清单里有自己的入口，指向同一个页面，图标资源与设置页的一致`() {
        val declared = launchers(PackageManager.MATCH_DISABLED_COMPONENTS).associateBy { it.activityInfo.name }
        assertEquals(AppIcon.entries.map { appIconSpecOf(it).alias }.toSet(), declared.keys)
        for (icon in AppIcon.entries) {
            val spec = appIconSpecOf(icon)
            val info = declared.getValue(spec.alias).activityInfo
            assertEquals(icon.name, AppActivity::class.java.name, info.targetActivity)
            assertEquals(icon.name, spec.launcher, info.icon)
        }
    }

    @Test
    fun `装好时桌面上只有默认图标的入口`() {
        assertEquals(listOf(appIconSpecOf(AppIcon.DEFAULT).alias), enabledAliases())
        assertEquals(AppIcon.DEFAULT, runBlocking { aliasIcons().current() })
    }

    @Test
    fun `在前台选了图标不换入口，设置页先画选中的`() = runBlocking {
        val icons = aliasIcons()

        icons.select(AppIcon.TV_NAVY)

        assertEquals(AppIcon.TV_NAVY, icons.current())
        assertEquals(listOf(appIconSpecOf(AppIcon.DEFAULT).alias), enabledAliases())
    }

    @Test
    fun `离开应用时换上选中的，桌面上只剩那一个入口`() = runBlocking {
        for (icon in AppIcon.entries.reversed()) {
            // 每轮一个新的前台状态：同一个 StateFlow 连着置 true 再置 false，收集方可能只看到 false 而不再触发
            val foreground = MutableStateFlow(true)
            val icons = aliasIcons(foreground)
            icons.select(icon)
            foreground.value = false
            val alias = appIconSpecOf(icon).alias
            awaitUntil("离开应用后换上 ${icon.name}") { enabledAliases() == listOf(alias) }
            assertEquals(icon, icons.current())
        }
    }
}
