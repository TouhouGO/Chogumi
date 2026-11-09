package io.github.touhougo.chogumi.core.model

/**
 * 一个表情共 [count] 人贴的贴贴；[selected] 时其中一个是我。别人的名单按编号生成（id 从 1 起），
 * 同样的参数生成同样的名单，所以贴上、取消之后的结果能直接与另一次构造比较。
 */
fun reactionOf(value: Int, count: Int, selected: Boolean = false): CommentReactionBo = CommentReactionBo(
    value = value,
    others = List(count - if (selected) 1 else 0) { index -> ReactionUserBo(id = index + 1, username = "user${index + 1}", nickname = "用户${index + 1}") },
    selected = selected,
)
