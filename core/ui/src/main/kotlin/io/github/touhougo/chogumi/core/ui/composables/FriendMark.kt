package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.common.LocalFriends

/**
 * 好友标识运行时开关：真实牌与骨架都必须通过这里决定是否可能画好友牌。
 */
@Composable
fun friendMarksEnabled(): Boolean = LocalAppFeatures.current.friendMarks

/**
 * 名字旁的「好友」小牌：功能开关经 [friendMarksEnabled] 判断，且 [userId] 必须是当前账号关注的人。
 * 画法是 [TextPill] 的浅色调一档（secondaryContainer）：楼主是实心主色，别人的收藏状态是中性色，三种一眼分得开。
 * 跟在名字后面；人物格（[PersonGridItem]）里压在头像底边正中。人人都是好友的列表（好友动态）不放。
 */
@Composable
fun FriendMark(userId: Int?, modifier: Modifier = Modifier) {
    if (!friendMarksEnabled() || !LocalFriends.current.includes(userId)) return
    TextPill(
        text = stringResource(R.string.friend_mark),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    )
}
