package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.common.bbcode.bbcodeOfHtml
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcomeIn
import io.github.touhougo.chogumi.core.data.util.LruMap
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.PmConversation
import io.github.touhougo.chogumi.core.model.PmConversationSummary
import io.github.touhougo.chogumi.core.model.PmDraft
import io.github.touhougo.chogumi.core.model.PmMessage
import io.github.touhougo.chogumi.core.model.PmThread
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.network.api.pmConversationOf
import io.github.touhougo.chogumi.core.network.api.pmConversationRowsOf
import io.github.touhougo.chogumi.core.network.model.web.PmConversationPage
import io.github.touhougo.chogumi.core.network.model.web.PmConversationRow
import io.github.touhougo.chogumi.core.network.model.web.PmOutgoing
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 站内短信。开放接口没有短信，全部走旧站网页与账号密码登录得到的网页会话（[WebSessionRepository]），以网页为准：
 * 会话列表与会话读网页（页面自己写着登录的是谁，[WebSessionRepository.page] 据此确认账号），发信照网页的表单提交，
 * 删信打开网页的删除链接。没有网页会话（浏览器授权、手动 Token 登录）时一律是「要账号密码登录」。
 *
 * 一次操作一个账号：每个方法在开始时取当前账号（[authenticatedOutcome]），读写与确认都对着它，途中换号或登出是 [Failure.SessionChanged]。
 *
 * 网页打开会话就把它算作读过，所以「读过」只有一个来源：读会话的那一个函数（[read]）。读到会话后它发出这件事（[conversationsRead]，
 * 面板据此去掉这一行的未读数），并让通知重读未读短信数（[NotificationRepository.messagesChanged]，不等它）；读不到就什么都不发，
 * 这一行仍是未读。看会话、按人找会话、删信后的回读都经过它。
 *
 * 本机记着「对方 uid → 会话 id」（按账号，最多 [PEER_MEMO_SIZE] 个）：读到的会话、列表里读得出 uid 的行、发信后网页跳去的会话都记下，
 * 给「和这个人已经有会话就直接进去」用（[conversationWith]、[findConversation]）。
 */
class PmRepository(
    private val tokenStore: TokenStore,
    private val webSessions: WebSessionRepository,
    private val notifications: NotificationRepository,
    private val scope: CoroutineScope,
    private val accountMe: AccountMe,
    private val computationDispatcher: CoroutineDispatcher,
) {
    /** （账号 uid, 对方 uid）→ 会话 id。 */
    private val conversationsByPeer = LruMap<Pair<Int, Int>, Int>(PEER_MEMO_SIZE)

    /** 对方 uid → 用户名（[usernameOf]）。用户名是公开的、不分账号。 */
    private val usernames = LruMap<Int, String>(PEER_MEMO_SIZE)

    private val reads = MutableSharedFlow<Int>(extraBufferCapacity = READ_BUFFER)

    /** 读成功了的会话 id（见类注释）。只发给正在收的；读过的会话在之后取到的列表里本来就没有未读。 */
    val conversationsRead: SharedFlow<Int> = reads.asSharedFlow()

    /**
     * 会话列表的一页（给 `Pager`：位置是从 0 起的页号，网页的第 `位置 + 1` 页），最近的会话在前。
     * 网页不给总数；一页没有会话就是到底了，有会话就还有下一页。
     */
    suspend fun conversations(position: Int): Outcome<PagedResult<PmConversationSummary>> = tokenStore.authenticatedOutcome { session, _ ->
        outcomeOf {
            val html = webSessions.page(session, BgmWeb.pmInbox(position + 1)) ?: throw FailureException(Failure.BadResponse)
            val inbox = parseInbox(html)
            inbox.rows.forEach { row -> row.peerId?.let { remember(session, it, row.id) } }
            PagedResult(inbox.summaries, total = 0, nextOffset = if (inbox.rows.isEmpty()) null else position + 1)
        }
    }

    /** 会话 [id] 的全部短信；会话不在（信已经删光）为 null。读到即算读过（见类注释）。 */
    suspend fun conversation(id: Int): Outcome<PmConversation?> = tokenStore.authenticatedOutcome { session, _ ->
        outcomeOf { read(session, id) }
    }

    /**
     * 发一封短信（新信、回复、新主题），回网页跳去的会话 id：跳到会话就是发出去了，停在写信页抛网页给的原因
     * （[RejectReason.PM_RECEIVER_NOT_FOUND]、[RejectReason.PM_NOT_SENT]）。[conversationId] 是从哪个会话回复的
     * （网页上这张表单所在的页）；新信为 null，表单所在的页是写给对方的写信页。
     */
    suspend fun send(draft: PmDraft, conversationId: Int? = null): Outcome<Int> = tokenStore.authenticatedOutcome { session, _ ->
        outcomeOf {
            val message = PmOutgoing(draft.receiverId, draft.title, draft.body, draft.replyTo, draft.newTopic)
            val referer = conversationId?.let(BgmWeb::pmConversation) ?: BgmWeb.privateMessage(draft.receiverId)
            webSessions.sendMessage(session, message, referer).also { remember(session, draft.receiverId, it) }
        }
    }

    /**
     * 从自己的短信里删掉会话 [conversationId] 里的一封 [messageId]（不是撤回），回删完之后的会话；这是会话里的最后一封时，
     * 会话从列表里消失，回 null。网页删除只回跳转，做没做成重读会话确认：那封还在是 [RejectReason.PM_DELETE_UNAVAILABLE]。
     *
     * 捕获当前账号后再返回删除动作；页面把动作排队时，账号属于用户点删除的这一刻，而不是排到队首的时刻。
     */
    fun prepareErase(conversationId: Int, messageId: Int): suspend () -> Outcome<PmConversation?> {
        val session = tokenStore.captureSession()
        return { tokenStore.authenticatedOutcomeIn(session) { current, _ -> erase(current, conversationId, messageId) } }
    }

    private suspend fun erase(session: AccountSession, conversationId: Int, messageId: Int): Outcome<PmConversation?> =
        outcomeOf {
            webSessions.perform(session, referer = BgmWeb.pmConversation(conversationId)) { formhash -> BgmWeb.pmErase(messageId, formhash) }
            val after = read(session, conversationId)
            if (after != null && after.messages.any { it.id == messageId }) throw FailureException(Failure.Rejected(RejectReason.PM_DELETE_UNAVAILABLE))
            if (after == null) forget(session, conversationId)
            after
        }

    /**
     * 当前账号和 [peer] 的会话；找不到为 null（要写的是第一封）。网页不能按人查会话，所以先看本机记得的，
     * 没记过就读会话列表第一页（读得出 uid 的行顺手记下）再看，最后按名字试读不出 uid 的行（默认头像的人）：
     * 读出会话后对方 uid 对得上才算，同名的人不会被当成对方。读到的每个会话都算读过（见类注释）。
     */
    suspend fun findConversation(peer: UserBo): Outcome<PmConversation?> = tokenStore.authenticatedOutcome { session, _ ->
        outcomeOf {
            readWith(session, peer.id, conversationWith(peer.id)) ?: run {
                val html = webSessions.page(session, BgmWeb.pmInbox(1)) ?: throw FailureException(Failure.BadResponse)
                val rows = parseInbox(html).rows
                rows.forEach { row -> row.peerId?.let { remember(session, it, row.id) } }
                val byName = rows.filter { it.peerId == null && it.peerName.trim() == peer.nickname.trim() }.map { it.id }
                (listOfNotNull(conversationWith(peer.id)) + byName).distinct().firstNotNullOfOrNull { readWith(session, peer.id, it) }
            }
        }
    }

    /** 本机记得的、当前账号与 [peerId] 的会话 id；没记过为 null（不代表没有会话）。 */
    suspend fun conversationWith(peerId: Int): Int? {
        val userId = tokenStore.session().userId ?: return null
        return synchronized(conversationsByPeer) { conversationsByPeer[userId to peerId] }
    }

    /** 读会话 [id]（null 不读），对方是 [peerId] 才算；会话不在了从本机记的里去掉。 */
    private suspend fun readWith(session: AccountSession, peerId: Int, id: Int?): PmConversation? {
        if (id == null) return null
        val conversation = read(session, id)
        if (conversation == null) forget(session, id)
        return conversation?.takeIf { it.peer.id == peerId }
    }

    private suspend fun read(session: AccountSession, id: Int): PmConversation? {
        val html = webSessions.page(session, BgmWeb.pmConversation(id)) ?: return null
        val page = withContext(computationDispatcher) { pmConversationOf(html) } ?: return null
        remember(session, page.peerId, id)
        val username = usernameOf(page)
        val zone = accountMe.webZone(session)
        val conversation = withContext(computationDispatcher) { page.toConversation(id, username, zone) }
        reads.tryEmit(id)
        scope.launch { notifications.messagesChanged() }
        return conversation
    }

    private suspend fun parseInbox(html: String): ParsedInbox = withContext(computationDispatcher) {
        val rows = pmConversationRowsOf(html)
        ParsedInbox(rows, rows.map { it.toSummary() })
    }

    /**
     * 对方的用户名：会话页上对方主页的链接写的是数字 uid（`/user/{uid}`），设了用户名的人按 uid 查资料是 404，点头像打不开主页。
     * 链接里不是 uid 时就用它；是 uid（或没有链接）时经公开主页的跳转换成用户名（[WebSessionRepository.usernameOfId]，
     * 没设用户名的人换到的就是 uid 本身），换到的记下，同一个人不再问。换不到时退回 uid：主页照样能开没设用户名的人。
     */
    private suspend fun usernameOf(page: PmConversationPage): String {
        if (page.peerKey.isNotEmpty() && page.peerKey.toIntOrNull() == null) return page.peerKey
        synchronized(usernames) { usernames[page.peerId] }?.let { return it }
        val name = outcomeOf { webSessions.usernameOfId(page.peerId) }.valueOrNull() ?: return page.peerId.toString()
        synchronized(usernames) { usernames[page.peerId] = name }
        return name
    }

    private fun remember(session: AccountSession, peerId: Int, conversationId: Int) {
        val userId = session.userId ?: return
        synchronized(conversationsByPeer) { conversationsByPeer[userId to peerId] = conversationId }
    }

    private fun forget(session: AccountSession, conversationId: Int) {
        val userId = session.userId ?: return
        synchronized(conversationsByPeer) {
            conversationsByPeer.entries.removeAll { (key, value) -> key.first == userId && value == conversationId }
        }
    }

    private companion object {
        const val PEER_MEMO_SIZE = 200

        /** 收的一方一时没跟上也不丢：一次操作最多读几个会话（按人找会话时的同名候选），远小于它。 */
        const val READ_BUFFER = 64
    }
}

private data class ParsedInbox(
    val rows: List<PmConversationRow>,
    val summaries: List<PmConversationSummary>,
)

/** 网页的日期（`2026-10-26`、`2021-3-8`）。 */
private val PM_DATE = DateTimeFormatter.ofPattern("y-M-d")

/** 网页的时刻（`2026-10-26 12:09`）。 */
private val PM_TIME = DateTimeFormatter.ofPattern("y-M-d H:mm")

private fun PmConversationRow.toSummary() = PmConversationSummary(
    id = id,
    peer = peerOf(peerId ?: 0, username = "", nickname = peerName, avatar = peerAvatar),
    date = try {
        LocalDate.parse(date, PM_DATE)
    } catch (e: DateTimeException) {
        null
    },
    preview = preview,
    unread = unread,
)

/** 网页按账号设置的时区写时刻；读不出记 0。 */
private fun webInstantOf(text: String, format: DateTimeFormatter, zone: ZoneId): Long = try {
    LocalDateTime.parse(text, format).atZone(zone).toInstant().toEpochMilli()
} catch (e: DateTimeException) {
    0L
}

private fun PmConversationPage.toConversation(id: Int, peerUsername: String, zone: ZoneId) = PmConversation(
    id = id,
    peer = peerOf(peerId, username = peerUsername, nickname = peerName, avatar = peerAvatar),
    threads = threads.map { PmThread(it.id, it.title) },
    messages = messages.map { PmMessage(it.id, it.threadId, it.mine, bbcodeOfHtml(it.bodyHtml), webInstantOf(it.sentAt, PM_TIME, zone)) },
    replyThreadId = replyThreadId,
)

/** 网页上只有一个尺寸的头像，三个尺寸都用它。 */
private fun peerOf(id: Int, username: String, nickname: String, avatar: String) =
    UserBo(id = id, username = username, nickname = nickname, avatarLarge = avatar, avatarMedium = avatar, avatarSmall = avatar)
