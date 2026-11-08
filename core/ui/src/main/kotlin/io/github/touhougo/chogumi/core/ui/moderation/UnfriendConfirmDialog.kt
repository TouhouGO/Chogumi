package io.github.touhougo.chogumi.core.ui.moderation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog

/**
 * 解除好友确认（他人主页与用户卡片共用，画法是 [ConfirmDialog]）：「已是好友」再点先确认，防误触。确认后把 [target]
 * （要解除的那个人）交给 [onConfirm]。文案不带称呼：解除不是绝交，随时能再加，一句话说清就够。
 */
@Composable
fun <T : Any> UnfriendConfirmDialog(target: T, onConfirm: (T) -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        target = target,
        confirmLabel = stringResource(R.string.profile_unfriend_confirm_action),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        title = stringResource(R.string.profile_unfriend_confirm_title),
        text = stringResource(R.string.profile_unfriend_confirm_body),
        destructive = true,
    )
}
