package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.userDisplayNameOf
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.ui.common.Gated
import io.github.touhougo.chogumi.core.ui.moderation.ModerationAction

/**
 * 哪张用户卡片开着：一个整数 id（楼层页是点了头像的那层楼的 id，目录页是创建者的用户 id），随页面保存。
 *
 * 它由**屏幕**持有（在列表之上），不在列表的某一行里：行滚出屏幕就离开组合，进程重建后行在楼层回来前是骨架，
 * 行里的状态会跟着丢（卡片消失）。行只拿到 [open]，卡片由 [CommentUserCard]（或调用处的 [UserCardSheet]）在屏幕层画一次。
 */
@Stable
class UserCardState internal constructor(openId: Int?) {
    var openId: Int? by mutableStateOf(openId)
        private set

    fun open(id: Int) {
        openId = id
    }

    fun close() {
        openId = null
    }

    internal companion object {
        val Saver: Saver<UserCardState, Int> = Saver(save = { it.openId }, restore = { UserCardState(it) })
    }
}

/** 屏幕层的用户卡片状态（可保存）；点「查看主页」进子页再返回，卡片原样还在。 */
@Composable
fun rememberUserCardState(): UserCardState = rememberSaveable(saver = UserCardState.Saver) { UserCardState(null) }

/**
 * 楼层页的用户卡片：在 [threads]（这一页的全部楼层，主楼与楼中楼）里按 [UserCardState.openId] 找到那层楼的作者，画一次。
 * 楼层还没回来（重建后）找不到就什么都不画，id 留着，楼层到了原样画出。[onModerate] 是页面给楼层长按菜单的同一条写操作通道
 * （卡片里的关注状态按钮经它加好友、解除好友）；null = 这页没接。
 */
@Composable
fun CommentUserCard(
    state: UserCardState,
    threads: List<CommentThreadBo>,
    onUserClick: (String) -> Unit,
    onModerate: Gated<(ModerationAction) -> Unit>?,
) {
    val id = state.openId ?: return
    val author = threads.commentById(id) ?: return
    UserCardSheet(
        nickname = userDisplayNameOf(author.creatorUsername, author.creatorNickname, author.creatorId),
        avatarUrl = author.creatorAvatar,
        sign = author.creatorSign,
        username = author.creatorUsername,
        userId = author.creatorId,
        isOwn = author.isOwn,
        onDismiss = state::close,
        onOpenProfile = { onUserClick(userKeyOf(author.creatorUsername, author.creatorId)) },
        onModerate = onModerate,
    )
}

private fun List<CommentThreadBo>.commentById(id: Int): CommentItemBo? =
    firstNotNullOfOrNull { thread -> (listOf(thread.anchor) + thread.replies).firstOrNull { it.id == id } }
