package io.github.touhougo.chogumi.feature.community.di

import io.github.touhougo.chogumi.feature.community.AllGroupsViewModel
import io.github.touhougo.chogumi.feature.community.BlockedUsersViewModel
import io.github.touhougo.chogumi.feature.community.CommunityViewModel
import io.github.touhougo.chogumi.feature.community.GroupViewModel
import io.github.touhougo.chogumi.feature.community.MutedGroupsViewModel
import io.github.touhougo.chogumi.feature.community.MyTopicsViewModel
import io.github.touhougo.chogumi.feature.community.NotificationViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val communityModule = module {
    viewModelOf(::CommunityViewModel)
    viewModelOf(::NotificationViewModel)
    viewModelOf(::AllGroupsViewModel)
    viewModel { params -> GroupViewModel(params.get(), get(), get(), get(), get(), get()) }
    viewModelOf(::MyTopicsViewModel)
    viewModelOf(::MutedGroupsViewModel)
    viewModelOf(::BlockedUsersViewModel)
}
