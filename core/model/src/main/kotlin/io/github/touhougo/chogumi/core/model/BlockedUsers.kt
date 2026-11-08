package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.Load

/**
 * 当前账号绝交的用户（bgm.tv 的绝交名单，[ids] 是用户数字 id）。绝交即屏蔽：名单里的人写的楼层、吐槽、动态、帖子与日志
 * 在 App 里都不显示。名单以服务端为准，App 里没有另一份屏蔽。
 */
data class BlockedUsers(val ids: Set<Int>) {

    /** [userId] 写的东西要不要藏起来：全 App 唯一的判定。作者未知（null）时不藏。 */
    fun hides(userId: Int?): Boolean = userId != null && userId in ids

    companion object {
        /** 谁也不屏蔽：游客，或这个账号的名单读不到。 */
        val NONE = BlockedUsers(emptySet())
    }
}

/**
 * 这一串楼在 [blocked] 这份名单下怎么显示：主楼是绝交用户写的，整串不显示（null）；否则去掉绝交用户的楼中楼。
 *
 * 楼层藏不藏只在显示时按页面**当前**的名单定（数据层取楼层时不判定）：页面跟着当前账号的名单走（`BlockedUsersFeed`），
 * 在这一页或别处绝交、取消绝交后，已经显示的楼层当场跟上，不等下次加载。
 */
fun CommentThreadBo.visibleThread(blocked: BlockedUsers): CommentThreadBo? =
    if (blocked.hides(anchor.creatorId)) null else withoutBlockedReplies(blocked)

/** 去掉 [blocked] 里的人写的楼中楼，主楼照留（话题 #1 楼是话题本身，作者被绝交也照画）。 */
fun CommentThreadBo.withoutBlockedReplies(blocked: BlockedUsers): CommentThreadBo =
    if (replies.none { blocked.hides(it.creatorId) }) this else copy(replies = replies.filterNot { blocked.hides(it.creatorId) })

/**
 * 一块内容在页面跟着的名单 [blocked] 下算不算到了（全 App 唯一的判定）：名单还没读到（null）时，已到的内容仍算等待，
 * 画占位，名单到了才按它一次画出来。先按「谁也不屏蔽」画出来、名单到了再藏，被绝交的人写的东西就会闪一下
 * （刚登录的账号本机还没有名单，要当场去读）。取不到照常传下去：名单读没读到与内容取没取到无关。
 *
 * 页面状态里的名单因此是 `BlockedUsers?`，开始是 null，由 `BlockedUsersFeed` 的第一拍填上，之后不再回到 null。
 */
fun <T> Load<T>.shownUnder(blocked: BlockedUsers?): Load<T> = if (blocked == null && this is Load.Ready) Load.Pending else this
