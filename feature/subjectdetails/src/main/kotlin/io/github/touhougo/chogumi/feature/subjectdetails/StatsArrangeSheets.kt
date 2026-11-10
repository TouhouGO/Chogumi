package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.StatsData
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeAddSheet
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeChoiceRow
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeEditorSheet
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeSheet
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeState
import io.github.touhougo.chogumi.core.ui.arrange.EditorSection
import io.github.touhougo.chogumi.core.ui.arrange.RemoveEntryButton
import io.github.touhougo.chogumi.core.ui.arrange.StyleOptions
import io.github.touhougo.chogumi.core.ui.composables.BusyButton
import io.github.touhougo.chogumi.core.ui.composables.FormTextField

/** 通用整理面板的统计调用方，只提供数据目录、名字和真实画法，不复制骨架。 */
@Composable
internal fun StatsArrangeSheets(state: ArrangeState<StatsEditor>, arranger: StatsArranger, tab: SubjectStatsTab?, snackbar: SnackbarHostState?) {
    if (tab == null) return
    if (state.adding) {
        val choices = StatsData.entries.filter { it in tab.availableData() }
        val details = choices.associateWith { data -> data.drawings.map { it.label() }.joinToString("、") }
        ArrangeAddSheet(stringResource(R.string.stats_arrange_add), state.hides(ArrangeSheet.ADDING),
            arranger.machine::addingClosed, action = { Button(onClick = arranger.machine::finishAdding) { Text(stringResource(R.string.arrange_done)) } }, tag = "stats_add_list", snackbar = snackbar) {
            itemsIndexed(choices, key = { _, data -> data.name }) { index, data ->
                ArrangeChoiceRow(index, choices.size, statsDataName(data, tab.subject.type), details[data], { arranger.add(data) },
                    modifier = Modifier.testTag("stats_add_${data.name}"), leading = { StatsModuleLead(data) },
                    trailing = { Icon(painterResource(R.drawable.add_24), null) })
            }
        }
    }
    state.editor?.let { editor ->
        ArrangeEditorSheet(stringResource(if (editor.isNew) R.string.stats_module_new else R.string.stats_module_edit),
            state.hides(ArrangeSheet.EDITOR), arranger.machine::editorClosed,
            action = { BusyButton(onClick = arranger::save, enabled = true, busy = state.saving, busyLabel = stringResource(R.string.loading), modifier = Modifier.testTag("stats_editor_save")) { Text(stringResource(R.string.save)) } }, snackbar = snackbar) {
            FormTextField(editor.module.name, arranger::name, enabled = !state.saving,
                label = stringResource(R.string.stats_module_name), placeholder = statsDataName(editor.module.data, tab.subject.type),
                modifier = Modifier.fillMaxWidth().testTag("stats_editor_name"))
            EditorSection(stringResource(R.string.stats_module_style)) {
                StyleOptions(editor.module.data.drawings, editor.module.drawing, { it.label() }, arranger::drawing,
                    tagPrefix = "stats_style", sketch = false) { drawing -> StatsModuleDrawing(editor.module.copy(drawing = drawing), tab, {}) }
            }
            if (!editor.isNew) RemoveEntryButton(stringResource(R.string.stats_module_remove), arranger::removeEdited, !state.saving, "stats_editor_remove")
        }
    }
}

@Composable
internal fun StatsModuleLead(data: StatsData) {
    val icon = when (data) {
        StatsData.RATING, StatsData.TOTAL_COLLECTS -> R.drawable.bar_chart_24
        StatsData.COLLECTION -> R.drawable.grid_view_24
        StatsData.TAGS, StatsData.SERIES, StatsData.RECOMMENDATIONS -> R.drawable.grid_view_24
        else -> R.drawable.bar_chart_24
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null) }
    }
}
