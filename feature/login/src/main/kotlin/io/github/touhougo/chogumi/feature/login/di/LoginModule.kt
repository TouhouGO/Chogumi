package io.github.touhougo.chogumi.feature.login.di

import io.github.touhougo.chogumi.feature.login.LoginViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val loginModule = module {
    viewModelOf(::LoginViewModel)
}
