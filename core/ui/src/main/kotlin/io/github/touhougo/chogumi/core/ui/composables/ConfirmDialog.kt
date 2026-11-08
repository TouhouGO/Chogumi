package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R

/**
 * 全 App 唯一的确认对话框（M3 basic dialog：28dp 圆角由默认形状承担）：问一句，两个文字按钮，右边确认、左边「取消」。
 * 删除、移除、退出、解除、绝交这类做了就收不回来的操作，确认后才交给 [onConfirm]；没有输入框的「要不要继续」也用它。
 *
 * [target] 是这个框问的那一个（要删的楼、要绝交的人；页面自己知道对象时给 [Unit]）。点确认先 [onDismiss] 收起，再把画这个框时的
 * [target] 交给 [onConfirm]：收起通常就是把「开着的框」那份状态清掉，确认的动作拿手里的 [target] 做事，不回头读刚被清掉的状态。
 *
 * - [title]、[text] 至少给一个（M3：标题可省；标题不写「确定吗？」这种含糊话，后果写进 [text]）。
 * - [destructive] 时确认按钮用 `error` 色（设计决定，not in M3：M3 basic dialog 的按钮字是 primary）。
 * - [icon] 给了时在顶部（M3 带图标时标题居中、图标 secondary，由 [AlertDialog] 承担）。
 * - [confirmEnabled] 为 false 时确认不可点（「取消」永远可点，M3）。
 *
 * 带输入框或其他内容的对话框不在这里，见 tools/guards/check_dialogs.py 的白名单。
 */
@Composable
fun <T : Any> ConfirmDialog(
    target: T,
    confirmLabel: String,
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit,
    title: String? = null,
    text: String? = null,
    destructive: Boolean = false,
    @DrawableRes icon: Int? = null,
    confirmEnabled: Boolean = true,
    confirmModifier: Modifier = Modifier,
) {
    require(title != null || text != null) { "确认框至少要有标题或正文" }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
        title = title?.let { { Text(it) } },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm(target)
                },
                enabled = confirmEnabled,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
                modifier = confirmModifier,
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
