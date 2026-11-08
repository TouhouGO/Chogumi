package io.github.touhougo.chogumi.feature.community

import io.github.touhougo.chogumi.core.model.NoticeBo
import io.github.touhougo.chogumi.core.model.NoticeSenderBo
import io.github.touhougo.chogumi.core.model.PmConversationSummary
import io.github.touhougo.chogumi.core.model.UserBo
import java.time.Instant
import java.time.LocalDate

/** Synthetic inbox rows for semantics and visual checks; no account, network, Room or real-user data. */
internal object NotificationSyntheticFixtures {
    val now: Long = Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    const val SENDER_NAME = "合成发送者"
    const val NOTICE_TITLE = "合成讨论主题"
    const val CONVERSATION_NAME = "合成收件人"
    const val CONVERSATION_PREVIEW = "这是一条用于布局和无障碍检查的合成短信。"
    const val UNREAD_COUNT = 2

    private val sender = UserBo(
        id = 4200,
        username = "synthetic-sender",
        nickname = SENDER_NAME,
    )

    private val peer = UserBo(
        id = 4300,
        username = "synthetic-peer",
        nickname = CONVERSATION_NAME,
    )

    fun notice(unread: Boolean): NoticeBo = NoticeBo(
        id = 6100,
        type = 1,
        sender = NoticeSenderBo(sender.id, sender.username, sender.nickname),
        title = NOTICE_TITLE,
        mainId = 5100,
        relatedId = 5200,
        createdAt = now - 60_000,
        unread = unread,
    )

    fun conversation(unread: Int): PmConversationSummary = PmConversationSummary(
        id = 6200,
        peer = peer,
        date = LocalDate.of(2026, 10, 27),
        preview = CONVERSATION_PREVIEW,
        unread = unread,
    )
}
