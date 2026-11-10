package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.Pager
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.NotificationRepository
import io.github.touhougo.chogumi.core.data.repository.PmRepository
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.model.NoticeBo
import io.github.touhougo.chogumi.core.model.PmConversationSummary
import io.github.touhougo.chogumi.core.ui.common.navigation.InboxTab
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 铃铛面板：停在哪一页，最近的通知（已读与未读）这一个区块，短信会话列表这一份分页列表。
 * 通知取不到时面板里报错 + 重试；重开面板时已显示的通知、会话留到新的一拍到来。
 */
data class NotificationUiState(
    val tab: InboxTab = InboxTab.NOTICES,
    val notices: Load<List<NoticeBo>> = Load.Pending,
    val conversations: PagedList<PmConversationSummary> = PagedList(),
)

class NotificationViewModel(
    private val notificationRepository: NotificationRepository,
    private val pmRepository: PmRepository,
) : StateViewModel<NotificationUiState>(NotificationUiState()) {

    val unreadCount: StateFlow<Int> = notificationRepository.unreadCount

    val unreadMessages: StateFlow<Int> = notificationRepository.unreadMessages

    /** 铃铛的圆点：未读通知 + 未读短信。 */
    val unreadTotal: StateFlow<Int> = notificationRepository.unreadTotal

    /** 面板的失败提示：全部标为已读、点开的那条标为已读、已有会话时重读列表失败。 */
    val failures = FailureNotices()

    private val conversations = Pager(
        scope = viewModelScope,
        read = { currentState.conversations },
        write = { transform -> updateState { it.copy(conversations = transform(it.conversations)) } },
    ) { pmRepository.conversations(it) }

    init {
        // 会话读成功了才去掉这一行的未读数；点开时不先扣，读失败这一行仍是未读
        viewModelScope.launch {
            pmRepository.conversationsRead.collect { id ->
                if (currentState.conversations.items.any { it.id == id && it.unread > 0 }) {
                    conversations.edit { rows -> rows.map { if (it.id == id) it.copy(unread = 0) else it } }
                }
            }
        }
    }

    /**
     * 点铃铛打开面板：两页都取（已显示的内容留到新的一拍到来）。有未读短信、没有未读通知时停在「短信」，其余停在「通知」。
     */
    fun open() {
        val tab = if (unreadMessages.value > 0 && unreadCount.value == 0) InboxTab.MESSAGES else InboxTab.NOTICES
        updateState { it.copy(tab = tab) }
        loadNotifications()
        if (currentState.conversations.items.isEmpty()) conversations.refresh() else conversations.reload(showIndicator = false) { failures.post(it) }
    }

    fun selectTab(tab: InboxTab) = updateState { it.copy(tab = tab) }

    fun loadNotifications() =
        launchSection("notices", failures, { loadOutcome { notificationRepository.getNotifications() } }, { it.notices }) {
            copy(notices = it)
        }

    fun retryConversations() = conversations.refresh()

    fun loadMoreConversations() = conversations.loadMore()

    /** 成功不提示：未读数归零、角标消失就是结果。做成后重读列表（[reloadAfterMark]）。 */
    fun markAllAsRead() {
        viewModelScope.launch {
            when (val result = notificationRepository.markAllAsRead()) {
                is Outcome.Success -> {
                    updateState { state ->
                        state.copy(notices = state.notices.map { notices -> notices.map { it.copy(unread = false) } })
                    }
                    reloadAfterMark()
                }
                is Outcome.Failed -> failures.post(result.failure)
            }
        }
    }

    /** 未读短信全部标为已读；成功后各行的未读数去掉，不提示。 */
    fun markAllMessagesRead() {
        viewModelScope.launch {
            when (val result = notificationRepository.markAllMessagesRead()) {
                is Outcome.Success -> conversations.edit { rows -> rows.map { it.copy(unread = 0) } }
                is Outcome.Failed -> failures.post(result.failure)
            }
        }
    }

    /**
     * 点开一条通知时把它标为已读：这一行先置灰（圆点消失），失败时恢复并提示。已读的不再请求。
     * 行留在列表里，从帖子返回时面板原样还在。
     */
    fun markRead(notice: NoticeBo) {
        if (!notice.unread) return
        setUnread(notice.id, unread = false)
        viewModelScope.launch {
            when (val result = notificationRepository.markRead(notice.id)) {
                is Outcome.Success -> reloadAfterMark()
                is Outcome.Failed -> {
                    setUnread(notice.id, unread = true)
                    failures.post(result.failure)
                }
            }
        }
    }

    /**
     * 标为已读做成后重读通知：标记之前发出、还在路上的那次读取带着旧的未读圆点，晚到会把刚标掉的画回来；
     * 同一区块的新一轮让它作废（[launchSection]），已显示的行留到新的一拍到来。
     */
    private fun reloadAfterMark() = loadNotifications()

    private fun setUnread(id: Int, unread: Boolean) = updateState { state ->
        state.copy(notices = state.notices.map { notices -> notices.map { if (it.id == id) it.copy(unread = unread) else it } })
    }

    fun refreshUnreadCount() {
        viewModelScope.launch {
            notificationRepository.refreshUnreadCount()
        }
    }
}
