package io.github.touhougo.chogumi.feature.progress.di

import io.github.touhougo.chogumi.feature.progress.ProgressViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val progressModule = module {
    viewModel { ProgressViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(named("computationDispatcher"))) }
}
