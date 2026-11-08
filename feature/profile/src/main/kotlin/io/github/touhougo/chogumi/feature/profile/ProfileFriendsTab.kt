package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.ui.common.loadMoreFooter
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.PersonGridItem
import io.github.touhougo.chogumi.core.ui.composables.PersonGridItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.labelRes

/**
 * 好友 tab：圆头像网格，与收藏「角色与人物」同一种网格（复用 [PersonGridItem]）。
 *
 * 列数不写死（原型 `.mgrid` 的 4 列在 380dp 小屏上会把头像挤扁），由 [GridCell.Person] 按屏宽推出。
 * 顶栏「我关注的 / 关注我的」切换保留；文案「好友」沿用 [getFriends] 的单向语义（KDoc 警告勿删）。
 */
internal fun LazyGridScope.socialItems(
    state: SocialState,
    /** 看的是不是自己：决定方向与空态文案说「我」还是说主人的名字（[SocialDirection.labelRes]）。 */
    isSelf: Boolean,
    /** 主页主人的显示名：他人主页的空态文案里称呼的就是这个名字。 */
    ownerName: String,
    onSelect: (SocialDirection) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onUserClick: (String) -> Unit,
    endText: String?,
) {
    // 「我关注的 / 关注我的」：原型 `.cbg`。选中方向只从状态里读（[SocialState.direction]），界面不另存一份。
    item(span = { GridItemSpan(maxLineSpan) }) {
        ConnectedChoiceGroup(
            options = SocialDirection.entries,
            selected = state.direction,
            label = { stringResource(it.labelRes(isSelf)) },
            onSelect = onSelect,
        )
    }

    val people = state.current
    val error = people.error
    when {
        people.isLoading -> items(6) { PersonGridItemSkeleton(Modifier.testTag(ProfileSocialItemTag)) }
        error != null -> item(span = { GridItemSpan(maxLineSpan) }) { RetryError(error, onRetry = onRefresh, Modifier.fillMaxWidth()) }
        // 同收藏 tab：空页但仍有后续游标时不能给空态，否则遮住页脚（服务端跳过整段用户时会出现）。
        people.isConfirmedEmpty -> item(span = { GridItemSpan(maxLineSpan) }) {
            // 本人的空态文案没有占位，多给的名字不用
            EmptyLine(stringResource(state.direction.emptyRes(isSelf), ownerName))
        }
        else -> {
            // 每个用户**直接是一个网格单元**——真懒加载的关键：按行分组会把整份列表一次性构建成节点树。
            items(people.items, key = { it.id }) { user ->
                PersonGridItem(
                    title = displayNameOf(user),
                    modifier = Modifier.testTag(ProfileSocialItemTag),
                    imageUrl = user.avatarUrl,
                    onClick = { onUserClick(userKeyOf(user.username, user.id)) },
                )
            }
            loadMoreFooter(people, onLoadMore, endText)
        }
    }
}


/** 收藏与社交单元的几何锚点：真实内容与加载占位共用。 */
internal const val ProfileSocialItemTag = "profile_social_item"
