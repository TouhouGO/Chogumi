package io.github.touhougo.chogumi.feature.monodetails.di

import io.github.touhougo.chogumi.feature.monodetails.MonoDetailsViewModel
import io.github.touhougo.chogumi.feature.monodetails.MonoRelationsListViewModel
import io.github.touhougo.chogumi.feature.monodetails.MonoWorksViewModel
import io.github.touhougo.chogumi.feature.monodetails.PersonCharactersViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val monoDetailsModule = module {
    viewModel { params -> MonoDetailsViewModel(params.get(), params.get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { params -> MonoWorksViewModel(params.get(), params.get(), get()) }
    viewModel { params -> PersonCharactersViewModel(params.get(), get()) }
    viewModel { params -> MonoRelationsListViewModel(params.get(), params.get(), get()) }
}
