package io.github.touhougo.chogumi.feature.messages

import io.github.touhougo.chogumi.core.model.PmConversation
import io.github.touhougo.chogumi.core.model.PmMessage
import io.github.touhougo.chogumi.core.model.PmThread

/** 会话页的一行：一条线开头的主题标签，或一封信的气泡。[key] 给列表做稳定的键（字符串）。 */
internal sealed interface PmRow {
    val key: String

    /** 一条线从这里开始（或在别的线之后接着这条线）：居中的主题名，点了回复这条线。 */
    data class ThreadLabel(val thread: PmThread, val firstMessageId: Int) : PmRow {
        override val key = "thread-$firstMessageId"
    }

    /**
     * 一封信。同一个人、同一条线里相隔不久的几封连成一组（[startsRun] / [endsRun]）：
     * 对方的头像只画在一组的第一封旁边，时间只写在一组的最后一封下面。
     */
    data class Bubble(val message: PmMessage, val startsRun: Boolean, val endsRun: Boolean) : PmRow {
        override val key = "message-${message.id}"
    }
}

/**
 * 会话 → 从旧到新的行（唯一的排法）：换线处插一个主题标签（同一条线隔着别的线再出现，再插一次）；
 * 认不出所在线的信不插标签。连成一组的条件：同一个人、同一条线、和上一封相隔不超过 [RUN_GAP_MILLIS]。
 */
internal fun pmRowsOf(conversation: PmConversation): List<PmRow> {
    val threads = conversation.threads.associateBy { it.id }
    val rows = mutableListOf<PmRow>()
    var previous: PmMessage? = null
    conversation.messages.forEachIndexed { index, message ->
        val newThread = previous?.threadId != message.threadId
        if (newThread) threads[message.threadId]?.let { rows += PmRow.ThreadLabel(it, message.id) }
        val next = conversation.messages.getOrNull(index + 1)
        rows += PmRow.Bubble(
            message = message,
            startsRun = previous?.let { !sameRun(it, message) } ?: true,
            endsRun = next?.let { !sameRun(message, it) } ?: true,
        )
        previous = message
    }
    return rows
}

private fun sameRun(earlier: PmMessage, later: PmMessage) =
    earlier.mine == later.mine && earlier.threadId == later.threadId && later.sentAt - earlier.sentAt <= RUN_GAP_MILLIS

/** 连成一组的最大间隔：十分钟（设计决定，not in M3：同一阵子说的几句算一组，隔久了重新写时间）。 */
internal const val RUN_GAP_MILLIS = 10 * 60_000L
