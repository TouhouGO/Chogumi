package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.GroupMuteRepository
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.GroupSummaryBo

/** 屏蔽的小组页的状态：本机屏蔽名单，最近屏蔽的在前。 */
data class MutedGroupsUiState(val groups: Load<List<GroupSummaryBo>> = Load.Pending)

/** 设置 › 屏蔽的小组：列出本机屏蔽的小组，逐个取消。名单跟着本机表走，社区 tab 那边同时恢复显示。 */
class MutedGroupsViewModel(
    private val mutes: GroupMuteRepository,
) : StateViewModel<MutedGroupsUiState>(MutedGroupsUiState()) {

    val failures = FailureNotices()

    init {
        retry()
    }

    fun retry() = launchSection("groups", failures, { mutes.observeAll() }, { it.groups }) { copy(groups = it) }

    fun unmute(group: GroupSummaryBo) {
        viewModelScope.launchWrite(failures, retry = { unmute(group) }) { mutes.unmute(group.name) }
    }
}
