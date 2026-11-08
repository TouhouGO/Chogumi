package io.github.touhougo.chogumi.core.network.model.web

/**
 * 短信会话列表页（`/pm/inbox.chii`）上的一行：和一个人的全部短信是一个会话。
 *
 * @property id 会话 id（`/pm/conversation/{id}.chii`）：既不是对方 uid，也不是信的 id。
 * @property peerId 对方的数字 uid，只从自定义头像的地址里读得出（`…/400100_a1B2c.jpg`）；默认头像没有，为 null。
 * @property date 最近一条的日期，网页原文（`2026-10-26`、`2021-3-8`，只有日期）。
 * @property preview 「主题 / 最近一条正文开头」的网页原文：正文是原始 BBCode，截断时带 `...`；对方那边看不到主题时只有正文。
 * @property unread 这个会话里的未读条数。
 */
data class PmConversationRow(
    val id: Int,
    val peerName: String,
    val peerAvatar: String,
    val peerId: Int?,
    val date: String,
    val preview: String,
    val unread: Int,
)

/**
 * 一个短信会话页（`/pm/conversation/{id}.chii`）：整段会话一页给全，从旧到新。
 *
 * @property peerId 对方的数字 uid（回复表单的收件人）。
 * @property peerKey 对方主页地址里的那一段（`/user/{peerKey}`）。
 * @property threads 会话里的主题（一条线），按网页筛选栏的顺序（新主题在前）。
 * @property replyThreadId 网页默认回复的那条线（最新那条线）的主题 id；网页上没有回复表单时为 null。
 */
data class PmConversationPage(
    val peerId: Int,
    val peerKey: String,
    val peerName: String,
    val peerAvatar: String,
    val threads: List<PmThreadRef>,
    val messages: List<PmMessageItem>,
    val replyThreadId: Int?,
)

/** 会话里的一个主题：[id] 是这条线第一封信的 id。 */
data class PmThreadRef(val id: Int, val title: String)

/**
 * 会话里的一封信。
 *
 * @property threadId 所在那条线的主题 id。
 * @property mine 是不是自己发的。
 * @property bodyHtml 正文：网页渲染好的 HTML 原样（`.pm-message-body` 的内容）。
 * @property sentAt 发出的时间，网页原文（`2026-10-26 12:09`，按账号设置的时区写）。
 */
data class PmMessageItem(val id: Int, val threadId: Int, val mine: Boolean, val bodyHtml: String, val sentAt: String)

/**
 * 要发出的一封短信。
 *
 * @property receiverId 收件人的数字 uid（网页的收件人框也认 uid，写信页、会话页填的都是它）。
 * @property related 回复哪条线（主题 id）；null 是一封新信（和已有会话的人发就是那个会话里的新主题）。
 * @property newTopic 在 [related] 所在的会话里开一个新主题，[title] 是它的名字。
 */
data class PmOutgoing(val receiverId: Int, val title: String, val body: String, val related: Int?, val newTopic: Boolean)

/** 网页上的未读短信：[count] 是未读条数，[markAllReadLink] 是「全部已读」的地址（网页给的，带 `gh=`）。 */
data class WebUnreadMessages(val count: Int, val markAllReadLink: String)
