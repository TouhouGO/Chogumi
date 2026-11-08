package io.github.touhougo.chogumi.feature.calendar.di

import io.github.touhougo.chogumi.feature.calendar.CalendarViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val calendarModule = module {
    viewModel { CalendarViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(named("computationDispatcher"))) }
}
