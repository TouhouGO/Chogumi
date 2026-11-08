package io.github.touhougo.chogumi.feature.community

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFilter
import io.github.touhougo.chogumi.core.data.repository.DraftRepository
import io.github.touhougo.chogumi.core.data.repository.postDrafts
import io.github.touhougo.chogumi.core.data.repository.GroupMuteRepository
import io.github.touhougo.chogumi.core.data.repository.GroupRepository
import io.github.touhougo.chogumi.core.data.util.loadOnce
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.reader.ComposerActions
import io.github.touhougo.chogumi.core.reader.ComposerState
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.common.paging.Pager
import kotlinx.coroutines.launch

/**
 * 小组页的状态：头部（简介、成员数、是否已加入）、这个小组的帖子、是否置顶、是否屏蔽（都是本机名单）、发帖面板。
 * [membershipPending]：加入或退出正在进行，按钮这时不响应。
 */
data class GroupUiState(
    val group: Load<GroupBo> = Load.Pending,
    val topics: PagedList<CommunityTopicBo> = PagedList(),
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val membershipPending: Boolean = false,
    val composer: ComposerState<Unit> = ComposerState(),
)

/**
 * 一个小组的页面。头部与帖子各取各的、同时发出；帖子按最后回复排，按 offset 翻页。
 * 发帖走编辑器面板（[ComposerActions]），发出后帖子列表重拉一次，新帖已经在里面。
 */
class GroupViewModel(
    private val name: String,
    private val repository: GroupRepository,
    private val mutes: GroupMuteRepository,
    drafts: DraftRepository,
    private val clock: AppClock,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<GroupUiState>(GroupUiState()) {

    /** 本页的失败提示：下拉刷新失败（内容还在屏幕上）。 */
    val failures = FailureNotices()

    /**
     * 成员写让写前未落定的头部读失效；成功时再让写入中启动、可能已拿到旧成员状态的读失效。
     * 读只在启动和落地时的版本相同时发布，写失败不阻止之后的新读。
     */
    private var membershipWriteVersion = 0L

    private val pager = Pager(
        scope = viewModelScope,
        read = { currentState.topics },
        write = { f -> updateState { it.copy(topics = f(it.topics)) } },
        // 按最后回复排的流在翻页途中会变：刚有人回复的帖子顶到前面，下一页里又出现一次。只留先到的那一份；绝交用户发的不显示
        present = { topics -> blocked.visible(topics.distinctBy { it.key }) { it.author?.id } },
    ) { offset -> outcomeOf { blocked.alongside { repository.getTopics(name, offset) } } }

    /** 绝交名单（[BlockedUsersFilter]）：在别处绝交、取消后，已取到的帖子当场按新名单重算。 */
    private val blocked: BlockedUsersFilter = BlockedUsersFilter(viewModelScope, blockedUsers) { pager.represent() }

    /** 发帖面板：标题 + 正文，发出后重拉帖子；新帖的 id 交给界面打开。没发出去的字按小组留成草稿。 */
    val composer = ComposerActions<Unit, Int>(
        scope = viewModelScope,
        state = { currentState.composer },
        update = { transform -> updateState { it.copy(composer = transform(it.composer)) } },
        submit = { _, draft, token -> repository.createTopic(name, draft.title, draft.text, token) },
        afterSend = { refreshTopics() },
        afterUnsure = { refreshTopics() },
        drafts = drafts.postDrafts(PostDraftKind.GROUP_TOPIC, key = name, clock),
    )

    init {
        loadGroup()
        pager.refresh()
        follow(failures, { repository.pinned }) { names -> updateState { it.copy(pinned = name in names) } }
        follow(failures, { mutes.observeAll() }) { groups -> updateState { state -> state.copy(muted = groups.any { it.name == name }) } }
    }

    /** 整页报错后的重试：头部与帖子一起重取。 */
    fun retry() {
        loadGroup()
        pager.refresh()
    }

    /** 下拉刷新：头部与帖子都重取，已显示的留着；失败只发提示。 */
    fun refresh() {
        loadGroup()
        refreshTopics()
    }

    /**
     * 回到这一页时重取头部（在网页上加入、退出之后，「已加入」跟着变）。头部还没到时（第一次进页）什么都不做；
     * 已显示的头部留着，取不到也不报错，下次回来再取。
     */
    fun refreshGroup() {
        if (currentState.group is Load.Ready) loadGroup(quiet = true)
    }

    fun loadMore() = pager.loadMore()

    fun setPinned(pinned: Boolean) {
        viewModelScope.launchWrite(failures, retry = { setPinned(pinned) }) { repository.setPinned(name, pinned) }
    }

    /** 屏蔽或取消屏蔽这个小组（与超展开长按同一份本机名单）：只影响社区 tab 的两页帖子流，这一页照常能看。头部到了才有这一项。 */
    fun setMuted(muted: Boolean) {
        val group = (currentState.group as? Load.Ready)?.value ?: return
        viewModelScope.launchWrite(failures, retry = { setMuted(muted) }) {
            if (muted) mutes.mute(group.summary, now = clock.nowMillis()) else mutes.unmute(name)
        }
    }

    /**
     * 加入（[joined] 为 true）或退出这个小组，做成后头部换成回读的那份（「已加入」与成员数一起变）。
     * 只有网页能做而 App 里做不成时（没有网页会话、网页做了回读没变），提示带「去网页」，到小组网页上完成；
     * 别的失败提示带重试。
     */
    fun setJoined(joined: Boolean) {
        if (currentState.membershipPending) return
        membershipWriteVersion++
        updateState { it.copy(membershipPending = true) }
        viewModelScope.launch {
            val outcome = repository.setJoined(name, joined)
            if (outcome is Outcome.Success) membershipWriteVersion++
            updateState { state ->
                val group = (outcome as? Outcome.Success)?.value
                state.copy(membershipPending = false, group = if (group != null) Load.Ready(group) else state.group)
            }
            val failure = (outcome as? Outcome.Failed)?.failure ?: return@launch
            if (failure in WEB_ONLY) failures.postWebOnly(failure, BgmWeb.group(name)) else failures.post(failure, retry = { setJoined(joined) })
        }
    }

    /** [quiet]：用户没动手的后台重取（回到页面），已有内容时取不到不弹提示。 */
    private fun loadGroup(quiet: Boolean = false) {
        val writeVersion = membershipWriteVersion
        launchSection("group", failures.takeUnless { quiet }, { loadOnce { repository.getGroup(name) } }, { it.group }) { loaded ->
            if (writeVersion == membershipWriteVersion) copy(group = loaded) else this
        }
    }

    private fun refreshTopics() = pager.reload { failures.post(it, retry = ::refresh) }

    private companion object {
        /** 加入、退出时说明「只能在网页上做」的失败：提示的动作是去小组网页。 */
        val WEB_ONLY = setOf(
            Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED),
            Failure.Rejected(RejectReason.GROUP_MEMBERSHIP_UNAVAILABLE),
        )
    }
}
