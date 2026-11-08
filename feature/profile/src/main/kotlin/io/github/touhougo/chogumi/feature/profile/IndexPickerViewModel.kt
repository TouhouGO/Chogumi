package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.map
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.IndexMembership
import io.github.touhougo.chogumi.core.model.IndexTarget
import kotlinx.coroutines.launch

/**
 * 收录面板（[IndexPickerSheet]）的状态。
 *
 * 勾选不另存一份「勾了哪些」：[indexes] 是服务端的样子（这样东西在不在各目录里），[toggled] 是这次点过、
 * 与服务端不一样的目录；一行勾没勾只由 [isPicked] 算。确认只提交 [toggled] 里的目录，成功一项，那一项的
 * [IndexMembership.relatedId] 换成服务端的新值、从 [toggled] 里拿掉；失败的留在 [toggled] 里（用户的勾选不丢），
 * 面板不收，再点「保存」只重交这些。
 *
 * @property indexes 我创建的目录（含私密），各带目标在不在里面；新建的目录排在最前。
 * @property saving 正在逐项提交：「保存」转圈，勾选与新建都不接受。
 * @property saved 全部写成功：面板据此收起（`hideRequested`）。
 * @property creating 新建目录在途：「保存」不能按（[canSaveIndex]）。
 */
data class IndexPickerUiState(
    val indexes: Load<List<IndexMembership>> = Load.Pending,
    val toggled: Set<Int> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val creating: Boolean = false,
)

/**
 * 「保存」能不能按：有改过的勾选，且不在提交、没提交完、没有新建在途。新建在途时不能保存：新目录建好才勾上，
 * 先交出去的那一轮里没有它，面板收起后它就只勾不交。按钮与 [IndexPickerViewModel.save] 都只看这里。
 */
internal fun canSaveIndex(state: IndexPickerUiState): Boolean =
    state.toggled.isNotEmpty() && !state.saving && !state.saved && !state.creating

/** 收录面板里一行的勾选：在目录里与这次点过，两者恰有一个成立（点一次翻转、再点一次回到服务端的样子）。 */
internal fun isPicked(membership: IndexMembership, toggled: Set<Int>): Boolean =
    (membership.relatedId != null) != (membership.index.id in toggled)

/**
 * 收录面板：把 [target]（作品、角色、人物、章节、日志、话题）收进自己的目录或移出，可以同时勾多个，也可以就地新建一个目录。
 * 每次打开面板各一个实例（[IndexPickerSheet] 按这次打开的 id 取），打开就取一遍勾选初值。
 * 提交逐项进行，新勾的收录、取消勾的移出；失败的提示在面板里（[failures]，面板盖着页面，页面的提示条在遮罩下看不见）。
 */
class IndexPickerViewModel(
    private val target: IndexTarget,
    private val repository: IndexRepository,
) : StateViewModel<IndexPickerUiState>(IndexPickerUiState()) {

    val failures = FailureNotices()

    init {
        load()
    }

    /** 勾选初值取不到时的重试（面板里只有重试，没有内容可留）。 */
    fun retry() = load()

    private fun load() = launchSection(
        key = MEMBERSHIPS_SECTION,
        failures = null,
        source = { loadOutcome { repository.membershipsOf(target) } },
        get = { it.indexes },
    ) { copy(indexes = it) }

    /** 点了一行：翻转这一行的勾选（只改本地，确认时才提交）。 */
    fun toggle(indexId: Int) {
        if (currentState.saving || currentState.saved) return
        updateState { state ->
            state.copy(toggled = if (indexId in state.toggled) state.toggled - indexId else state.toggled + indexId)
        }
    }

    /** 确认：[IndexPickerUiState.toggled] 里的目录逐项收录或移出；全部成功才收起面板。 */
    fun save() {
        val state = currentState
        if (!canSaveIndex(state)) return
        val changes = state.indexes.valueOrNull().orEmpty().filter { it.index.id in state.toggled }
        if (changes.isEmpty()) return
        updateState { it.copy(saving = true) }
        viewModelScope.launch {
            var failure: Failure? = null
            changes.forEach { membership ->
                val related = membership.relatedId
                val written: Outcome<Int?> = if (related == null) {
                    repository.addEntry(membership.index.id, target)
                } else {
                    repository.removeEntry(membership.index.id, related).map { null }
                }
                when (written) {
                    is Outcome.Success -> updateState { it.written(membership.index.id, written.value) }
                    is Outcome.Failed -> failure = written.failure
                }
            }
            updateState { it.copy(saving = false, saved = failure == null) }
            failure?.let { failures.post(it, retry = ::save) }
        }
    }

    /**
     * 就地新建一个目录（名称 [title]，[isPrivate] 为私密）：建好排在最前、直接勾上（收录仍等确认时提交），
     * 然后回调 [onCreated]（面板收起输入框、清空名称）。失败时名称留着，提示原因。
     */
    fun create(title: String, isPrivate: Boolean, onCreated: () -> Unit) {
        val state = currentState
        if (state.creating || state.saving || state.saved || !IndexTitleRule.isComplete(title)) return
        updateState { it.copy(creating = true) }
        viewModelScope.launch {
            when (val created = repository.createIndex(title, description = "", isPrivate = isPrivate)) {
                is Outcome.Success -> {
                    val membership = IndexMembership(IndexBo(id = created.value, title = title.trim(), total = 0, isPrivate = isPrivate), relatedId = null)
                    updateState { s ->
                        s.copy(
                            creating = false,
                            indexes = s.indexes.map { listOf(membership) + it },
                            toggled = s.toggled + membership.index.id,
                        )
                    }
                    onCreated()
                }
                is Outcome.Failed -> {
                    updateState { it.copy(creating = false) }
                    failures.post(created.failure)
                }
            }
        }
    }

    private companion object {
        const val MEMBERSHIPS_SECTION = "memberships"
    }
}

/** 目录 [indexId] 写成功：它的收录行 id 换成 [relatedId]（移出为 null），从待提交里拿掉。 */
private fun IndexPickerUiState.written(indexId: Int, relatedId: Int?) = copy(
    indexes = indexes.map { list -> list.map { if (it.index.id == indexId) it.copy(relatedId = relatedId) else it } },
    toggled = toggled - indexId,
)
