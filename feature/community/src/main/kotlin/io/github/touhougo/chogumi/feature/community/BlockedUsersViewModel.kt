package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlocklistRepository
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.userKeyOf
import kotlinx.coroutines.launch

/**
 * 屏蔽的用户页的状态：当前账号的绝交名单；[unblocking] 是正在取消绝交的人（[blockedUserKey]），那一行的按钮这时不响应。
 */
data class BlockedUsersUiState(
    val users: Load<List<UserBo>> = Load.Pending,
    val unblocking: Set<String> = emptySet(),
)

/** 取消绝交时认人用的名字：用户名；只知道 id 的人（名单读不到资料时）用数字 id。 */
fun blockedUserKey(user: UserBo): String = userKeyOf(user.username, user.id)

/** 设置 › 屏蔽的用户：列出绝交的人（名单以 bgm.tv 为准），逐个取消绝交。取消之后，已显示的楼层与列表里这个人当场重新出现。 */
class BlockedUsersViewModel(
    private val blocklist: BlocklistRepository,
    private val users: UserRepository,
) : StateViewModel<BlockedUsersUiState>(BlockedUsersUiState()) {

    val failures = FailureNotices()

    init {
        retry()
    }

    fun retry() = launchSection("users", failures, { loadOutcome { blocklist.blockedUsers() } }, { it.users }) { copy(users = it) }

    fun unblock(user: UserBo) {
        val key = blockedUserKey(user)
        if (key in currentState.unblocking) return
        updateState { it.copy(unblocking = it.unblocking + key) }
        viewModelScope.launch {
            val outcome = users.removeUserFromBlocklist(key)
            updateState { state ->
                val remaining = if (outcome is Outcome.Success) state.users.map { list -> list.filterNot { blockedUserKey(it) == key } } else state.users
                state.copy(users = remaining, unblocking = state.unblocking - key)
            }
            if (outcome is Outcome.Failed) failures.post(outcome.failure, retry = { unblock(user) })
        }
    }
}
