package io.github.touhougo.chogumi.core.model

/**
 * 讨论回复、章节吐槽、动态可贴的 12 个表情（服务端 `lib/like.ts` 的 `ALLOWED_COMMON_REACTIONS`）。
 * 顺序照服务端源码，**不要重排**：选择器按此顺序排。
 */
val COMMON_REACTIONS: List<Int> = listOf(0, 79, 54, 140, 62, 122, 104, 80, 141, 88, 85, 90)

/** 作品吐槽（收藏评论）可贴的 8 个（`ALLOWED_SUBJECT_COLLECT_REACTIONS`，顺序照源码）。是 [COMMON_REACTIONS] 的子集。 */
val SUBJECT_COLLECT_REACTIONS: List<Int> = listOf(0, 104, 54, 140, 122, 90, 88, 80)

/**
 * 换掉「我」的贴贴（唯一规则）：服务端每人每楼只留一个，再贴就是替换（`lib/like.ts` `Reaction.add`）。
 * 先撤掉我原来那个（没有别人贴的整项去掉），[value] 非 null 时再标上它（原来没有就追加在末尾）。别人的名单不动。
 * [value] 为 null 表示取消。
 */
fun List<CommentReactionBo>.withMyReaction(value: Int?): List<CommentReactionBo> {
    val withoutMine = mapNotNull { reaction ->
        if (reaction.selected) reaction.copy(selected = false).takeIf { it.others.isNotEmpty() } else reaction
    }
    if (value == null) return withoutMine
    return if (withoutMine.any { it.value == value }) {
        withoutMine.map { if (it.value == value) it.copy(selected = true) else it }
    } else {
        withoutMine + CommentReactionBo(value = value, others = emptyList(), selected = true)
    }
}

/** 楼层里 id 为 [itemId] 的那一楼（主楼或楼中楼）；没有时 null。 */
fun List<CommentThreadBo>.findItem(itemId: Int): CommentItemBo? =
    firstNotNullOfOrNull { thread -> (listOf(thread.anchor) + thread.replies).firstOrNull { it.id == itemId } }

/** 把 [itemId] 那一楼（主楼或楼中楼）的贴贴换成 [reactions]，其余原样。 */
fun List<CommentThreadBo>.withReactions(itemId: Int, reactions: List<CommentReactionBo>): List<CommentThreadBo> = map { thread ->
    fun CommentItemBo.patched() = if (id == itemId) copy(reactions = reactions) else this
    thread.copy(anchor = thread.anchor.patched(), replies = thread.replies.map { it.patched() })
}
