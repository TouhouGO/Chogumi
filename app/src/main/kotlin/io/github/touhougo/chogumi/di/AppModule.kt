package io.github.touhougo.chogumi.di

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.touhougo.chogumi.core.foundation.AppTime
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.ui.common.navigation.ProgressEntryRequests
import io.github.touhougo.chogumi.shell.ShellViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * app 壳自己的注册：OAuth 配置（由 App 按构建参数给出）、启动用的 ShellViewModel，
 * App 在不在前台（进程级 STARTED），以及全 App 唯一的时间刻度 [AppTime]：只在前台时走。
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
    viewModelOf(::ShellViewModel)
}
