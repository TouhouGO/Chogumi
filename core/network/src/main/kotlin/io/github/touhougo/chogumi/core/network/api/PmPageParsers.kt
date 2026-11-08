package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.model.web.PmConversationPage
import io.github.touhougo.chogumi.core.network.model.web.PmConversationRow
import io.github.touhougo.chogumi.core.network.model.web.PmMessageItem
import io.github.touhougo.chogumi.core.network.model.web.PmThreadRef
import io.github.touhougo.chogumi.core.network.model.web.WebUnreadMessages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

// 旧站短信页（2026-10 的网页结构，样本见 core/network 的测试资源 pm/）：会话列表、会话、发信出错页、未读数 JSON。

/** 会话地址里的会话 id（`/pm/conversation/{id}.chii`）。 */
private val CONVERSATION_HREF = Regex("""/pm/conversation/(\d+)\.chii""")

/** 筛选栏主题链接上的主题 id（`?thread={id}`）。 */
private val THREAD_PARAM = Regex("""[?&]thread=(\d+)""")

/** 信的元素 id（`msg_{信 id}`）。 */
private val MESSAGE_ID = Regex("""msg_(\d+)""")

/** 头像地址：`background-image:url('…')` 里的那一段。 */
private val BACKGROUND_URL = Regex("""url\(\s*['"]?([^'")]+)""")

/** 自定义头像的文件名带对方 uid（`…/pic/user/l/000/40/01/400100_a1B2c.jpg`）；默认头像没有。 */
private val AVATAR_USER_ID = Regex("""/pic/user/[a-z]/(?:\d+/)*(\d+)(?:_[^/.]*)?\.\w+(?:\?.*)?$""")

/** 用户主页地址里的那一段（`/user/{用户名或 uid}`）。 */
private val USER_PATH = Regex("""(?:https?://[^/]+)?/user/([^/?#]+)/?""")

/** 信的发出时间：`2026-10-26 12:09`。 */
private val SENT_AT = Regex("""\d{4}-\d{1,2}-\d{1,2} \d{1,2}:\d{2}""")

/** 网页头像地址（`//lain.bgm.tv/…`、相对或完整地址）→ 完整地址。 */
private fun avatarOf(element: Element?): String {
    val url = element?.attr("style")?.let { BACKGROUND_URL.find(it)?.groupValues?.get(1) }?.trim().orEmpty()
    return if (url.startsWith("//")) "https:$url" else url
}

/** 头像地址里的对方 uid；默认头像为 null。 */
private fun avatarUserIdOf(avatar: String): Int? = AVATAR_USER_ID.find(avatar)?.groupValues?.get(1)?.toIntOrNull()

/** 短信页都在 `#contentPM` 里；没有它说明回的不是短信页（登录页、出错页），按坏响应处理。 */
private fun Document.requirePmPage(): Element = selectFirst("#contentPM") ?: throw FailureException(Failure.BadResponse)

/** 会话列表页上的会话，从上到下（最近的在前）；这一页没有会话（翻到底了）是空列表。 */
fun pmConversationRowsOf(html: String): List<PmConversationRow> {
    val list = webDocumentOf(html).requirePmPage().selectFirst(".pm-conversation-list") ?: throw FailureException(Failure.BadResponse)
    return list.select("a.pm-conversation-item[href]").mapNotNull { row ->
        val id = CONVERSATION_HREF.find(row.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val avatar = avatarOf(row.selectFirst(".avatarNeue"))
        PmConversationRow(
            id = id,
            peerName = row.selectFirst(".pm-conversation-name")?.text().orEmpty(),
            peerAvatar = avatar,
            peerId = avatarUserIdOf(avatar),
            date = row.selectFirst(".pm-conversation-date")?.text().orEmpty(),
            preview = row.selectFirst(".pm-conversation-desc")?.wholeText()?.trim().orEmpty(),
            unread = row.selectFirst(".pm-conversation-unread")?.text()?.trim()?.toIntOrNull() ?: 0,
        )
    }
}

/**
 * 会话页上的会话；短信页上没有打开的会话（「请选择一个对话」，会话里的信已经删光）为 null。
 *
 * 信从旧到新排在 `.pm-message-list` 里，每条线以一个 `.pm-thread-label`（主题名）开头。主题 id 是这条线第一封信的 id，
 * 筛选栏上每个主题的链接带着它；线的第一封被删了，就按主题名找筛选栏上还没对上的那个主题（[threadIdOf]）。
 */
fun pmConversationOf(html: String): PmConversationPage? {
    val page = webDocumentOf(html).requirePmPage()
    val panel = page.selectFirst(".pm-chat-panel") ?: return null
    val list = panel.selectFirst(".pm-message-list") ?: return null
    val peerLink = panel.selectFirst(".pm-chat-title strong a[href]")
    val peerKey = peerLink?.attr("href")?.let { USER_PATH.matchEntire(it)?.groupValues?.get(1) }.orEmpty()
    val form = panel.selectFirst("form#pmReplyForm")
    val peerId = form?.selectFirst("input[name=msg_receivers]")?.attr("value")?.toIntOrNull()
        ?: peerKey.toIntOrNull()
        ?: throw FailureException(Failure.BadResponse)
    val threads = panel.select(".pm-thread-filter a[href]").mapNotNull { link ->
        THREAD_PARAM.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()?.let { PmThreadRef(it, link.text()) }
    }
    val messages = mutableListOf<PmMessageItem>()
    val claimed = mutableSetOf<Int>()
    var threadTitle: String? = null
    var threadId: Int? = null
    for (child in list.children()) {
        when {
            child.hasClass("pm-thread-label") -> {
                threadTitle = child.text()
                threadId = null
            }
            child.hasClass("pm-message") -> {
                val id = MESSAGE_ID.matchEntire(child.id())?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val thread = threadId ?: threadIdOf(id, threadTitle, threads, claimed).also {
                    threadId = it
                    claimed += it
                }
                messages += PmMessageItem(
                    id = id,
                    threadId = thread,
                    mine = child.hasClass("pm-message-self"),
                    bodyHtml = child.selectFirst(".pm-message-body")?.html().orEmpty(),
                    sentAt = child.selectFirst(".pm-message-info")?.text()?.let { SENT_AT.find(it)?.value }.orEmpty(),
                )
            }
        }
    }
    return PmConversationPage(
        peerId = peerId,
        peerKey = peerKey,
        peerName = peerLink?.text().orEmpty(),
        peerAvatar = avatarOf(panel.selectFirst(".pm-chat-header .avatarNeue")),
        threads = threads,
        messages = messages,
        replyThreadId = form?.selectFirst("input[name=related]")?.attr("value")?.toIntOrNull(),
    )
}

/**
 * 一条线（以 [firstMessageId] 开头、主题名 [title]）的主题 id：筛选栏上有以这封信为 id 的主题就是它；
 * 没有（这条线的第一封被删了）就是筛选栏上同名、还没被前面的线认领、比这封信早的主题里最近的那个；都对不上时用这封信的 id。
 */
private fun threadIdOf(firstMessageId: Int, title: String?, threads: List<PmThreadRef>, claimed: Set<Int>): Int {
    if (threads.any { it.id == firstMessageId }) return firstMessageId
    return threads.filter { it.title == title && it.id !in claimed && it.id < firstMessageId }.maxOfOrNull { it.id } ?: firstMessageId
}

/**
 * 发信没成功时网页停在写信页，原因写在输入框旁的 `span.alarm` 里 → [RejectReason]：
 * 「收件人不存在」是 [RejectReason.PM_RECEIVER_NOT_FOUND]，其余（包括读不出原因）一律 [RejectReason.PM_NOT_SENT]。
 */
internal fun pmRejectionOf(html: String): RejectReason {
    val alarms = webDocumentOf(html).select("span.alarm").map { it.text() }.filter { it.isNotBlank() }
    return if (alarms.any { "收件人不存在" in it }) RejectReason.PM_RECEIVER_NOT_FOUND else RejectReason.PM_NOT_SENT
}

/** 发信成功时网页跳去的会话地址里的会话 id；不是会话地址为 null。 */
internal fun conversationIdOfLocation(location: String): Int? = CONVERSATION_HREF.find(location)?.groupValues?.get(1)?.toIntOrNull()

/**
 * 网页的未读数 JSON（`/json/notify`，网页顶栏自己的脚本也读它）里的未读短信：`pm_count` 与「全部已读」地址 `pm_ignore_url`。
 * 读不出这两项按坏响应处理（不是 JSON 时解析器抛的异常经 `toFailure()` 也是坏响应）。
 */
internal fun unreadMessagesOf(json: String): WebUnreadMessages {
    val root = Json.parseToJsonElement(json) as? JsonObject ?: throw FailureException(Failure.BadResponse)
    val count = (root["pm_count"] as? JsonPrimitive)?.intOrNull ?: throw FailureException(Failure.BadResponse)
    val link = (root["pm_ignore_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw FailureException(Failure.BadResponse)
    return WebUnreadMessages(count, link)
}
