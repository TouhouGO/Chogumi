package io.github.touhougo.chogumi.core.model

/**
 * 评论状态。
 *
 * 后端 9 种状态映射（`bangumi-server-private/lib/comment.ts`）：
 * - NORMAL: 0(正常), 3(置顶), 4(合并) 以及未知数值兜底
 * - DELETED: 1(管理员关闭), 2(重开), 5(下沉), 6(用户删除), 7(管理员删除)
 * - FOLDED: 8(折叠)
 */
enum class CommentState {
    NORMAL,
    DELETED,
    FOLDED,
}

/** 贴了某个表情的一个人（服务端贴贴名单里的一项）。头像不在名单里，按用户名取：[bgmUserAvatarUrl]。 */
data class ReactionUserBo(
    val id: Int,
    val username: String,
    val nickname: String,
)

/**
 * 一个表情的贴贴。名单是唯一的来源：[others] 是除我以外贴了它的人（服务端顺序），我贴没贴只看 [selected]，
 * 人数由两者推出（[count]）。我不放进名单：贴上、取消只翻 [selected]（[withMyReaction]），不需要知道我的资料。
 */
data class CommentReactionBo(
    val value: Int,
    val others: List<ReactionUserBo>,
    val selected: Boolean,
) {
    val count: Int get() = others.size + if (selected) 1 else 0
}

/**
 * 单条评论领域模型。
 *
 * 没有楼层号字段：楼层号由 UI 渲染层按评论在列表中的索引推导，只有一个来源，不会与列表顺序对不上。
 */
data class CommentItemBo(
    val id: Int,
    val mainId: Int,
    val relatedId: Int,
    val creatorId: Int,
    val creatorNickname: String,
    val creatorUsername: String,
    val creatorAvatar: String?,
    val content: String,
    val createdAt: Long,
    val state: CommentState,
    val reactions: List<CommentReactionBo> = emptyList(),
    /** 发帖人签名：不在楼层里显示，只在点头像弹出的用户卡片上看。 */
    val creatorSign: String = "",
    /** 吐槽（条目短评）才有：发帖人的收藏状态与评分，楼层组件的紧凑样式据此画收藏状态角标与星级。 */
    val collection: CommentCollectionBo? = null,
    /** 是不是当前账号发的（数据层映射时按当前会话定一次）：楼层菜单据此给出编辑、删除。未登录时恒为 false。 */
    val isOwn: Boolean = false,
)

/**
 * 吐槽附带的收藏信息。[rate] 为 0 表示没打分。
 */
data class CommentCollectionBo(
    val type: CollectionType,
    val rate: Int,
)

/**
 * 楼中楼两层评论树。
 */
data class CommentThreadBo(
    val anchor: CommentItemBo,
    val replies: List<CommentItemBo> = emptyList(),
)
