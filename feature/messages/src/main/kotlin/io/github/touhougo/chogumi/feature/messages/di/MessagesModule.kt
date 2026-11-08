package io.github.touhougo.chogumi.feature.messages.di

import io.github.touhougo.chogumi.feature.messages.PmConversationViewModel
import io.github.touhougo.chogumi.feature.messages.PmRecipientsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val messagesModule = module {
    viewModel { params -> PmConversationViewModel(params.get(), get(), get(), get(), get()) }
    viewModelOf(::PmRecipientsViewModel)
}
