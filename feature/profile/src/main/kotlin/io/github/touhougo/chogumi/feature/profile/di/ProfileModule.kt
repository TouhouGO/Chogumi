package io.github.touhougo.chogumi.feature.profile.di

import io.github.touhougo.chogumi.core.data.repository.LocalCareerStatistics
import io.github.touhougo.chogumi.feature.profile.BlogDetailsViewModel
import io.github.touhougo.chogumi.feature.profile.BlogListViewModel
import io.github.touhougo.chogumi.feature.profile.CareerViewModel
import io.github.touhougo.chogumi.feature.profile.IndexDetailsViewModel
import io.github.touhougo.chogumi.feature.profile.IndexListViewModel
import io.github.touhougo.chogumi.feature.profile.IndexPickerViewModel
import io.github.touhougo.chogumi.feature.profile.ProfileViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val profileModule = module {
    viewModel { params -> ProfileViewModel(params.getOrNull(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // 自己的收藏统计取具体类（图里只登记了具体类，没有绑到 CollectionStatsDataSource）；他人的数据源由 DataModule 绑到 OtherCareerDataSource
    viewModel { params ->
        CareerViewModel(params.getOrNull(), params.get(), params.get(), get<LocalCareerStatistics>(), get(), get(), get())
    }
    viewModel { params -> BlogListViewModel(params.get(), get()) }
    viewModel { params -> BlogDetailsViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { params -> IndexListViewModel(params.get(), params.get(), get(), get()) }
    viewModel { params -> IndexDetailsViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { params -> IndexPickerViewModel(params.get(), get()) }
}
