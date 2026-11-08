package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R

/**
 * 关注状态按钮（bgm.tv 的「好友」是单向关注）：他人主页的操作行与用户卡片共用的那一个实现。
 * 不是好友 → filled tonal「加为好友」（person_add）；已是好友 → outlined「已是好友」（✓），再点交给 [onUnfriendRequest]，
 * 调用方先弹解除确认（[io.github.touhougo.chogumi.core.ui.moderation.UnfriendConfirmDialog]），防误触。
 * [enabled] 为 false 时两种都不可点（写在途，防双击双发）。
 * 高 40dp、图标 20、图标与字 8：M3 small 按钮默认值（ButtonDefaults.Small*）。
 */
@Composable
fun FriendStatusButton(
    isFriend: Boolean,
    onAddFriend: () -> Unit,
    onUnfriendRequest: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (isFriend) {
        OutlinedButton(onClick = onUnfriendRequest, enabled = enabled, modifier = modifier) {
            ButtonLeadingIcon(R.drawable.check_24)
            Text(stringResource(R.string.profile_already_friend))
        }
    } else {
        FilledTonalButton(onClick = onAddFriend, enabled = enabled, modifier = modifier) {
            ButtonLeadingIcon(R.drawable.person_add_24)
            Text(stringResource(R.string.add_friend))
        }
    }
}

/** 按钮前置图标：M3 small 按钮的图标尺寸与图标—文字间距。 */
@Composable
fun ButtonLeadingIcon(iconRes: Int) {
    Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(ButtonDefaults.SmallIconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
}
