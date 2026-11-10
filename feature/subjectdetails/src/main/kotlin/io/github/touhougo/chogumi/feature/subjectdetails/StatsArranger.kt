package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.repository.StatsLayoutRepository
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.model.StatsDrawing
import io.github.touhougo.chogumi.core.model.StatsModule
import io.github.touhougo.chogumi.core.model.reorderedStats
import io.github.touhougo.chogumi.core.model.withStatsModule
import io.github.touhougo.chogumi.core.model.withoutStatsModule
import io.github.touhougo.chogumi.core.model.newStatsModule
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeMachine
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 统计领域的草稿；整理状态与保存代次由core:ui同一状态机持有。 */
data class StatsEditor(val module: StatsModule, val isNew: Boolean)

class StatsArranger(
    private val scope: CoroutineScope,
    private val layouts: StatsLayoutRepository,
    private val read: () -> ArrangeState<StatsEditor>,
    write: ((ArrangeState<StatsEditor>) -> ArrangeState<StatsEditor>) -> Unit,
    private val currentLayout: () -> List<StatsModule>,
    private val failures: FailureNotices,
) {
    val machine = ArrangeMachine(read, write)
    private val update = write
    fun edit(module: StatsModule) = machine.edit(StatsEditor(module, false))
    fun add(data: StatsData) = machine.edit(StatsEditor(currentLayout().newStatsModule(data), true))
    fun name(value: String) = update { state -> if (state.saving) state else state.copy(editor = state.editor?.let { it.copy(module = it.module.copy(name = value)) }) }
    fun drawing(value: StatsDrawing) = update { state -> if (state.saving) state else state.copy(editor = state.editor?.let { it.copy(module = it.module.copy(drawing = value)) }) }
    fun move(ids: List<String>) = scope.launchWrite(failures) {
        layouts.edit { it.reorderedStats(ids) }
    }
    fun remove(id: String) = scope.launchWrite(failures) { layouts.edit { it.withoutStatsModule(id) } }

    fun save() {
        val editor = read().editor ?: return
        persist { it.withStatsModule(editor.module, editor.isNew) }
    }
    fun removeEdited() {
        val editor = read().editor ?: return
        persist { it.withoutStatsModule(editor.module.id) }
    }
    private fun persist(change: (List<StatsModule>) -> List<StatsModule>) {
        val token = machine.beginSave() ?: return
        scope.launch {
            val result = outcomeOf { layouts.edit(change) }
            if (!machine.completeSave(token, result is Outcome.Success)) return@launch
            if (result is Outcome.Failed) failures.post(result.failure)
        }
    }
}
