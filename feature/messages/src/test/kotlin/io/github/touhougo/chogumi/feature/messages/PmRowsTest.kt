package io.github.touhougo.chogumi.feature.messages

import io.github.touhougo.chogumi.core.model.PmConversation
import io.github.touhougo.chogumi.core.model.PmMessage
import io.github.touhougo.chogumi.core.model.PmThread
import io.github.touhougo.chogumi.core.model.UserBo
import kotlin.test.Test
import kotlin.test.assertEquals

/** 会话的行：换线处插主题标签，同一个人、同一条线、相隔不久的几封连成一组。 */
class PmRowsTest {
    private val first = PmThread(1, "第一个主题")
    private val second = PmThread(5, "第二个主题")

    private fun message(id: Int, thread: Int, mine: Boolean, minute: Long) = PmMessage(id, thread, mine, "正文 $id", minute * 60_000L)

    private fun conversation(vararg messages: PmMessage, threads: List<PmThread> = listOf(second, first)) =
        PmConversation(id = 9, peer = UserBo(id = 2, username = "2", nickname = "对方"), threads = threads, messages = messages.toList(), replyThreadId = 5)

    /** 行的简写：`#主题id` 是标签，`id[` / `]` 标出一组的头尾。 */
    private fun shape(rows: List<PmRow>) = rows.joinToString(" ") { row ->
        when (row) {
            is PmRow.ThreadLabel -> "#${row.thread.id}"
            is PmRow.Bubble -> (if (row.startsRun) "[" else "") + row.message.id + (if (row.endsRun) "]" else "")
        }
    }

    @Test
    fun `每条线开头插一个标签_同一条线隔着别的线再出现时再插一次`() {
        val rows = pmRowsOf(
            conversation(
                message(1, 1, mine = true, minute = 0),
                message(5, 5, mine = true, minute = 1),
                message(6, 1, mine = true, minute = 2),
            ),
        )

        assertEquals("#1 [1] #5 [5] #1 [6]", shape(rows))
        assertEquals(listOf("thread-1", "message-1", "thread-5", "message-5", "thread-6", "message-6"), rows.map { it.key })
    }

    @Test
    fun `同一个人同一条线十分钟内的几封连成一组_换人或隔久了另起一组`() {
        val rows = pmRowsOf(
            conversation(
                message(1, 1, mine = false, minute = 0),
                message(2, 1, mine = false, minute = 10),
                message(3, 1, mine = true, minute = 11),
                message(4, 1, mine = true, minute = 30),
            ),
        )

        assertEquals("#1 [1 2] [3] [4]", shape(rows))
    }

    @Test
    fun `认不出所在线的信不插标签`() {
        val rows = pmRowsOf(conversation(message(1, 7, mine = true, minute = 0), threads = emptyList()))

        assertEquals("[1]", shape(rows))
    }
}
