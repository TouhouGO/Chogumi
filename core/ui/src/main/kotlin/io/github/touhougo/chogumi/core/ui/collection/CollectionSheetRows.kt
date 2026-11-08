package io.github.touhougo.chogumi.core.ui.collection

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.model.CollectionProgress
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.ChoiceGroupStyle
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.iconRes
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.labelResFor
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/** 上次没保存的修改还在：tertiary container 一行，带「放弃」。 */
@Composable
internal fun RestoredBanner(onDiscard: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, bottom = 12.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.collection_restored), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.collection_discard), color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

/**
 * 状态区上下的提示行（进度到头了、要不要一起标完）：surface container 底的一行字，右边一个动作。
 * [rowModifier] 挂在面里面那一行上（整行可切换时的点击）：水波纹裁在圆角里。
 */
@Composable
internal fun NoteRow(text: String, modifier: Modifier = Modifier, rowModifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ChogumiDimens.ScreenPadding),
    ) {
        Row(
            modifier = rowModifier.heightIn(min = NoteRowHeight).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            action()
        }
    }
}

/** 状态：五个切换按钮连成一组，图标在上、文字在下，选中那格由方变圆、填 primary（[ChoiceGroupStyle.STATUS]）。 */
@Composable
internal fun StatusChoice(selected: CollectionType, subject: SubjectBo, onSelect: (CollectionType) -> Unit) {
    ConnectedChoiceGroup(
        options = CollectionType.choices,
        selected = selected,
        label = { stringResource(it.labelResFor(subject.type)) },
        // 按类型分的说法放不下（英文「Want to watch」）时整组换成通用的短名
        shortLabel = { stringResource(it.labelRes()) },
        onSelect = onSelect,
        icon = { option, selected -> option.iconRes(selected) },
        style = ChoiceGroupStyle.STATUS,
        modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding),
    )
}

/** 选「看过」时：进度一起标到最后（默认开）。整行可点，开关只是显示。 */
@Composable
internal fun FinishAllRow(progress: CollectionProgress, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val text = stringResource(if (progress is CollectionProgress.Book) R.string.collection_finish_all_book else R.string.collection_finish_all_episodes)
    NoteRow(
        text = text,
        modifier = Modifier.padding(top = 12.dp),
        rowModifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 移除已收藏的作品：点行后确认，确认前不提交；写入在途时不可重复触发。 */
@Composable
internal fun RemoveCollectionRow(subject: SubjectBo, enabled: Boolean, onRemove: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error
    ListItem(
        enabled = enabled,
        onClick = { confirming = true },
        leadingContent = { Icon(painterResource(R.drawable.delete_24), contentDescription = null, tint = error) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f)),
    ) {
        Text(stringResource(R.string.collection_remove), color = error)
    }
    if (confirming) {
        ConfirmDialog(
            target = subject,
            confirmLabel = stringResource(R.string.collection_remove),
            onConfirm = { onRemove() },
            onDismiss = { confirming = false },
            title = stringResource(R.string.collection_remove),
            text = stringResource(R.string.collection_remove_confirm, displayNameOf(subject)),
            destructive = true,
            confirmEnabled = enabled,
            confirmModifier = Modifier.testTag("collection_remove_confirm"),
        )
    }
}

@Composable
internal fun SectionDivider() {
    HorizontalDivider(Modifier.padding(start = ChogumiDimens.ScreenPadding, end = ChogumiDimens.ScreenPadding, top = 16.dp))
}


/** 提示行的最小高：放得下一颗按钮（设计稿 `.autodone`，not in M3）。 */
private val NoteRowHeight = 56.dp
