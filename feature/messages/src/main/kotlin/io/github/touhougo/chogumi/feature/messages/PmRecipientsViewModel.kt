package io.github.touhougo.chogumi.feature.messages

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.FriendsRepository
import io.github.touhougo.chogumi.core.data.repository.PmRepository
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.PmConversationSummary
import io.github.touhougo.chogumi.core.model.UserBo
import kotlinx.coroutines.launch

/**
 * 写信页选收件人：最近联系人（会话列表第一页，最近的在前）与好友（自己关注的全部的人），各自一个区块；
 * 当前账号的绝交名单跟着走，名单里的人不列（[pmRecipientsOf]）。
 */
data class PmRecipientsUiState(
    val recent: Load<List<PmConversationSummary>> = Load.Pending,
    val friends: Load<List<UserBo>> = Load.Pending,
    val blocked: BlockedUsers = BlockedUsers.NONE,
)

/** 两个区块各读一次，失败时整块报错、各自重试（区块里没有内容时才会失败，所以没有要发到提示条的失败）。 */
class PmRecipientsViewModel(
    private val pm: PmRepository,
    private val friends: FriendsRepository,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<PmRecipientsUiState>(PmRecipientsUiState()) {

    init {
        loadRecent()
        loadFriends()
        viewModelScope.launch { blockedUsers.current().collect { list -> updateState { it.copy(blocked = list) } } }
    }

    fun loadRecent() = launchSection(RECENT, failures = null, { loadOutcome { pm.conversations(0).map { it.items } } }, { it.recent }) {
        copy(recent = it)
    }

    fun loadFriends() = launchSection(FRIENDS, failures = null, { loadOutcome { friends.users() } }, { it.friends }) { copy(friends = it) }

    private companion object {
        const val RECENT = "recent"
        const val FRIENDS = "friends"
    }
}
