package io.github.touhougo.chogumi.core.ui.moderation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.res.R

/**
 * 绝交确认（他人主页 ⋮ 与楼层菜单共用，画法是 [ConfirmDialog]）。后果写明，确认后把 [target]（要绝交的那个人）交给 [onConfirm]。
 * 原型 `.dialog .ic`：顶部 block 图标；「绝交」是收不回来的操作。
 */
@Composable
fun <T : Any> BlockConfirmDialog(target: T, nickname: String, onConfirm: (T) -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        target = target,
        confirmLabel = stringResource(R.string.block_confirm_action),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        title = stringResource(R.string.block_confirm_title, nickname),
        text = stringResource(R.string.block_confirm_body, nickname),
        destructive = true,
        icon = R.drawable.block_24,
    )
}
