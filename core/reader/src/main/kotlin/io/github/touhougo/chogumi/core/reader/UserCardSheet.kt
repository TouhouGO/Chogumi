package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.LocalFriends
import io.github.touhougo.chogumi.core.ui.common.LocalUserCareerCard
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.FriendStatusButton
import io.github.touhougo.chogumi.core.ui.composables.PageModalSheet
import io.github.touhougo.chogumi.core.ui.image.ImageViewer
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction

/**
 * 用户卡片（原型截图 22、`.ucard`）：点楼层头像弹出。签名不在每层楼挂，只在这里看。
 *
 * 头像、昵称与签名一行，「查看主页」是这一行右侧的小按钮，与头像居中对齐；[onOpenProfile] 为 null 时不画——没有去处就不给按钮。
 * 下面一行是关注状态按钮（[FriendStatusButton]，与他人主页同一个）：不用去主页就能加好友、看是不是好友；状态读当前账号的好友名单
 * （[LocalFriends]，与名字旁「好友」小牌同一份），加好友与解除好友经页面的写操作通道 [onModerate]（与楼层长按菜单同一条），
 * 解除先弹确认。自己的卡片、页面没接写操作通道（null）、取不到用户名、专注模式（好友属于社区功能，与他人主页同一条规则）都不画这一行。
 * 再下面是这个人的生涯卡（[LocalUserCareerCard]，与个人页「概览」同一个组件），[username] 为空（取不到用户名）时不画。
 *
 * 取值：
 * - From M3：顶部圆角 28dp（`CornerExtraLargeTop`）；昵称 `titleLargeEmphasized`；「查看主页」是 extra small 按钮
 *   （容器高 32dp，形状、内边距、字阶都按这一档，`component-tokens.md` ButtonXSmall），触控区仍是 48dp；关注状态按钮是 small 按钮（40dp）。
 * - 设计决定（not in M3）：底色 `surfaceContainer`、内容左右 20 / 下 16、行间距 12、头像 64、头像与昵称、昵称与按钮间距 16；
 *   签名放在昵称下方、贴头像底（产品决定），不单独装框；关注状态按钮占满一行。
 */
private val UserCardAvatarSize = 64.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UserCardSheet(
    nickname: String,
    avatarUrl: String?,
    sign: String,
    /** 用户名：生涯卡按它取数、写操作按它发；空 = 取不到，不画生涯卡与关注状态按钮。 */
    username: String,
    /** 用户数字 id：关注状态按它查当前账号的好友名单。 */
    userId: Int,
    /** 是不是当前账号自己：自己的卡片没有关注状态按钮。 */
    isOwn: Boolean,
    onDismiss: () -> Unit,
    onOpenProfile: (() -> Unit)?,
    /** 页面的写操作通道（加好友、解除好友）；null = 这页没接，不画关注状态按钮。 */
    onModerate: Gated<(ModerationAction) -> Unit>?,
) {
    val careerCard = LocalUserCareerCard.current
    val friendButton = onModerate != null && !isOwn && username.isNotBlank() && LocalAppFeatures.current.community
    var viewingAvatar by rememberSaveable { mutableStateOf(false) }
    if (viewingAvatar && avatarUrl != null) {
        ImageViewer(listOf(avatarUrl), startIndex = 0, onDismiss = { viewingAvatar = false })
    }
    // 有「查看主页」会进子页：画在所在页的面板层里，返回原样还在（独立窗口在返回时要重建，见 PageSheetLayer）
    PageModalSheet(
        onDismiss = onDismiss,
        fullHeight = false,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // 点头像全屏看原图
                CircleAvatar(avatarUrl, nickname, UserCardAvatarSize, onClick = avatarUrl?.let { { viewingAvatar = true } })
                // 昵称贴头像顶、签名贴头像底；签名长了往下长，不挤昵称。没写签名就不画这一行，昵称与头像居中对齐。
                Column(
                    modifier = Modifier.weight(1f).heightIn(min = UserCardAvatarSize),
                    verticalArrangement = if (sign.isBlank()) Arrangement.Center else Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = nickname,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (sign.isNotBlank()) {
                        Text(
                            text = sign,
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onOpenProfile != null) {
                    // 与头像居中对齐：签名长了往下长，按钮仍在头像旁边
                    Box(Modifier.heightIn(min = UserCardAvatarSize), contentAlignment = Alignment.Center) {
                        val height = ButtonDefaults.ExtraSmallContainerHeight
                        FilledTonalButton(
                            onClick = onOpenProfile,
                            shapes = ButtonDefaults.shapesFor(height),
                            contentPadding = ButtonDefaults.contentPaddingFor(height),
                            modifier = Modifier.heightIn(min = height),
                        ) {
                            Text(stringResource(R.string.user_card_view_profile), style = ButtonDefaults.textStyleFor(height))
                        }
                    }
                }
            }
            if (friendButton) {
                FriendStatusButton(
                    isFriend = LocalFriends.current.includes(userId),
                    onAddFriend = { onModerate.action(ModerationAction.AddFriend(username)) },
                    onUnfriendRequest = { onModerate.action(ModerationAction.Unfriend(username)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (careerCard != null && username.isNotBlank()) careerCard(username)
        }
    }
}
