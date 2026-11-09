package io.github.touhougo.chogumi.core.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.bgmUserAvatarUrl
import io.github.touhougo.chogumi.core.model.findItem
import io.github.touhougo.chogumi.core.model.userDisplayNameOf
import io.github.touhougo.chogumi.core.model.userKeyOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.LocalSignedInAccount
import io.github.touhougo.chogumi.core.ui.composables.ContentGrid
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.PeopleListSheet
import io.github.touhougo.chogumi.core.ui.composables.PersonGridItem
import io.github.touhougo.chogumi.core.ui.composables.SelectableChip

/*
 * 谁贴了：长按一颗贴贴（或贴贴投票的一个选项），打开这一处贴贴的名单面板。与作品「谁在看」面板同一种写法：
 * 名单面板（[PeopleListSheet]：满高面板 → 标题 → 一排筛选 chip，每种表情一个、带人数 → 人物网格），格子是
 * [PersonGridItem]（与收藏者、好友同一种），点一个人进他的主页。名单来自贴贴本身（[CommentReactionBo.others]），不另外请求；
 * 头像按用户名走头像入口（[bgmUserAvatarUrl]）。
 */

/**
 * 哪一处贴贴的名单开着：那一处的 id（楼层是楼 id，动态是动态 id）和停在哪个表情，随页面保存。
 * 与 [UserCardState] 一样由**屏幕**持有（在列表之上）：行滚出屏幕或重建后还是骨架时，行里的状态会丢。
 */
@Stable
class ReactionUsersState internal constructor(open: Pair<Int, Int>?) {
    var itemId: Int? by mutableStateOf(open?.first)
        private set
    var value: Int? by mutableStateOf(open?.second)
        private set

    fun open(itemId: Int, value: Int) {
        this.itemId = itemId
        this.value = value
    }

    /** 面板里换一个表情。 */
    fun select(value: Int) {
        this.value = value
    }

    fun close() {
        itemId = null
        value = null
    }

    internal companion object {
        /** 存成 [id, 表情]，关着时存空数组。 */
        val Saver: Saver<ReactionUsersState, IntArray> = Saver(
            save = { state -> state.itemId?.let { id -> state.value?.let { intArrayOf(id, it) } } ?: IntArray(0) },
            restore = { saved -> ReactionUsersState(saved.takeIf { it.size == 2 }?.let { it[0] to it[1] }) },
        )
    }
}

/** 屏幕层的贴贴名单状态（可保存）；点一个人进他的主页再返回，面板原样还在。 */
@Composable
fun rememberReactionUsersState(): ReactionUsersState = rememberSaveable(saver = ReactionUsersState.Saver) { ReactionUsersState(null) }

/**
 * 楼层页的贴贴名单：在 [threads]（这一页的全部楼层）里按 [ReactionUsersState.itemId] 找到那层楼，画它的贴贴名单。
 * 楼层还没回来（重建后）找不到就什么都不画，状态留着，楼层到了原样画出。
 */
@Composable
fun CommentReactionUsers(state: ReactionUsersState, threads: List<CommentThreadBo>, onUserClick: (String) -> Unit) {
    val id = state.itemId ?: return
    val value = state.value ?: return
    val reactions = threads.findItem(id)?.reactions ?: return
    ReactionUsersSheet(reactions, value, onSelect = state::select, onDismiss = state::close, onUserClick = onUserClick, contentKey = id)
}

/**
 * 一处贴贴的名单面板（[reactions] 是那一处现在的贴贴，贴上、取消后跟着变）。停在 [value] 那个表情上，chip 换表情经 [onSelect]；
 * 它已经没人贴了（刷新、自己取消）就停在第一个，一个都没有时不画。我贴的那一格排在最前（[LocalSignedInAccount]）。
 * [contentKey] 换了（另一层楼）就是另一个面板。
 */
@Composable
internal fun ReactionUsersSheet(
    reactions: List<CommentReactionBo>,
    value: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onUserClick: (String) -> Unit,
    contentKey: Any? = null,
) {
    val shown = reactions.firstOrNull { it.value == value } ?: reactions.firstOrNull() ?: return
    PeopleListSheet(
        title = stringResource(R.string.reaction_users_title),
        onDismiss = onDismiss,
        contentKey = contentKey,
        filters = {
            reactions.forEach { reaction ->
                SelectableChip(
                    label = "${reaction.count}",
                    selected = reaction.value == shown.value,
                    labelLeading = { ReactionGlyph(reactionDisplayOf(reaction.value), FilterChipDefaults.IconSize) },
                    onClick = { onSelect(reaction.value) },
                )
            }
        },
    ) {
        // 换一个表情是另一份名单：从头看起
        key(shown.value) {
            ReactionUserGrid(shown, onUserClick)
        }
    }
}

@Composable
private fun ReactionUserGrid(reaction: CommentReactionBo, onUserClick: (String) -> Unit) {
    val me = LocalSignedInAccount.current?.takeIf { reaction.selected }
    val meLabel = stringResource(R.string.reaction_users_me)
    ContentGrid(cell = GridCell.Person, modifier = Modifier.fillMaxSize(), bottom = 16.dp) {
        if (me != null) {
            item(key = "me") {
                PersonGridItem(
                    title = meLabel,
                    imageUrl = bgmUserAvatarUrl(me.username),
                    onClick = { onUserClick(userKeyOf(me.username, me.userId)) },
                )
            }
        }
        items(reaction.others, key = { it.id }) { user ->
            PersonGridItem(
                title = userDisplayNameOf(user.username, user.nickname, user.id),
                imageUrl = bgmUserAvatarUrl(user.username),
                onClick = { onUserClick(userKeyOf(user.username, user.id)) },
                userId = user.id,
            )
        }
    }
}
