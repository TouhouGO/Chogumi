package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.IndexMembership
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.BusyButton
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PrivateRow
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.ScrollableModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SegmentedCreateItem
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import java.util.UUID
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 「收录」面板：页面 ⋮ 里的「收录」打开（core:ui `PageMoreMenu`，经 `LocalIndexPicker` 由 app 层接到这里）。
 *
 * 标题行右侧是「保存」；下面第一行「新建目录」，点开就地填名称、选私密，建好的目录排在最前并直接勾上；
 * 再下面是我创建的全部目录，每行一个勾选框，可以同时勾多个，[target] 已经在里面的预先勾上。
 * 保存只提交改动过的行，全部成功面板收起；有失败时面板留着、失败的勾选也留着，提示条在面板里。
 *
 * 每次打开一个 [IndexPickerViewModel]（按这次打开的 id 取，活到所在页离开）：重开总是重取勾选初值，
 * 关掉面板时还在途的提交照常写完。
 */
@Composable
fun IndexPickerSheet(target: IndexTarget, onDismiss: () -> Unit) {
    val openId = rememberSaveable { UUID.randomUUID().toString() }
    val viewModel: IndexPickerViewModel = koinViewModel(key = "index-picker-$openId") { parametersOf(target) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    IndexPickerSheetContent(
        state = state,
        failures = viewModel.failures,
        actions = IndexPickerActions(
            toggle = viewModel::toggle,
            save = viewModel::save,
            create = viewModel::create,
            retry = viewModel::retry,
        ),
        onDismiss = onDismiss,
    )
}

/** 面板上的操作，一一对应 [IndexPickerViewModel] 的同名方法。 */
internal class IndexPickerActions(
    val toggle: (indexId: Int) -> Unit,
    val save: () -> Unit,
    val create: (title: String, isPrivate: Boolean, onCreated: () -> Unit) -> Unit,
    val retry: () -> Unit,
)

/** [IndexPickerSheet] 的画法：只读 [state]，操作交给 [actions]。 */
@Composable
internal fun IndexPickerSheetContent(state: IndexPickerUiState, failures: FailureNotices, actions: IndexPickerActions, onDismiss: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    FailureNoticeEffect(failures, snackbar)
    ScrollableModalSheet(onDismiss = onDismiss, hideRequested = state.saved, snackbar = snackbar) {
        SheetTitleRow(stringResource(R.string.index_pick_title)) {
            BusyButton(
                onClick = actions.save,
                enabled = canSaveIndex(state),
                busy = state.saving,
                busyLabel = stringResource(R.string.loading),
                modifier = Modifier.testTag(IndexPickerSaveTag),
            ) {
                Text(stringResource(R.string.save))
            }
        }
        IndexPickerList(state, actions)
    }
}

@Composable
private fun IndexPickerList(state: IndexPickerUiState, actions: IndexPickerActions) {
    // 「新建目录」的输入放在列表外面：那一行滚出列表时输入不丢，建好的回调也落在同一份上
    val newIndex = rememberSaveable(saver = NewIndexForm.Saver) { NewIndexForm() }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        // 左右留白由每行自己给：「新建目录」里的「仅自己可见」开关是整宽的列表项，与新建 / 编辑面板里一样贴着面板边
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        when (sectionStateOf(state.indexes)) {
            SectionState.LOADING -> items(SKELETON_ROWS) { index -> IndexPickRowSkeleton(index) }
            SectionState.ERROR -> item(key = "error") {
                RetryError(checkNotNull(state.indexes.failureOrNull()), onRetry = actions.retry, RowPadding)
            }
            // 还没有目录时只有「新建目录」一行
            SectionState.EMPTY, SectionState.CONTENT -> {
                val indexes = state.indexes.valueOrNull().orEmpty()
                item(key = "new") {
                    NewIndexRow(
                        form = newIndex,
                        creating = state.creating,
                        enabled = !state.saving,
                        onCreate = actions.create,
                    )
                }
                itemsIndexed(indexes, key = { _, membership -> membership.index.id }) { position, membership ->
                    IndexPickRow(
                        membership = membership,
                        picked = isPicked(membership, state.toggled),
                        position = position,
                        count = indexes.size,
                        enabled = !state.saving,
                        onToggle = { actions.toggle(membership.index.id) },
                    )
                }
            }
        }
    }
}

/** 一个目录一行：名称、收录数，行尾勾选框（整行就是开关，勾选框只是显示）。M3 列表多选：trailing checkbox。 */
@Composable
private fun IndexPickRow(
    membership: IndexMembership,
    picked: Boolean,
    position: Int,
    count: Int,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val total = membership.index.total
    SegmentedItem(
        index = position,
        count = count,
        onClick = onToggle,
        enabled = enabled,
        trailingContent = { Checkbox(checked = picked, onCheckedChange = null, enabled = enabled) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.index_items_count, total, total), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier
            .then(RowPadding)
            .semantics {
                role = Role.Checkbox
                selected = picked
            }
            .testTag("$IndexPickRowTagPrefix${membership.index.id}"),
    ) {
        Text(membership.index.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** [IndexPickRow] 的占位：同一个行框架、同样两行字阶与行尾勾选框（不可见），内容到了行高不变。 */
@Composable
private fun IndexPickRowSkeleton(position: Int) {
    SegmentedItem(
        index = position,
        count = SKELETON_ROWS,
        onClick = null,
        trailingContent = { Checkbox(checked = false, onCheckedChange = null, modifier = Modifier.alpha(0f).clearAndSetSemantics {}) },
        supportingContent = { SkeletonText(MaterialTheme.typography.bodyMedium, widthFraction = 0.25f) },
        modifier = RowPadding,
    ) {
        SkeletonText(MaterialTheme.typography.bodyLarge, widthFraction = 0.5f)
    }
}

/** 「新建目录」的输入：展开没有、名称、私密。由面板持有（[IndexPickerList]），建好才 [reset]。 */
@Stable
private class NewIndexForm(open: Boolean = false, name: String = "", isPrivate: Boolean = false) {
    var open by mutableStateOf(open)
    var name by mutableStateOf(name)
    var isPrivate by mutableStateOf(isPrivate)

    fun reset() {
        open = false
        name = ""
        isPrivate = false
    }

    companion object {
        val Saver = listSaver<NewIndexForm, Any>(
            save = { listOf(it.open, it.name, it.isPrivate) },
            restore = { NewIndexForm(it[0] as Boolean, it[1] as String, it[2] as Boolean) },
        )
    }
}

/**
 * 「新建目录」：收着时是一行（[SegmentedCreateItem]，全仓「新建」行的同一种样子）；点开换成名称输入框（与新建 / 编辑面板
 * 同一个 [IndexTitleField]）、「仅自己可见」开关（同一个 [PrivateRow]）与「创建」。
 * 建好由 [IndexPickerViewModel.create] 回调收起、清空；失败时输入留着。
 */
@Composable
private fun NewIndexRow(form: NewIndexForm, creating: Boolean, enabled: Boolean, onCreate: (String, Boolean, () -> Unit) -> Unit) {
    if (!form.open) {
        SegmentedCreateItem(
            index = 0,
            count = 1,
            title = stringResource(R.string.index_create),
            icon = R.drawable.add_24,
            onClick = { form.open = true },
            enabled = enabled,
            modifier = RowPadding.padding(bottom = NewIndexGap).testTag(IndexPickerNewTag),
        )
        return
    }
    Column(Modifier.fillMaxWidth().padding(bottom = NewIndexGap)) {
        IndexTitleField(
            value = form.name,
            onValueChange = { if (IndexTitleRule.accepts(it)) form.name = it },
            enabled = !creating,
            modifier = RowPadding.testTag(IndexPickerNameTag),
        )
        PrivateRow(checked = form.isPrivate, onCheckedChange = { form.isPrivate = it }, enabled = !creating)
        BusyButton(
            onClick = { onCreate(form.name, form.isPrivate, form::reset) },
            enabled = enabled && IndexTitleRule.isComplete(form.name),
            busy = creating,
            busyLabel = stringResource(R.string.loading),
            modifier = RowPadding.align(Alignment.End),
        ) {
            Text(stringResource(R.string.index_pick_create))
        }
    }
}

/** 列表每行的左右留白（面板内容的边距）。 */
private val RowPadding = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding)

private const val SKELETON_ROWS = 3

/** 「新建目录」与下面的目录列表之间多空出的一截：两组分开（设计决定，not in M3）。 */
private val NewIndexGap = 8.dp

internal const val IndexPickerSaveTag = "index_picker_save"
internal const val IndexPickerNewTag = "index_picker_new"
internal const val IndexPickerNameTag = "index_picker_name"
internal const val IndexPickRowTagPrefix = "index_pick_row_"
