package io.github.touhougo.chogumi.feature.explore.di

import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.feature.explore.ExploreViewModel
import io.github.touhougo.chogumi.feature.explore.SearchViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val exploreModule = module {
    viewModel { ExploreViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { params ->
        SearchViewModel(get(), get(), get(), get(), get(), get(), params.getOrNull<SearchCriteria>(), params.getOrNull<String>(), params.getOrNull<Boolean>() ?: false)
    }
}
