package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.common.bbcode.SmileyCatalog

/**
 * 一条 reaction 该怎么显示：认识的是一个表情 code（内置还是远程下载由 [SmileyImage] 一处决定），
 * 不认识的只能显示原始编号。
 */
sealed interface ReactionDisplay {
    /** 认识的 value，画 [code] 对应的表情。 */
    data class Smiley(val code: String) : ReactionDisplay

    /** 服务端返回了客户端不认识的 value —— 退回原文，不崩溃不空白。 */
    data class Unknown(val value: Int) : ReactionDisplay
}

/**
 * 把服务端的 reaction `value` 解析成显示方式。
 *
 * ## 为什么不直接用 value 当索引
 *
 * `value` 是服务端的**内部编号**（0/79/54/140…），不是文件名也不是序号。
 * 直接拼进字符串的话，界面会显示「54、2」。映射语义在
 * [SmileyCatalog]，这里只做分流，不持有任何映射表。
 *
 * ## 未知 value 的处理
 *
 * 服务端调整过两次 reaction 集合（`ALLOWED_COMMON_REACTIONS` 与
 * `HIDDEN_REACTIONS`），将来还可能变。遇到不认识的 value 显示原始编号，
 * 比空白或崩溃诚实 —— 与 `BBCodeText` 对未知节点「原样显示」同一条原则。
 */
fun reactionDisplayOf(value: Int): ReactionDisplay {
    val code = SmileyCatalog.codeOfReaction(value) ?: return ReactionDisplay.Unknown(value)
    return ReactionDisplay.Smiley(code)
}
