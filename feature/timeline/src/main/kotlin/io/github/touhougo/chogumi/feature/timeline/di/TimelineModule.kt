package io.github.touhougo.chogumi.feature.timeline.di

import io.github.touhougo.chogumi.feature.timeline.TimelineViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val timelineModule = module {
    viewModelOf(::TimelineViewModel)
}
