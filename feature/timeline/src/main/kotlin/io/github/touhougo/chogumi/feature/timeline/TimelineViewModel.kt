package io.github.touhougo.chogumi.feature.timeline

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.BlockedUsersFeed
import io.github.touhougo.chogumi.core.data.repository.DraftRepository
import io.github.touhougo.chogumi.core.data.repository.TimelineRepository
import io.github.touhougo.chogumi.core.data.repository.postDrafts
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineMode
import io.github.touhougo.chogumi.core.model.TimelineQuery
import io.github.touhougo.chogumi.core.model.UserModeration
import io.github.touhougo.chogumi.core.reader.ComposerActions
import io.github.touhougo.chogumi.core.reader.ComposerState
import io.github.touhougo.chogumi.core.reader.TimelineDetailActions
import io.github.touhougo.chogumi.core.reader.TimelineDetailState
import io.github.touhougo.chogumi.core.reader.TimelineFilter
import io.github.touhougo.chogumi.core.reader.TimelineReplyTarget
import io.github.touhougo.chogumi.core.reader.draftKey
import io.github.touhougo.chogumi.core.ui.moderation.ModerationController
import io.github.touhougo.chogumi.core.ui.moderation.ModerationState
import kotlinx.coroutines.launch

/**
 * 动态页自己的状态；动态列表本身在仓库里（[TimelineViewModel.feed]），不在这里另存一份。
 *
 * @property filter 类别：好友、全站两页共用，筛的是各自已取到的列表（接口不能按类别查，见 [scanRound]）。
 * @property scanning 正在按类别往前找的范围：页脚在两页之间的空当里也一直显示加载中。
 * @property resumeAfter 用户点「继续往前找」时各范围的最后一条（id），[scanPaused] 从它往后重新计数；换类别、刷新时清掉。
 * @property pending 删除在途：同一时刻只有一个。
 * @property composer 发动态的编辑器面板。
 */
data class TimelineUiState(
    val filter: TimelineFilter = TimelineFilter.ALL,
    val scanning: Set<TimelineMode> = emptySet(),
    val resumeAfter: Map<TimelineMode, Long> = emptyMap(),
    val pending: Boolean = false,
    /** 动态详情面板。 */
    val detail: TimelineDetailState = TimelineDetailState(),
    val composer: ComposerState<Unit> = ComposerState(),
    /** 楼层长按的报告面板与绝交确认，只由 [TimelineViewModel.moderation] 改。 */
    val moderation: ModerationState = ModerationState(),
)

class TimelineViewModel(
    private val repository: TimelineRepository,
    drafts: DraftRepository,
    clock: AppClock,
    userModeration: UserModeration,
    blockedUsers: BlockedUsersFeed,
) : StateViewModel<TimelineUiState>(TimelineUiState()) {

    /** 本页的失败提示：刷新失败（已有内容时）、删除失败、详情面板里回复与贴贴失败。 */
    val failures = FailureNotices()

    /** 楼层长按菜单里的报告、加好友、绝交（[ModerationController]），失败经 [failures] 提示。 */
    val moderation = ModerationController(
        scope = viewModelScope,
        read = { currentState.moderation },
        write = { change -> updateState { it.copy(moderation = change(it.moderation)) } },
        failures = failures,
        moderation = userModeration,
    )

    /** 详情面板的操作：楼层、贴贴与写回复（面板状态是 [TimelineUiState.detail]）。 */
    val detail = TimelineDetailActions(
        scope = viewModelScope,
        state = { currentState.detail },
        update = { change -> updateState { it.copy(detail = change(it.detail)) } },
        replies = repository::replies,
        react = repository::react,
        reply = repository::reply,
        drafts = drafts.postDrafts(PostDraftKind.TIMELINE_REPLY, clock, TimelineReplyTarget::draftKey),
        failures = failures,
        blocked = blockedUsers.current(),
    )

    /** 发动态（发布失败的提示在面板里，见 [ComposerActions.failures]；发出后仓库自己刷新各列表）；没发出去的字留成草稿。 */
    val composer = ComposerActions<Unit, Long>(
        scope = viewModelScope,
        state = { currentState.composer },
        update = { transform -> updateState { it.copy(composer = transform(it.composer)) } },
        submit = { _, draft, token -> repository.publish(draft.text, token) },
        afterUnsure = { repository.refreshLoadedFeeds() },
        drafts = drafts.postDrafts(PostDraftKind.TIMELINE, key = "", clock),
    )

    fun feed(query: TimelineQuery) = repository.observe(query)

    /** 这一页按当前类别是否该先停下、等用户点「继续往前找」（判定见 [scanPaused]）。 */
    private fun paused(query: TimelineQuery, items: List<TimelineItemBo>): Boolean =
        scanPaused(items, currentState.filter, currentState.resumeAfter[query.mode])

    /**
     * 进页、换类别时调：列表是空的才从头取——从别人主页 / 详情返回时，新动态不会插到顶上把刚点的卡片顶下去，
     * 要新内容由下拉刷新取（[refresh]）；取到了却没有一条属于当前类别，就往前找。
     */
    fun open(query: TimelineQuery) {
        viewModelScope.launch {
            if (repository.observe(query).value.items.isEmpty()) repository.refresh(query)
            scanIfNoneListed(query)
        }
    }

    /** 下拉刷新与整页重试。已有内容时失败，内容留着、用提示条说原因（见 [TimelineRepository.refresh]）。 */
    fun refresh(query: TimelineQuery) {
        updateState { it.copy(resumeAfter = it.resumeAfter - query.mode) }
        viewModelScope.launch {
            repository.refresh(query, userInitiated = true)?.let { failures.post(it, retry = { refresh(query) }) }
            scanIfNoneListed(query)
        }
    }

    /**
     * 续取：页脚露出时自动调，停下后点「继续往前找」也调这里。按类别筛时一轮可能连取多页（[scanRound]）；
     * 已经停下时（只有点按钮能走到这里）从当前最后一条起重新计数。
     */
    fun loadMore(query: TimelineQuery) {
        val mode = query.mode
        if (mode in currentState.scanning) return
        val feed = repository.observe(query)
        if (paused(query, feed.value.items)) {
            val last = feed.value.items.lastOrNull()?.id ?: return
            updateState { it.copy(resumeAfter = it.resumeAfter + (mode to last)) }
        }
        updateState { it.copy(scanning = it.scanning + mode) }
        viewModelScope.launch {
            try {
                scanRound(
                    feed = { feed.value },
                    accepts = { currentState.filter.accepts(it) },
                    paused = { paused(query, it) },
                    loadPage = { repository.loadMore(query) },
                )
            } finally {
                updateState { it.copy(scanning = it.scanning - mode) }
            }
        }
    }

    fun selectFilter(filter: TimelineFilter) = updateState { it.copy(filter = filter, resumeAfter = emptyMap()) }

    fun delete(id: Long) = action { repository.delete(id) }

    /** 已有动态、却没有一条属于当前类别：往前找（列表为空或整页出错时不找，那是首屏加载与重试的事）。 */
    private fun scanIfNoneListed(query: TimelineQuery) {
        val state = repository.observe(query).value
        if (state.error == null && state.items.isNotEmpty() && state.items.none(currentState.filter::accepts)) loadMore(query)
    }

    private fun action(block: suspend () -> Outcome<*>) {
        if (currentState.pending) return
        updateState { it.copy(pending = true) }
        viewModelScope.launch {
            try {
                val result = block()
                if (result is Outcome.Failed) failures.post(result.failure)
            } finally {
                updateState { it.copy(pending = false) }
            }
        }
    }
}
