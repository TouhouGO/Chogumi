package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.foundation.BGM_SITE
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.WebOperation
import io.github.touhougo.chogumi.core.network.model.web.PmOutgoing
import io.github.touhougo.chogumi.core.network.model.web.ProfileForm
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.github.touhougo.chogumi.core.network.model.web.WebUnreadMessages
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 网页会话当前的账号：[userId] 是网页上登录的人，[formhash] 是这个会话的表单校验值（操作链接上的 `gh=`）。 */
data class WebAccount(val userId: Int, val formhash: String)

/**
 * 带登录 Cookie 访问 bgm.tv 网页：只给开放接口做不了、网页能做的事用（取消收藏角色、解除好友…，以及只有网页才有的短信）。
 *
 * 用网页客户端（networkModule 里构造，与 [WebLoginApi] 共用）：不跟随跳转（操作链接做完只回一个跳转，
 * 跟过去没有意义），不走 HTTP 缓存，不带 OAuth 令牌。每个带 Cookie 的请求先过 [requireBgmSite]，只发往本站。网页没有契约，操作成没成由调用方回读开放接口确认，这里不猜。
 */
class WebSessionApi(private val client: HttpClient) {

    /** 个人设置页那张表单一次只改一回（[editSettings]）。 */
    private val settingsForm = Mutex()

    /**
     * 会话现在是谁、表单校验值是多少：打开一个只有登录后才有内容的页面读出来。
     * 没登录（Cookie 过期、被登出）抛「要网页登录」；页面里读不出来按坏响应处理。
     */
    suspend fun account(session: WebSession): WebAccount = parseWebAccount(fetch(session, "$BGM_SITE$ACCOUNT_PAGE").bodyAsText())

    /** 带会话打开一个网页，回页面内容：操作链接只写在页面上时，从这里找。 */
    suspend fun page(session: WebSession, url: String): String = fetch(session, url).bodyAsText()

    /**
     * 带会话读一页（只读，不是操作链接）：回页面内容；页面回跳转时为 null，跳转是什么意思由调用方按这一页解释
     * （没登录被送去登录页、要打开的东西已经不在）。
     */
    suspend fun pageOrNull(session: WebSession, url: String): String? {
        val response = fetch(session, url)
        return if (response.status.value in 300..399) null else response.bodyAsText()
    }

    /**
     * 发一封短信：照网页的写信、回复表单提交到 [url]（`BgmWeb.PM_CREATE`），[referer] 是表单所在的页。
     * 发出去了网页跳到那个会话，回它的会话 id；没发出去网页停在写信页，原因写在输入框旁，换成 [pmRejectionOf] 的原因抛出。
     * 跳到别处读不出会话 id，按坏响应处理。
     */
    suspend fun sendMessage(
        session: WebSession,
        url: String,
        formhash: String,
        message: PmOutgoing,
        referer: String,
        beforeSend: suspend () -> Unit,
    ): Int {
        val fields = buildList {
            add("formhash" to formhash)
            add("msg_receivers" to message.receiverId.toString())
            message.related?.let { add("related" to it.toString()) }
            if (message.newTopic) add("new_topic" to "1")
            add("msg_title" to message.title)
            add("msg_body" to message.body)
            add("submit" to PM_SUBMIT)
        }
        beforeSend()
        val response = client.submitForm(
            url = requireBgmSite(url),
            formParameters = parameters { fields.forEach { (name, value) -> append(name, value) } },
        ) {
            header(HttpHeaders.Cookie, session.cookie)
            header(HttpHeaders.Referrer, referer)
        }
        return when (response.status.value) {
            in 300..399 -> response.headers[HttpHeaders.Location]?.let(::conversationIdOfLocation) ?: throw FailureException(Failure.BadResponse)
            in 200..299 -> throw FailureException(Failure.Rejected(pmRejectionOf(response.bodyAsText())))
            else -> throw FailureException(Failure.Http(response.status.value))
        }
    }

    /** 网页上的未读短信（[unreadMessagesOf]），读 [url]（`BgmWeb.WEB_NOTIFY`）。 */
    suspend fun unreadMessages(session: WebSession, url: String): WebUnreadMessages = unreadMessagesOf(fetch(session, url).bodyAsText())

    /**
     * 把未读短信全部标为已读：照网页自己的脚本，打开 [link]（[WebUnreadMessages.markAllReadLink]，相对本站解析）加 `ajax=1`，
     * 网页回 JSON，`status` 为 `ok` 才算做成；没回 `ok` 按坏响应抛出。
     */
    suspend fun markMessagesRead(session: WebSession, link: String, beforeSend: suspend () -> Unit) {
        val url = Url(BGM_SITE).resolve(link) + (if ('?' in link) "&" else "?") + "ajax=1"
        beforeSend()
        val answer = fetch(session, url, operation = true)
        if (!ERASE_OK.containsMatchIn(answer.bodyAsText())) throw FailureException(Failure.BadResponse)
    }

    /**
     * 打开一个操作链接（带 `gh=`）。[referer] 是链接所在的网页：从页面上找到的链接照页面上写的给，相对这一页解析，
     * 同在网页上点一样带上 Referer。网页对它回跳转，或一页提示；有的操作先回一页确认表单（提交到这个操作自己），
     * 那就照页面提交它，同在网页上点「确定」。每次发送前由 [beforeSend] 确认操作所属会话仍有效；
     * 做没做成由调用方回读接口确认。
     */
    suspend fun follow(session: WebSession, link: String, referer: String? = null, beforeSend: suspend () -> Unit) {
        val url = if (referer == null) link else Url(referer).resolve(link)
        beforeSend()
        val response = fetch(session, url, referer, operation = true)
        if (response.status.value !in 200..299) return
        val form = parsePostForm(response.bodyAsText(), Url(url).encodedPath) ?: return
        beforeSend()
        post(session, Url(url).resolve(form.action), form, referer = url)
    }

    /**
     * 绝交 [userId]：提交隐私设置页上的绝交表单（`ignore_user` 填对方的数字 id），同在网页上填好点「保存」。
     * 网页只回跳转或一页提示，做没做成由调用方回读接口确认。
     */
    suspend fun block(session: WebSession, formhash: String, userId: Int, beforeSend: suspend () -> Unit) {
        val page = "$BGM_SITE$ACCOUNT_PAGE"
        beforeSend()
        post(session, page, WebForm(page, listOf("ignore_user" to userId.toString(), "formhash" to formhash, "submit_ignore" to "保存")), referer = page)
    }

    /**
     * 数字 id 对应的用户名：主页 `/user/{id}` 是公开的，对有用户名的人回跳转到 `/user/{用户名}`（不带 Cookie）；
     * 没有跳转（用户名就是 id）回 id 本身，页面不存在或读不出来回 null。接口按用户名查资料，id 到用户名只有这一步。
     */
    suspend fun usernameOfId(id: Int): String? {
        val response = client.get("$BGM_SITE/user/$id")
        return when (response.status.value) {
            in 300..399 -> response.headers[HttpHeaders.Location]?.let { USER_HREF.matchEntire(it)?.groupValues?.get(1) }
            in 200..299 -> id.toString()
            else -> null
        }
    }

    /** 隐私设置页上的绝交名单（[blocklistRowsOf]）。 */
    suspend fun blocklist(session: WebSession): List<BlocklistRow> = blocklistRowsOf(fetch(session, "$BGM_SITE$ACCOUNT_PAGE").bodyAsText())

    /**
     * 取消与 [userId] 的绝交：打开隐私设置页，点绝交名单里对方那一行的取消链接（`ignore_reset={id}`）。
     * 名单里没有对方时什么也不做；做没做成由调用方回读接口确认。
     */
    suspend fun unblock(session: WebSession, userId: Int, beforeSend: suspend () -> Unit) {
        beforeSend()
        val row = blocklist(session).firstOrNull { it.id == userId } ?: return
        // 取消链接就是隐私设置页自己的地址：回的是跳转或这一页，页面上那几张表单不是要确认的，不走 [follow] 的确认表单提交。
        beforeSend()
        fetch(session, Url("$BGM_SITE$ACCOUNT_PAGE").resolve(row.unblockLink), "$BGM_SITE$ACCOUNT_PAGE", operation = true)
    }

    /**
     * 把个人设置页上「显示 NSFW 条目」（`show_nsfw_subject` 勾选框）改成 [show]：整张表单按页面上现有的值原样提交，
     * 只改这一项（[editSettings]），同在网页上勾选后点「保存修改」。做没做成由调用方回读接口确认。
     */
    suspend fun setShowNsfw(session: WebSession, show: Boolean, beforeSend: suspend () -> Unit) =
        editSettings(session, FormEdit(checks = mapOf(NSFW_CHECKBOX to show)), beforeSend)

    /**
     * 改个人资料（昵称、签名、自我介绍、头像）：只换 [edit] 里给了的几项，其余（用户名、个人主页、NSFW、时区）按页面上现在的值原样提交
     * （[editSettings]），同在网页上改好点「保存修改」。头像同浏览器选了文件一样随表单分段上传。做没做成由调用方回读接口确认。
     */
    suspend fun editProfile(session: WebSession, edit: ProfileForm, beforeSend: suspend () -> Unit) =
        editSettings(
            session,
            FormEdit(
                values = buildMap<String, String> {
                    edit.nickname?.let { put(NICKNAME_FIELD, it) }
                    edit.sign?.let { put(SIGN_FIELD, it) }
                    edit.bio?.let { put(BIO_FIELD, it) }
                },
                files = edit.avatarJpeg?.let { mapOf(AVATAR_FIELD to FormFile(AVATAR_FILENAME, "image/jpeg", it)) }.orEmpty(),
            ),
            beforeSend,
        )

    /**
     * 提交个人设置页 `/settings` 那张表单，只改 [edit] 里的项：每次提交前现读这一页，表单按页面上此刻的值填（[editedPostForm]），
     * 不用之前读到的旧值，别处刚改的设置不会被盖回去。页面上没有要改的项，或表单读不准（宁可不提交），
     * 都是「去网页端改」（[RejectReason.PRIVACY_SETTING_UNAVAILABLE]）。
     *
     * 读页到提交完成是一整段，一次只走一段（[settingsForm]）：每次提交都带着整张表单，两段交叠时都读到同一张旧页，
     * 后交的那段把先交的那项改回旧值。关掉面板不停下在途的写，改资料几项、NSFW 开关都会这样交叠。
     */
    private suspend fun editSettings(session: WebSession, edit: FormEdit, beforeSend: suspend () -> Unit) = settingsForm.withLock {
        val page = "$BGM_SITE$SETTINGS_PAGE"
        beforeSend()
        val form = editedPostForm(fetch(session, page).bodyAsText(), edit)
            ?: throw FailureException(Failure.Rejected(RejectReason.PRIVACY_SETTING_UNAVAILABLE))
        beforeSend()
        post(session, Url(page).resolve(form.action), form, referer = page)
    }

    /**
     * 删除自己的一条动态：依次打开自己的时间线页（[pageOf] 给第几页的地址，最多 [TIMELINE_PAGES_SCANNED] 页，翻到没有删除链接的页为止），
     * 找到页面上这条动态的删除链接（`tml_del` 样式，`gh=` 已在里面），照页面上写的地址加 `ajax=1` 提交，网页回 JSON，`status` 为 `ok` 才算做成。
     * 找不到这条动态、网页没回 `ok` 都按坏响应抛出，由调用方换成面向用户的原因。
     */
    suspend fun eraseTimeline(session: WebSession, id: Long, pageOf: (page: Int) -> String, beforeSend: suspend () -> Unit) {
        for (number in 1..TIMELINE_PAGES_SCANNED) {
            val page = pageOf(number)
            beforeSend()
            val links = timelineEraseLinksOf(fetch(session, page).bodyAsText())
            if (links.isEmpty()) break
            val link = links.firstOrNull { it.id == id } ?: continue
            val url = Url(page).resolve(link.href + (if ('?' in link.href) "&" else "?") + "ajax=1")
            beforeSend()
            val answer = client.submitForm(url = requireBgmSite(url), formParameters = parameters { }) {
                header(HttpHeaders.Cookie, session.cookie)
                header(HttpHeaders.Referrer, page)
            }
            if (answer.status.value >= 400) throw FailureException(Failure.Http(answer.status.value))
            if (!ERASE_OK.containsMatchIn(answer.bodyAsText())) throw FailureException(Failure.BadResponse)
            return
        }
        throw FailureException(Failure.BadResponse)
    }

    /**
     * 带会话提交一张表单（同在网页上点提交，Referer 是表单所在的页）：表单声明分段编码的照浏览器分段发（文件只能这样传），
     * 其余按普通表单发。网页报错是 [Failure.Http]。
     */
    private suspend fun post(session: WebSession, url: String, form: WebForm, referer: String) {
        val target = requireBgmSite(url)
        val submitted = if (form.multipart) {
            client.submitFormWithBinaryData(
                url = target,
                formData = formData {
                    form.fields.forEach { (name, value) -> append(name, value) }
                    form.files.forEach { (name, file) ->
                        append(
                            name,
                            file.bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, file.contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.filename}\"")
                            },
                        )
                    }
                },
            ) {
                header(HttpHeaders.Cookie, session.cookie)
                header(HttpHeaders.Referrer, referer)
            }
        } else {
            client.submitForm(
                url = target,
                formParameters = parameters { form.fields.forEach { (name, value) -> append(name, value) } },
            ) {
                header(HttpHeaders.Cookie, session.cookie)
                header(HttpHeaders.Referrer, referer)
            }
        }
        if (submitted.status.value >= 400) throw FailureException(Failure.Http(submitted.status.value))
    }

    private suspend fun fetch(session: WebSession, url: String, referer: String? = null, operation: Boolean = false): HttpResponse {
        val response = client.get(requireBgmSite(url)) {
            if (operation) attributes.put(WebOperation, Unit)
            header(HttpHeaders.Cookie, session.cookie)
            if (referer != null) header(HttpHeaders.Referrer, referer)
        }
        if (response.status.value >= 400) throw FailureException(Failure.Http(response.status.value))
        return response
    }

}

/** 隐私设置页：登录后才有内容，页面小。网页会话「现在是谁」都看它。 */
internal const val ACCOUNT_PAGE = "/settings/privacy"

/** 个人设置页：昵称、签名、头像、自我介绍、时区与「显示 NSFW 条目」在同一张表单上（分段编码，头像随它上传）。 */
private const val SETTINGS_PAGE = "/settings"

/** 个人设置页表单上昵称、签名、自我介绍、头像文件框的字段名。 */
private const val NICKNAME_FIELD = "nickname"
private const val SIGN_FIELD = "sign_input"
private const val BIO_FIELD = "newbio"
private const val AVATAR_FIELD = "picfile"

/** 上传头像时带的文件名（网页只看内容与类型，名字随意）。 */
private const val AVATAR_FILENAME = "avatar.jpg"

/** 个人设置页上「显示 NSFW 条目」勾选框的字段名（与开放接口隐私设置里的同名项是同一个值）。 */
private const val NSFW_CHECKBOX = "show_nsfw_subject"

/** 删除动态时最多翻几页自己的时间线找那一条。 */
private const val TIMELINE_PAGES_SCANNED = 5

/** 网页的写信、回复表单上提交按钮的值。 */
private const val PM_SUBMIT = "发送"

/** 网页对 `ajax=1` 操作（删除动态、全部已读）回的 JSON 里 `"status":"ok"`。 */
private val ERASE_OK = Regex(""""status"\s*:\s*"ok"""")

/** 动态删除链接的地址：路径里最后一段数字是动态 id（`/erase/tml/{id}?gh=…`）。 */
private val TIMELINE_ERASE_ID = Regex("""/(\d+)(?:[/?#]|$)""")

/** 自己时间线页上一条动态的删除链接：[id] 是动态 id，[href] 是页面上写的地址。 */
internal data class TimelineEraseLink(val id: Long, val href: String)

/** 时间线页上的删除链接：`tml_del` 样式的 `<a>`，地址路径里带动态 id 的才算。 */
internal fun timelineEraseLinksOf(html: String): List<TimelineEraseLink> = webDocumentOf(html).select("a.tml_del[href]").mapNotNull { anchor ->
    val href = anchor.attr("href")
    TIMELINE_ERASE_ID.find(href.substringBefore('?') + "?")?.groupValues?.get(1)?.toLongOrNull()?.let { TimelineEraseLink(it, href) }
}

/**
 * 隐私设置页绝交名单的一行：[id] 是取消链接（`/settings/privacy?ignore_reset={id}&gh=…`）上的对方数字 id，
 * [unblockLink] 是取消绝交的链接（页面上写的 href）。数字 id 只出现在这个链接里，行首的主页链接只有用户名，所以以它认人。
 */
data class BlocklistRow(val id: Int, val unblockLink: String)

/** 取消绝交的链接：隐私设置页自己的地址，带 `ignore_reset={数字 id}`（GET，不是表单提交）；别的操作链接不算。 */
private val IGNORE_RESET_HREF = Regex("""(?:https?://[^/?#]+)?/settings/privacy\?(?:[^#]*&)?ignore_reset=(\d+)(?:[&#].*)?""")

/** 用户主页的链接（`/user/{用户名或 id}`，相对地址或带站点）；头像图片等别的地址不算。 */
private val USER_HREF = Regex("""(?:https?://[^/]+)?/user/([^/?#]+)/?""")

/**
 * 隐私设置页上的绝交名单：每行（`<tr>`）里有一个 `tip_i` 样式、指向 `ignore_reset={id}` 的取消链接，有它的行才算。
 */
internal fun blocklistRowsOf(html: String): List<BlocklistRow> = webDocumentOf(html).select("tr").mapNotNull { row ->
    row.select("a.tip_i[href]").firstNotNullOfOrNull { anchor ->
        val href = anchor.attr("href")
        IGNORE_RESET_HREF.matchEntire(href)?.groupValues?.get(1)?.toIntOrNull()?.let { BlocklistRow(it, href) }
    }
}

/**
 * 小组网页上加入（[joined] 为 true）或退出 [name] 的链接，页面上写的 href 原样；没有时为 null。
 * 链接只认站内的 `/group/{name}/join` 与 `/group/{name}/bye`（相对地址，或以 [BGM_SITE] 开头）：小组简介、帖子标题里
 * 用户写的外站同名链接不算。校验值等参数照页面上的带。
 */
fun groupMembershipLinkOf(html: String, name: String, joined: Boolean): String? {
    val action = if (joined) "join" else "bye"
    val link = Regex("""(?:${Regex.escape(BGM_SITE)})?/group/${Regex.escape(name)}/$action\b.*""")
    return webDocumentOf(html).select("a[href]").map { it.attr("href") }.firstOrNull { link.matches(it) }
}

/** 每个网页都有的当前用户 id 脚本变量；没登录时是 0。 */
private val CURRENT_USER_ID = Regex("""CHOBITS_UID\s*=\s*(\d+)""")

/** 操作链接上的表单校验值：登出链接 `/logout/{formhash}` 与操作链接的 `gh=`。 */
private val FORMHASH_IN_LINK = listOf(Regex("""/logout/([0-9a-f]+)"""), Regex("""[?&]gh=([0-9a-f]+)"""))

/** 页面上的表单校验值：先看隐藏输入框 `formhash`，再看登出链接与操作链接（[FORMHASH_IN_LINK]）；都没有返回 null。 */
internal fun formhashOf(html: String): String? {
    val document = webDocumentOf(html)
    document.selectFirst("input[name=formhash][value]")?.attr("value")?.takeIf { it.isNotEmpty() }?.let { return it }
    val links = document.select("a[href]").map { it.attr("href") }
    return FORMHASH_IN_LINK.firstNotNullOfOrNull { pattern -> links.firstNotNullOfOrNull { pattern.find(it)?.groupValues?.get(1) } }
}

/** 网页上的当前用户 id（没登录是 0）；页面里读不到返回 null。 */
fun webUserIdOf(html: String): Int? = CURRENT_USER_ID.find(html)?.groupValues?.get(1)?.toIntOrNull()

/** 从登录后的页面读出 [WebAccount]：当前用户为 0 是没登录；读不到用户或校验值是坏响应。 */
internal fun parseWebAccount(html: String): WebAccount {
    val userId = webUserIdOf(html) ?: throw FailureException(Failure.BadResponse)
    if (userId == 0) throw FailureException(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED))
    val formhash = formhashOf(html) ?: throw FailureException(Failure.BadResponse)
    return WebAccount(userId, formhash)
}
