package io.github.touhougo.chogumi.feature.home.di

import io.github.touhougo.chogumi.feature.home.HomeViewModel
import io.github.touhougo.chogumi.feature.home.widget.WidgetCalendarSource
import io.github.touhougo.chogumi.feature.home.widget.WidgetHeatSource
import io.github.touhougo.chogumi.feature.home.widget.WidgetProgressActions
import io.github.touhougo.chogumi.feature.home.widget.WidgetUpdates
import io.github.touhougo.chogumi.feature.home.widget.WidgetWorksSource
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val homeModule = module {
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(named("computationDispatcher"))) }

    // 桌面小组件：活过页面，与 App 进程同生命周期
    single { WidgetWorksSource(get(), get(), get(), get()) }
    single { WidgetHeatSource(get(), get(), get()) }
    single { WidgetCalendarSource(get(), get(), get(), get(), get(), get(), get()) }
    single { WidgetProgressActions(get(), get()) }
    single { WidgetUpdates(androidContext(), get(named("appScope")), get(), get(), get(), get()) }
}
