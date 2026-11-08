package io.github.touhougo.chogumi.core.model

import java.time.LocalDate

// 站内短信只有旧站网页有（开放接口没有），模型照网页：和一个人的全部短信是一个会话，会话里按主题分成几条线。
// 收件箱、发件箱在网页上已经合成一个会话列表。

/**
 * 短信会话列表里的一个会话。
 *
 * @property id 会话 id：既不是对方 uid，也不是信的 id。
 * @property peer 对方：昵称、头像；uid 只在自定义头像的地址里读得出，读不出时为 0（名字照常由 `displayNameOf` 给）。
 * @property date 最近一条的日期（网页只给日期，不编时间）；读不出为 null。
 * @property preview 「主题 / 最近一条正文开头」：正文是原始 BBCode，截断时带 `...`；对方那边看不到主题时只有正文。
 * @property unread 这个会话里的未读条数；打开会话就算读过。
 */
data class PmConversationSummary(
    val id: Int,
    val peer: UserBo,
    val date: LocalDate?,
    val preview: String,
    val unread: Int,
)

/**
 * 一个短信会话：整段从旧到新。
 *
 * @property peer 对方：uid、主页地址里的那一段（[UserBo.username]）、昵称、头像。
 * @property threads 会话里的主题（一条线），新主题在前。
 * @property replyThreadId 默认回复的那条线（最新那条线）的主题 id；没有为 null。
 */
data class PmConversation(
    val id: Int,
    val peer: UserBo,
    val threads: List<PmThread>,
    val messages: List<PmMessage>,
    val replyThreadId: Int?,
)

/** 会话里的一个主题：[id] 是这条线第一封信的 id，回复这条线时作为 `related` 交给网页。 */
data class PmThread(val id: Int, val title: String)

/**
 * 会话里的一封信。
 *
 * @property threadId 所在那条线的主题 id（[PmThread.id]）。
 * @property mine 是不是自己发的。
 * @property body 正文 BBCode：网页渲染好的 HTML 还原而来（`bbcodeOfHtml`），交给帖子用的同一个渲染器。
 * @property sentAt 发出的时刻，epoch 毫秒。
 */
data class PmMessage(val id: Int, val threadId: Int, val mine: Boolean, val body: String, val sentAt: Long)

/**
 * 要发的一封短信。
 *
 * @property receiverId 收件人的数字 uid。
 * @property replyTo 回复哪条线（[PmThread.id]）；null 是一封新信（和已有会话的人发，就是那个会话里的一个新主题）。
 * @property newTopic 在 [replyTo] 所在的会话里开一个新主题，名字是 [title]。
 */
data class PmDraft(
    val receiverId: Int,
    val body: String,
    val title: String = "",
    val replyTo: Int? = null,
    val newTopic: Boolean = false,
)
