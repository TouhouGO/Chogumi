package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.PmSamples
import io.github.touhougo.chogumi.core.network.model.web.PmConversationRow
import io.github.touhougo.chogumi.core.network.model.web.PmThreadRef
import io.github.touhougo.chogumi.core.network.model.web.WebUnreadMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 旧站短信页的解析：样本是按网页结构构造的虚构页面（[PmSamples]）。 */
class PmPageParsersTest {

    @Test
    fun `会话列表读出每个会话_对方uid只从自定义头像读_默认头像为null_摘要保留BBCode与换行`() {
        val rows = pmConversationRowsOf(PmSamples.read("conversation-all-tags.html"))

        assertEquals(listOf(300003, 300001), rows.map { it.id })
        val test = rows[0]
        assertEquals("anonymous", test.peerName)
        assertEquals(400100, test.peerId)
        assertEquals("https://lain.bgm.tv/pic/user/l/000/40/01/400100_a1B2c.jpg?r=1700000000&hd=1", test.peerAvatar)
        assertEquals("2026-10-26", test.date)
        assertEquals(
            "Chogumi 测试 / [b]粗体[/b] [i]斜体[/i] [u]下划线[/u] [s]删除线[/s] [mask]遮罩[/mask]\n[size=25]大字[/size] ...",
            test.preview,
        )
        assertEquals(0, test.unread)
        assertEquals(PmConversationRow(300001, "示例用户", "https://lain.bgm.tv/pic/user/l/icon.jpg", null, "2021-3-8", "Re:示例主题 / 示例正文", 0), rows[1])
    }

    @Test
    fun `会话列表读出未读条数`() {
        val row = pmConversationRowsOf(PmSamples.read("peer-inbox-unread.html")).single()

        assertEquals(2, row.unread)
        assertEquals(400200, row.peerId)
        assertEquals("Chogumi 测试回复：普通回复一次。", row.preview)
    }

    @Test
    fun `没有更多会话的页是空列表_不是短信页按坏响应`() {
        assertEquals(emptyList(), pmConversationRowsOf(PmSamples.read("inbox-empty.html")))

        val e = assertFailsWith<FailureException> { pmConversationRowsOf("""<form action="/FollowTheRabbit"></form>""") }
        assertEquals(Failure.BadResponse, e.failure)
    }

    @Test
    fun `会话读出对方_主题_每封信所在的线_回复默认最新那条线`() {
        val page = assertNotNullPage("conversation-two-threads.html")

        assertEquals(400100, page.peerId)
        assertEquals("400100", page.peerKey)
        assertEquals("anonymous", page.peerName)
        assertEquals(listOf(PmThreadRef(500003, "Chogumi 测试新主题"), PmThreadRef(500001, "Chogumi 测试")), page.threads)
        assertEquals(listOf(500001, 500002, 500003), page.messages.map { it.id })
        assertEquals(listOf(500001, 500001, 500003), page.messages.map { it.threadId })
        assertEquals(listOf(true, true, true), page.messages.map { it.mine })
        assertEquals(listOf("2026-10-26 12:09", "2026-10-26 12:10", "2026-10-26 12:10"), page.messages.map { it.sentAt })
        assertEquals("Chogumi 测试回复：普通回复一次。", page.messages[1].bodyHtml)
        assertEquals(500003, page.replyThreadId)
    }

    @Test
    fun `对方发来的信不是自己的_对方uid取回复表单的收件人`() {
        val page = assertNotNullPage("peer-conversation.html")

        assertEquals(400200, page.peerId)
        assertEquals("示例好友", page.peerName)
        assertEquals(listOf(false, false), page.messages.map { it.mine })
    }

    @Test
    fun `正文是网页的HTML原样_空白与换行不被重排`() {
        val html = PmSamples.read("conversation-all-tags.html")
        val original = html.substringAfter("""<div class="pm-message-body">""").substringBefore("\n<div class=\"pm-message-info\">").removeSuffix("</div>")

        assertEquals(original, assertNotNullPage("conversation-all-tags.html").messages.single().bodyHtml)
    }

    @Test
    fun `线的第一封被删了_按主题名认回筛选栏上的主题`() {
        val html = PmSamples.read("conversation-two-threads.html")
            .replace(Regex("""<div id="msg_500001".*?</div>\s*</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL), "")

        val page = requireNotNull(pmConversationOf(html))

        assertEquals(listOf(500002, 500003), page.messages.map { it.id })
        assertEquals(listOf(500001, 500003), page.messages.map { it.threadId })
    }

    @Test
    fun `短信页上没有打开的会话为null`() {
        assertNull(pmConversationOf(PmSamples.read("inbox.html")))
    }

    @Test
    fun `发信出错页按输入框旁的那一句给原因`() {
        assertEquals(RejectReason.PM_RECEIVER_NOT_FOUND, pmRejectionOf(PmSamples.read("create-error-no-receiver.html")))
        assertEquals(RejectReason.PM_NOT_SENT, pmRejectionOf(PmSamples.read("compose.html")), "停在写信页但读不出原因")
    }

    @Test
    fun `未读数JSON读出未读短信与全部已读的地址`() {
        assertEquals(WebUnreadMessages(2, "/pm/ignore_unread.chii?gh=FORMHASH"), unreadMessagesOf(PmSamples.read("peer-notify-unread.json")))
        assertEquals(WebUnreadMessages(0, "/pm/ignore_unread.chii?gh=FORMHASH"), unreadMessagesOf(PmSamples.read("notify.json")))

        val missing = assertFailsWith<FailureException> { unreadMessagesOf("""{"count":0}""") }
        assertEquals(Failure.BadResponse, missing.failure)
        val page = assertFailsWith<FailureException> { unreadMessagesOf("<html></html>") }
        assertEquals(Failure.BadResponse, page.failure, "回的是网页不是 JSON")
    }

    @Test
    fun `发信后跳去的地址读出会话id`() {
        assertEquals(300003, conversationIdOfLocation("/pm/conversation/300003.chii"))
        assertEquals(300003, conversationIdOfLocation("https://bgm.tv/pm/conversation/300003.chii?page=1"))
        assertNull(conversationIdOfLocation("/pm"))
    }

    private fun assertNotNullPage(name: String) = requireNotNull(pmConversationOf(PmSamples.read(name))) { name }
}
