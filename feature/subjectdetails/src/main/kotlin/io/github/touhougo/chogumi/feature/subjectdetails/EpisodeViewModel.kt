package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.SubjectCommunityRepository
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.EpisodeThreadBo

/** 单集讨论页的状态：[head] 是这一集与它的作品；评论楼层由 [EpisodeCommentsViewModel] 管（与单集面板同一份）。 */
data class EpisodeUiState(val head: Load<EpisodeThreadBo> = Load.Pending)

/** 单集讨论页的头部：进页取一次；没取到时整页报错，重试再取。 */
class EpisodeViewModel(
    private val episodeId: Int,
    private val repository: SubjectCommunityRepository,
) : StateViewModel<EpisodeUiState>(EpisodeUiState()) {

    init {
        load()
    }

    fun retry() = load()

    // 头部只在没取到时重取，失败落在页面上，不另发提示
    private fun load() = launchSection("head", failures = null, { loadOnce { repository.getEpisode(episodeId) } }, { it.head }) {
        copy(head = it)
    }
}
