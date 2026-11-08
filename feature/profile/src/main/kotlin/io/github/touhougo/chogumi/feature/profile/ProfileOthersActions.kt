package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.ButtonLeadingIcon
import io.github.touhougo.chogumi.core.ui.composables.FriendStatusButton
import io.github.touhougo.chogumi.core.ui.composables.SkeletonStandIn

/**
 * 他人主页的操作行（原型 `.actions`：gap 12dp，按钮高 40dp = M3 small 按钮）。
 *
 * 状态**只从 [ops] 派生**（唯一真源 [SocialOpsState]）：
 * - 未绝交：关注状态按钮（[FriendStatusButton]，与用户卡片同一个；好友名单还没读到时是同大的占位）+ `短消息`（outlined，站内写信给该用户）；
 * - 已绝交：整行换成「你已与 TA 绝交、取消绝交」条（原型 `.blocked`，圆角 16dp、底 surfaceContainer）。
 *
 * 「已是好友」再点先弹确认（[onUnfriendRequest]），防误触。
 */
@Composable
internal fun OthersActionBar(
    user: UserBo?,
    ops: SocialOpsState,
    onAddFriend: () -> Unit,
    onUnfriendRequest: () -> Unit,
    onUnblock: () -> Unit,
    onMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (ops.isBlocked) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = MaterialTheme.shapes.large,
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.block_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.profile_blocked_banner, user?.let(::displayNameOf).orEmpty()),
                    Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onUnblock) { Text(stringResource(R.string.profile_unblock)) }
            }
        }
        return
    }
    // 原型 `.actions{gap:12px}`：关注状态按钮（写在途时不可点）与「短消息」各占一半。
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val isFriend = ops.isFriend
        if (isFriend == null) {
            // 好友名单还没读到：按钮自己当占位，与读到后的按钮同大、同形状
            SkeletonStandIn(Modifier.weight(1f), shape = ButtonDefaults.shape) {
                FriendStatusButton(isFriend = false, onAddFriend = {}, onUnfriendRequest = {}, modifier = Modifier.fillMaxWidth())
            }
        } else {
            FriendStatusButton(
                isFriend = isFriend,
                onAddFriend = onAddFriend,
                onUnfriendRequest = onUnfriendRequest,
                enabled = !ops.pending,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(onClick = onMessage, modifier = Modifier.weight(1f)) {
            ButtonLeadingIcon(R.drawable.mail_24)
            Text(stringResource(R.string.profile_message))
        }
    }
}
