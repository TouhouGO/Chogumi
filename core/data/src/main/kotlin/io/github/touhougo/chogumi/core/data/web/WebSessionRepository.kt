package io.github.touhougo.chogumi.core.data.web

import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.StoredWebSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.network.api.WebAccount
import io.github.touhougo.chogumi.core.network.api.WebSessionApi
import io.github.touhougo.chogumi.core.network.api.webUserIdOf
import io.github.touhougo.chogumi.core.network.model.web.PmOutgoing
import io.github.touhougo.chogumi.core.network.model.web.ProfileForm
import io.github.touhougo.chogumi.core.network.model.web.WebReplyParent
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.github.touhougo.chogumi.core.network.model.web.WebUnreadMessages

/**
 * 当前账号的 bgm.tv 网页会话（Cookie）：账号密码登录时随令牌一起拿到，与令牌存在同一个凭证文件里，Cookie 加密。
 * 只给开放接口做不了的事用（写操作，与只有网页才有的短信）；「谁登录了」仍只由 `SessionState` 回答，网页会话只是这个账号身上的一样东西，
 * 登出、换号时与令牌一起清掉（[TokenStore]）。
 */
class WebSessionRepository(
    private val tokenStore: TokenStore,
    private val cipher: CookieCipher,
    private val api: WebSessionApi,
) {

    /** 给刚登录好的 [expected] 存上网页会话；Cookie 加不了密就不存（与解不开同样当作没有网页会话）。 */
    suspend fun save(expected: AccountSession, session: WebSession) {
        val sealed = cipher.seal(session.cookie) ?: return
        tokenStore.saveWebSession(expected, StoredWebSession(sealed))
    }

    /**
     * 带网页会话打开一个操作链接：[link] 拿表单校验值（链接上的 `gh=`）拼出地址（core:model `BgmWeb`）。
     * 先打开一个登录后才有内容的页面，确认网页上登录的还是当前账号。没有网页会话、解不开、网页那边已登出或换了人，
     * 都抛「要账号密码登录」，后三种顺手丢掉存着的会话。网页只回跳转，做没做成由调用方回读接口确认。
     * [referer] 是网页上这个链接所在的页（同在网页上点一样带上）；没有就不带。
     */
    suspend fun perform(knownSession: AccountSession? = null, referer: String? = null, link: suspend (formhash: String) -> String) {
        val expected = knownSession ?: tokenStore.requireSession()
        tokenStore.withSessionToken(expected) { _, _ ->
            val (session, account) = verified(expected)
            val url = link(account.formhash)
            api.follow(session, url, referer, beforeSend = { tokenStore.requireCurrent(expected) })
        }
    }

    /**
     * 带网页会话打开 [page]，由 [pick] 从页面里找出操作链接（页面上写的 href，相对这一页解析）再打开，Referer 是这一页，同在网页上点。
     * 用在操作链接只写在页面上、拼不出来的地方。页面上没有这个操作（[pick] 为 null）抛 [missing]。会话的确认同 [perform]。
     */
    suspend fun performOnPage(
        page: String,
        missing: Failure,
        knownSession: AccountSession? = null,
        pick: (html: String) -> String?,
    ) {
        val expected = knownSession ?: tokenStore.requireSession()
        tokenStore.withSessionToken(expected) { _, _ ->
            val (session, _) = verified(expected)
            val html = api.page(session, page)
            tokenStore.requireCurrent(expected)
            val link = pick(html) ?: throw FailureException(missing)
            api.follow(session, link, referer = page, beforeSend = { tokenStore.requireCurrent(expected) })
        }
    }

    /**
     * 带网页会话绝交 [userId] 给出的用户（提交隐私设置页的绝交表单）。会话的确认同 [perform]，确认之后才取对方 id：
     * 没有网页会话时原因就是「要账号密码登录」，不多发请求。做没做成由调用方回读接口确认。
     */
    suspend fun block(knownSession: AccountSession, userId: suspend () -> Int) {
        tokenStore.withSessionToken(knownSession) { _, _ ->
            val (session, account) = verified(knownSession)
            api.block(session, account.formhash, userId(), beforeSend = { tokenStore.requireCurrent(knownSession) })
        }
    }

    /**
     * 带网页会话取消与 [userId] 给出的用户的绝交（点隐私设置页绝交名单上对方那一行的取消链接）。
     * 会话的确认与取对方的时机同 [block]；做没做成由调用方回读接口确认。
     */
    suspend fun unblock(knownSession: AccountSession, userId: suspend () -> Int) {
        tokenStore.withSessionToken(knownSession) { _, _ ->
            val (session, _) = verified(knownSession)
            api.unblock(session, userId(), beforeSend = { tokenStore.requireCurrent(knownSession) })
        }
    }

    /**
     * 带网页会话删除自己的动态 [id]（在自己的时间线页 [pageOf] 上找删除链接再提交）。会话的确认同 [perform]。
     * 网页回的是 JSON 结果，不回读接口：动态读取走服务端缓存，刚删完仍会读到。
     */
    suspend fun eraseTimeline(knownSession: AccountSession, id: Long, pageOf: (page: Int) -> String) {
        tokenStore.withSessionToken(knownSession) { _, _ ->
            val (session, _) = verified(knownSession)
            api.eraseTimeline(session, id, pageOf, beforeSend = { tokenStore.requireCurrent(knownSession) })
        }
    }

    /**
     * 带网页会话在 [page]（话题、单集、日志、角色人物、目录的网页）上回复一条 [content]，回复一层楼时带 [parent]。
     * 会话的确认同 [perform]；发没发出去由调用方回读楼层确认。
     */
    suspend fun reply(knownSession: AccountSession, page: String, content: String, parent: WebReplyParent?) {
        tokenStore.withSessionToken(knownSession) { _, _ ->
            val (session, _) = verified(knownSession)
            api.reply(session, page, content, parent, beforeSend = { tokenStore.requireCurrent(knownSession) })
        }
    }

    /** 带网页会话把个人设置页上的「显示 NSFW 条目」改成 [show]（整张表单原样提交，只改这一项）。会话的确认同 [perform]。 */
    suspend fun setShowNsfw(knownSession: AccountSession, show: Boolean) {
        tokenStore.withSessionToken(knownSession) { _, _ ->
            val (session, _) = verified(knownSession)
            api.setShowNsfw(session, show, beforeSend = { tokenStore.requireCurrent(knownSession) })
        }
    }

    /**
     * 带网页会话改个人资料（个人设置页的表单现读现交，只换 [form] 里给了的项，头像随表单上传）。会话的确认同 [perform]；
     * 做没做成由调用方回读接口确认。
     */
    suspend fun editProfile(knownSession: AccountSession, form: ProfileForm) {
        tokenStore.withSessionToken(knownSession) { _, _ ->
            val (session, _) = verified(knownSession)
            api.editProfile(session, form, beforeSend = { tokenStore.requireCurrent(knownSession) })
        }
    }

    /**
     * [knownSession] 有没有存着的网页会话（只看本地，不发请求）：没有的（浏览器授权、手动 Token 登录）只能看、不能做网页才有的事。
     * 存着的会话在网页那边失效了，要等下一次操作才发现，那时它被丢掉，这里随之变成 false。
     */
    suspend fun available(knownSession: AccountSession): Boolean = tokenStore.webSessionFor(knownSession) != null

    /**
     * 带网页会话读 [url] 这一页（只读：短信列表、会话）。页面自己写着登录的是谁（`CHOBITS_UID`），是当前账号就回页面内容，
     * 不多开账号页。不是（没登录、换了人）或页面回了跳转，就像 [perform] 那样打开账号页确认：会话失效时丢掉它、抛「要账号密码登录」；
     * 会话仍然有效时，回跳转的页为 null（是什么意思由调用方按这一页解释），写着别人的页按坏响应处理。
     */
    suspend fun page(knownSession: AccountSession, url: String): String? = tokenStore.withSessionToken(knownSession) { _, _ ->
        val html = api.pageOrNull(stored(knownSession), url)
        tokenStore.requireCurrent(knownSession)
        if (html != null && webUserIdOf(html) == knownSession.userId) {
            html
        } else {
            verified(knownSession)
            if (html != null) throw FailureException(Failure.BadResponse)
            null
        }
    }

    /**
     * 当前账号网页上的未读短信。没有网页会话（浏览器授权、手动 Token 登录）或解不开时为 null，不发请求。
     * 只读一个小 JSON，不先开账号页确认（未读数随前台、社区页刷新，常读）：网页会话失效由读短信页、写操作发现。
     */
    suspend fun unreadMessages(knownSession: AccountSession): WebUnreadMessages? = tokenStore.withSessionToken(knownSession) { _, _ ->
        val session = tokenStore.webSessionFor(knownSession)?.let { cipher.open(it.sealedCookie) }?.let(::WebSession)
        session?.let { api.unreadMessages(it, BgmWeb.WEB_NOTIFY) }
    }

    /**
     * 带网页会话发一封短信 [message]，回网页跳去的会话 id；[referer] 是网页上这张表单所在的页（会话页或写信页）。
     * 会话的确认同 [perform]；没发出去抛网页给的原因（`pmRejectionOf`）。
     */
    suspend fun sendMessage(knownSession: AccountSession, message: PmOutgoing, referer: String): Int =
        tokenStore.withSessionToken(knownSession) { _, _ ->
            val (session, account) = verified(knownSession)
            api.sendMessage(session, BgmWeb.PM_CREATE, account.formhash, message, referer, beforeSend = { tokenStore.requireCurrent(knownSession) })
        }

    /** 带网页会话把未读短信全部标为已读（「全部已读」的地址取自网页的未读数 JSON，不手拼）。会话的确认同 [perform]。 */
    suspend fun markMessagesRead(knownSession: AccountSession) {
        tokenStore.withSessionToken(knownSession) { _, _ ->
            val (session, _) = verified(knownSession)
            val unread = api.unreadMessages(session, BgmWeb.WEB_NOTIFY)
            tokenStore.requireCurrent(knownSession)
            api.markMessagesRead(session, unread.markAllReadLink, beforeSend = { tokenStore.requireCurrent(knownSession) })
        }
    }

    /** 数字 id 对应的用户名（公开网页，不用会话）；查不到是 null。 */
    suspend fun usernameOfId(id: Int): String? = api.usernameOfId(id)

    /** 确认网页上登录的还是当前账号，回会话与它的账号（表单校验值在里面）；做不到时丢掉会话、抛「要账号密码登录」。 */
    private suspend fun verified(expected: AccountSession): Pair<WebSession, WebAccount> {
        val session = stored(expected)
        val account = try {
            api.account(session)
        } catch (e: FailureException) {
            if (e.failure == Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)) discard(expected)
            throw e
        }
        tokenStore.requireCurrent(expected)
        if (account.userId != expected.userId) discard(expected)
        return session to account
    }

    /** 存着的网页会话；没有时抛「要账号密码登录」，解不开时丢掉它再抛。 */
    private suspend fun stored(expected: AccountSession): WebSession {
        val stored = tokenStore.webSessionFor(expected) ?: throw loginRequired()
        return cipher.open(stored.sealedCookie)?.let(::WebSession) ?: discard(expected)
    }

    private suspend fun discard(expected: AccountSession): Nothing {
        tokenStore.clearWebSession(expected)
        throw loginRequired()
    }

    private fun loginRequired() = FailureException(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED))
}
