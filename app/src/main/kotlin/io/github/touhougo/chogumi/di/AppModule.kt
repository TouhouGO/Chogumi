package io.github.touhougo.chogumi.di

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.touhougo.chogumi.BuildConfig
import io.github.touhougo.chogumi.core.data.update.AppUpdates
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.ui.common.navigation.ProgressEntryRequests
import io.github.touhougo.chogumi.shell.AppUpdateViewModel
import io.github.touhougo.chogumi.shell.ShellViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * app 壳自己的注册：OAuth 配置（由 App 按构建参数给出）、启动用的 ShellViewModel，
 * App 在不在前台（进程级 STARTED），全 App 唯一的时间刻度 [AppTime]：只在前台时走，以及检查更新 [AppUpdates]：
 * 要装着的这一版的 versionCode，只有 app 的 BuildConfig 知道，所以登记在这里。
 */
fun appModule(authConfig: AuthConfig) = module {
    single { authConfig }
    // 应用级计算上下文：数据映射、统计与封面取色都使用同一份注入。
    single<CoroutineDispatcher>(named("computationDispatcher")) { Dispatchers.Default }
    single<Flow<Boolean>>(named("appForeground")) {
        ProcessLifecycleOwner.get().lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }
    }
    single { AppTime.ticking(get(), get(named("appForeground")), get(named("appScope"))) }
    // 首页进进度页的入口请求：导航状态，不是持久数据
    single { ProgressEntryRequests() }
    single {
        AppUpdates(
            api = get(),
            preferences = get(),
            clock = get(),
            installedVersionCode = BuildConfig.VERSION_CODE,
            dir = File(androidContext().cacheDir, UPDATE_DOWNLOAD_DIR),
            scope = get(named("appScope")),
            io = Dispatchers.IO,
        )
    }
    viewModelOf(::ShellViewModel)
    viewModelOf(::AppUpdateViewModel)
}

/** 应用内更新下好的安装包放在缓存目录下的这里，与 res/xml/update_paths.xml 是同一个。 */
private const val UPDATE_DOWNLOAD_DIR = "updates"
