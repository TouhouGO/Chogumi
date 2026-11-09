package io.github.touhougo.chogumi.feature.settings.di

import io.github.touhougo.chogumi.feature.settings.AppIcons
import io.github.touhougo.chogumi.feature.settings.CoilImageCache
import io.github.touhougo.chogumi.feature.settings.ContentSettingsViewModel
import io.github.touhougo.chogumi.feature.settings.ImageCache
import io.github.touhougo.chogumi.feature.settings.LauncherAliasIcons
import io.github.touhougo.chogumi.feature.settings.NetworkSettingsViewModel
import io.github.touhougo.chogumi.feature.settings.PreferenceSettingsViewModel
import io.github.touhougo.chogumi.feature.settings.ProfileSettingsViewModel
import io.github.touhougo.chogumi.feature.settings.SettingsViewModel
import io.github.touhougo.chogumi.feature.settings.UpdateSettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

val settingsModule = module {
    // 离开应用时才换图标：前台状态由 app 壳给出（进程级 STARTED）
    single<AppIcons> { LauncherAliasIcons(androidContext(), get(named("appForeground")), get(named("appScope"))) }
    single<ImageCache> { CoilImageCache(androidContext()) }
    viewModelOf(::SettingsViewModel)
    viewModelOf(::PreferenceSettingsViewModel)
    viewModelOf(::ContentSettingsViewModel)
    viewModelOf(::ProfileSettingsViewModel)
    viewModelOf(::UpdateSettingsViewModel)
    viewModelOf(::NetworkSettingsViewModel)
}
