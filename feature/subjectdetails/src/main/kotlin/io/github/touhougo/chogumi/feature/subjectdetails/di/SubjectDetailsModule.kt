package io.github.touhougo.chogumi.feature.subjectdetails.di

import io.github.touhougo.chogumi.feature.subjectdetails.EpisodeCommentsViewModel
import io.github.touhougo.chogumi.feature.subjectdetails.EpisodeViewModel
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectCollectorsViewModel
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectCommunityViewModel
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectDetailsViewModel
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectIndexesViewModel
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectRelationsViewModel
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectReviewsViewModel
import io.github.touhougo.chogumi.feature.subjectdetails.SubjectTopicsViewModel
import io.github.touhougo.chogumi.feature.subjectdetails.TopicViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

val subjectDetailsModule = module {
    viewModel { params -> SubjectDetailsViewModel(params.get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { params -> SubjectRelationsViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { params -> SubjectCommunityViewModel(params.get(), get(), get(), get()) }
    viewModel { params -> SubjectCollectorsViewModel(params.get(), get()) }
    viewModel { params -> SubjectTopicsViewModel(params.get(), get(), get()) }
    viewModel { params -> SubjectReviewsViewModel(params.get(), get(), get()) }
    viewModel { params -> SubjectIndexesViewModel(params.get(), get()) }
    // 第三个参数（从草稿进来时回复的楼）可为 null：按位置取，按类型取会拿到同为 Int 的话题 id
    viewModel { params -> TopicViewModel(params.get(), params.get(), params[2], get(), get(), get(named("computationDispatcher")), get(), get(), get()) }
    viewModel { params -> EpisodeCommentsViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { params -> EpisodeViewModel(params.get(), get()) }
}
