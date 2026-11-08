package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.data.repository.INDEX_TITLE_MAX_LENGTH
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.BusyButton
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.composables.PrivateRow
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 新建 / 编辑目录面板：标题行右侧「保存」，下面名称、介绍、「仅自己可见」。
 * 打开就把焦点交给名称（弹出键盘）；写成功才滑下去（[IndexEditorState.saved]），失败时填的内容留着。
 */
@Composable
internal fun IndexEditorSheet(
    state: IndexEditorState,
    onChange: (IndexDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val draft = state.draft
    val editable = !state.saving && !state.saved
    PageModalSheet(onDismiss = onDismiss, fullHeight = false, hideRequested = state.saved, growsWithContent = true, inputFocus = focus) {
        SheetTitleRow(stringResource(if (state.indexId == null) R.string.index_create else R.string.index_edit)) {
            BusyButton(onClick = onSave, enabled = state.canSave, busy = state.saving, busyLabel = stringResource(R.string.loading)) {
                Text(stringResource(R.string.save))
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(SheetFieldGap),
        ) {
            IndexTitleField(
                value = draft.title,
                onValueChange = { onChange(draft.copy(title = it)) },
                enabled = editable,
                modifier = Modifier.focusRequester(focus),
            )
            FormTextField(
                value = draft.description,
                onValueChange = { onChange(draft.copy(description = it)) },
                label = stringResource(R.string.index_description_label),
                enabled = editable,
                minLines = DESCRIPTION_MIN_LINES,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PrivateRow(
            checked = draft.isPrivate,
            onCheckedChange = { onChange(draft.copy(isPrivate = it)) },
            enabled = editable,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
    }
}

/**
 * 目录名称输入框：新建 / 编辑面板与收录面板里的就地新建共用。框下靠右是已写的字数 / 上限
 * （与编辑器、收藏短评的计数同一种写法）；多打的字由调用处按 [IndexTitleRule] 拦下。
 */
@Composable
internal fun IndexTitleField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    FormTextField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.index_title_label),
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        supportingText = {
            Text(
                stringResource(R.string.text_length_count, value.length, INDEX_TITLE_MAX_LENGTH),
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/** 「收录链接」面板的状态（关着时页面状态里是 null）：框里的字、在途、写成功（面板滑下去）。 */
data class IndexLinkState(val text: String = "", val adding: Boolean = false, val added: Boolean = false)

/**
 * 自己的目录里「收录链接」：一个输入框，下面实时说认出的是哪一类（[target] 由页面经 core:ui `indexLinkOf` 从框里的字得到），
 * 认不出时标红；标题行右侧「收录」。打开时剪贴板里有能收录的链接就预填（页面做）。
 */
@Composable
internal fun IndexLinkSheet(
    state: IndexLinkState,
    target: IndexTarget?,
    onTextChange: (String) -> Unit,
    onAdd: (IndexTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val unsupported = state.text.isNotBlank() && target == null
    PageModalSheet(onDismiss = onDismiss, fullHeight = false, hideRequested = state.added, growsWithContent = true, inputFocus = focus) {
        SheetTitleRow(stringResource(R.string.index_add_link)) {
            BusyButton(
                onClick = { target?.let(onAdd) },
                enabled = target != null && !state.added,
                busy = state.adding,
                busyLabel = stringResource(R.string.loading),
            ) {
                Text(stringResource(R.string.index_add))
            }
        }
        FormTextField(
            value = state.text,
            onValueChange = onTextChange,
            placeholder = stringResource(R.string.index_add_link_placeholder),
            enabled = !state.adding && !state.added,
            isError = unsupported,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            supportingText = when {
                target != null -> {
                    { Text(stringResource(R.string.index_add_link_kind, stringResource(target.category.labelRes()))) }
                }
                unsupported -> {
                    { Text(stringResource(R.string.index_add_link_unsupported)) }
                }
                else -> null
            },
            modifier = Modifier
                .padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, bottom = 16.dp)
                .fillMaxWidth()
                .focusRequester(focus),
        )
    }
}

/**
 * 自己目录里一条收录的评语面板（长按一行「编辑评语」）：标题行右侧「保存」，下面一个多行输入框。
 * 打开就把焦点交给输入框（弹出键盘）；写成功才滑下去（[IndexCommentState.saved]），失败时框里的字留着。
 */
@Composable
internal fun IndexCommentSheet(
    state: IndexCommentState,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    PageModalSheet(onDismiss = onDismiss, fullHeight = false, hideRequested = state.saved, growsWithContent = true, inputFocus = focus) {
        SheetTitleRow(stringResource(R.string.index_comment_title)) {
            BusyButton(onClick = onSave, enabled = state.canSave, busy = state.saving, busyLabel = stringResource(R.string.loading)) {
                Text(stringResource(R.string.save))
            }
        }
        FormTextField(
            value = state.text,
            onValueChange = onChange,
            placeholder = stringResource(R.string.index_comment_placeholder),
            enabled = !state.saving && !state.saved,
            minLines = DESCRIPTION_MIN_LINES,
            modifier = Modifier
                .padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, bottom = 16.dp)
                .fillMaxWidth()
                .focusRequester(focus),
        )
    }
}

/** 面板里两个输入框之间的距离（设计决定，not in M3：与举报面板的输入框上边距同一档）。 */
private val SheetFieldGap = 8.dp

/** 多行输入框（目录介绍、收录评语）至少几行高（设计决定，not in M3）。 */
private const val DESCRIPTION_MIN_LINES = 3
