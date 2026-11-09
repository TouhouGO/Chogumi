package io.github.touhougo.chogumi.core.network.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.PmSamples
import io.github.touhougo.chogumi.core.network.createWebHttpClient
import io.github.touhougo.chogumi.core.network.model.web.PmOutgoing
import io.github.touhougo.chogumi.core.network.model.web.ProfileForm
import io.github.touhougo.chogumi.core.network.model.web.WebReplyParent
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

class WebSessionApiTest {

    @Test
    fun `登录后的页面读出当前用户与表单校验值`() {
        val page = """<script>var CHOBITS_UID = 42;</script><a href="/logout/9f8e7d">登出</a>"""
        assertEquals(WebAccount(userId = 42, formhash = "9f8e7d"), parseWebAccount(page))
        val withForm = """CHOBITS_UID = 42; <input type="hidden" name="formhash" value="a1b2"> <a href="/logout/ffff">"""
        assertEquals("a1b2", parseWebAccount(withForm).formhash, "隐藏输入框优先")
    }

    @Test
    fun `当前用户为0是没登录_读不出是坏响应`() {
        val loggedOut = assertFailsWith<FailureException> { parseWebAccount("""CHOBITS_UID = 0; <a href="/login">登录</a>""") }
        assertEquals(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED), loggedOut.failure)
        val noHash = assertFailsWith<FailureException> { parseWebAccount("CHOBITS_UID = 42;") }
        assertEquals(Failure.BadResponse, noHash.failure)
        val noUser = assertFailsWith<FailureException> { parseWebAccount("<html></html>") }
        assertEquals(Failure.BadResponse, noUser.failure)
    }

    @Test
    fun `隐私设置页读出绝交名单_数字id只在取消链接里_别的行与别的链接不算`() {
        val page = """<form method="post" action="/settings/privacy"><table class="settings">
            <tr><td><h2 class="subtitle">与以下用户绝交</h2></td><td></td></tr>
            <tr><td valign="top"><a href="/user/dean_white">白色蜂鸟</a></td>
              <td><p class="tip_j"><a href="/settings/privacy?ignore_reset=831069&amp;gh=f0" class="tip_i rr">取消绝交</a></p></td></tr>
            <tr><td valign="top"><a href="/user/luminaqin">阿琴在coding | 琴神</a></td>
              <td><p class="tip_j"><a href='/settings/privacy?gh=f0&amp;ignore_reset=938070' class='tip_i rr'>取消绝交</a></p></td></tr>
            <tr><td valign="top"><a href="/user/eve">Eve</a></td><td><a class="tip_i" href="/message/delete/9?gh=f0">删除</a></td></tr>
            <tr><td valign="top"><input name="ignore_user" type="text" value=""><input value="保存" name="submit_ignore" type="submit"></td></tr>
            </table></form>"""
        assertEquals(
            listOf(
                BlocklistRow(831069, "/settings/privacy?ignore_reset=831069&gh=f0"),
                BlocklistRow(938070, "/settings/privacy?gh=f0&ignore_reset=938070"),
            ),
            blocklistRowsOf(page),
        )
    }

    private val session = WebSession("chii_auth=c00k1e")

    /** 记下每个请求；[pages] 里的地址回那一页，别的回一个跳转。 */
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun api(pages: Map<String, String> = emptyMap()) = WebSessionApi(
        createWebHttpClient(
            MockEngine { request ->
                requests += request
                val page = pages[request.url.toString()]
                if (page != null) respond(page, HttpStatusCode.OK) else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/"))
            },
            customizers = emptyList(),
        ),
    )

    @Test
    fun `页面上写的外站链接不打开_登录Cookie不出本站`() = runTest {
        val api = api()

        for (link in listOf("https://evil.example/group/boring/join?gh=66bb", "http://bgm.tv/group/boring/join", "https://bgm.tv.evil.example/x")) {
            val e = assertFailsWith<FailureException> { api.follow(session, link, referer = "https://bgm.tv/group/boring") {} }
            assertEquals(Failure.BadResponse, e.failure, link)
        }
        assertFailsWith<FailureException> { api.page(session, "https://evil.example/group/boring") }
        assertEquals(emptyList<String>(), requests.map { it.url.toString() })

        api.follow(session, "/group/boring/join?gh=66bb", referer = "https://bgm.tv/group/boring") {}
        assertEquals("https://bgm.tv/group/boring/join?gh=66bb", requests.single().url.toString(), "站内相对链接照常打开")
        assertEquals("chii_auth=c00k1e", requests.single().headers[HttpHeaders.Cookie])
    }

    @Test
    fun `确认表单提交到外站时不提交`() = runTest {
        val join = "https://bgm.tv/group/boring/join?gh=66bb"
        val api = api(
            pages = mapOf(join to """<form action="https://evil.example/group/boring/join" method="post"><input type="hidden" name="formhash" value="f0"></form>"""),
        )

        val e = assertFailsWith<FailureException> { api.follow(session, join) {} }

        assertEquals(Failure.BadResponse, e.failure)
        assertEquals(listOf(join), requests.map { it.url.toString() })
    }

    @Test
    fun `勾选框表单照浏览器提交_其余各项原样_只改这一个勾选框`() {
        val html = """
            <form method="post" action="/search"><input type="hidden" name="q" value="x"></form>
            <form method="post" action="/settings">
            <input type="hidden" name="formhash" value="f0">
            <input name="nickname" value="A &amp; B">
            <input type="radio" name="gender" value="1"><input type="radio" name="gender" value="2" checked>
            <input type="checkbox" name="other" value="1" checked>
            <input type="checkbox" name="show_nsfw_subject" value="1" checked="checked">
            <select name="zone"><option value="7">7</option><option value="8">8</option></select>
            <textarea name="bio">a &lt;b&gt;</textarea>
            <input type="file" name="avatar"><input type="button" name="preview" value="预览">
            <input type="submit" name="submit" value="保存修改"><input type="submit" name="cancel" value="取消">
            </form>
        """.trimIndent()

        val unchecked = editedPostForm(html, nsfw(false))
        assertEquals("/settings", unchecked?.action)
        assertEquals(
            listOf("formhash" to "f0", "nickname" to "A & B", "gender" to "2", "other" to "1", "zone" to "7", "bio" to "a <b>", "submit" to "保存修改"),
            unchecked?.fields,
        )
        assertEquals("show_nsfw_subject" to "1", editedPostForm(html, nsfw(true))?.fields?.get(4))
        assertEquals(null, editedPostForm(html, FormEdit(checks = mapOf("missing" to true))))
    }

    // 以下表单是合成的最小样本（手写，不是 bgm.tv 的真实页面）：只验证解析规则，不证明真实页面的字段都读得准。
    private fun settingsForm(vararg controls: String) = """
        <form method="post" action="/settings"><input type="hidden" name="formhash" value="f0">
        ${controls.joinToString("\n")}
        <input type="checkbox" name="show_nsfw_subject" value="1" checked>
        <input type="submit" name="submit" value="保存修改"></form>
    """.trimIndent()

    @Test
    fun `勾选框表单遇到读不准的控件就不提交`() {
        val unreadable = listOf(
            """<button name="draft" value="1">存草稿</button>""",
            """<select name="langs" multiple><option value="a" selected>a</option><option value="b" selected>b</option></select>""",
            """<input type="slider" name="level" value="3">""",
        )
        for (control in unreadable) assertEquals(null, editedPostForm(settingsForm(control), nsfw(false)), control)
    }

    @Test
    fun `勾选框表单_disabled的控件与无名按钮不提交_data前缀的属性不算name_不带引号的属性照读_文本域去掉开头换行`() {
        val form = editedPostForm(
            settingsForm(
                """<input name="locked" value="x" disabled>""",
                """<input type="text" data-name="decoy" name="nickname" value="Fixture User">""",
                "<input name=sign value=hi>",
                "<textarea name=\"bio\">\nline1\nline2</textarea>",
                """<button type="button">预览</button>""",
            ),
            nsfw(false),
        )
        assertEquals(listOf("formhash" to "f0", "nickname" to "Fixture User", "sign" to "hi", "bio" to "line1\nline2", "submit" to "保存修改"), form?.fields)
    }

    @Test
    fun `勾选框所在的表单读不准时不去用后面别的表单`() {
        val html = settingsForm("""<button name="draft">存草稿</button>""") +
            """<form method="post" action="/other"><input type="checkbox" name="show_nsfw_subject" checked></form>"""

        assertEquals(null, editedPostForm(html, nsfw(true)))
    }

    private fun nsfw(checked: Boolean) = FormEdit(checks = mapOf("show_nsfw_subject" to checked))

    @Test
    fun `改资料只换给了的几项_其余照页面现在的值_分段表单照分段提交`() {
        val html = settingsForm(
            """<input name="nickname" value="旧昵称">""",
            """<input name="sign_input" value="旧签名">""",
            """<input type="file" name="picfile">""",
            """<input name="username" value="">""",
            """<textarea name="newbio">旧介绍</textarea>""",
        ).replace("""<form method="post" action="/settings">""", """<form method="post" action="/settings" enctype="multipart/form-data">""")

        val form = editedPostForm(html, FormEdit(values = mapOf("sign_input" to "新签名")))

        assertEquals(true, form?.multipart)
        assertEquals(
            listOf(
                "formhash" to "f0", "nickname" to "旧昵称", "sign_input" to "新签名", "username" to "",
                "newbio" to "旧介绍", "show_nsfw_subject" to "1", "submit" to "保存修改",
            ),
            form?.fields,
        )
        assertEquals(false, editedPostForm(settingsForm(), nsfw(true))?.multipart, "没声明分段编码的表单按普通表单提交")
    }

    @Test
    fun `要改的项不在_类型不对或被禁用时不提交`() {
        val html = settingsForm(
            """<input name="nickname" value="旧昵称">""",
            """<input name="sign_input" value="旧签名" disabled>""",
            """<input type="hidden" name="uid" value="1">""",
        )
        assertNull(editedPostForm(html, FormEdit(values = mapOf("newbio" to "x"))), "页面上没有这一项")
        assertNull(editedPostForm(html, FormEdit(values = mapOf("sign_input" to "x"))), "被禁用")
        assertNull(editedPostForm(html, FormEdit(values = mapOf("uid" to "2"))), "隐藏字段不是给人填的")
        assertNull(editedPostForm(html, FormEdit(values = mapOf("show_nsfw_subject" to "x"))), "勾选框不能当文本框换值")
        assertNull(editedPostForm(html, FormEdit(files = mapOf("nickname" to FormFile("a.jpg", "image/jpeg", byteArrayOf(1))))), "文本框不能当文件框")
        assertEquals("nickname" to "新", editedPostForm(html, FormEdit(values = mapOf("nickname" to "新")))?.fields?.get(1))
    }

    @Test
    fun `改资料先现读设置页再提交_头像随表单分段上传`() = runTest {
        val settings = "https://bgm.tv/settings"
        val page = """<form method="post" action="/settings" enctype="multipart/form-data" name="set">
            <input type="hidden" name="formhash" value="f0"><input name="nickname" value="旧昵称">
            <input name="sign_input" value="旧签名"><input type="file" name="picfile">
            <input name="username" value=""><input name="newsite" value="">
            <textarea name="newbio">旧介绍</textarea><input type="checkbox" name="show_nsfw_subject" value="1" checked>
            <select name="timeoffsetnew"><option value="9999" selected>默认</option></select>
            <input type="submit" name="submit" value="保存修改"></form>"""
        val api = answeringApi { request ->
            if (request.method.value == "GET") Triple(HttpStatusCode.OK, null, page) else Triple(HttpStatusCode.Found, "/settings", "")
        }
        var checks = 0

        api.editProfile(session, ProfileForm(nickname = "新昵称", avatarJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte())), beforeSend = { checks++ })

        assertEquals(listOf("GET $settings", "POST $settings"), requests.map { "${it.method.value} ${it.url}" })
        assertEquals(2, checks, "读页与提交之前各确认一次会话")
        val post = requests.last()
        assertEquals(settings, post.headers[HttpHeaders.Referrer])
        val parts = multipartOf(assertIs<MultiPartFormDataContent>(post.body).toByteArray().decodeToString())
        assertEquals(
            listOf(
                "formhash" to "f0", "nickname" to "新昵称", "sign_input" to "旧签名", "username" to "", "newsite" to "",
                "newbio" to "旧介绍", "show_nsfw_subject" to "1", "timeoffsetnew" to "9999", "submit" to "保存修改",
            ),
            parts.filter { it.first != "picfile" }.map { it.first to it.second },
        )
        val avatar = parts.single { it.first == "picfile" }
        assertTrue("filename=\"avatar.jpg\"" in avatar.third && "image/jpeg" in avatar.third, avatar.third)
    }

    @Test
    fun `两次改设置交叠时后一次等前一次交完再读页_前一次改的不被改回`() = runBlocking {
        val values = ConcurrentHashMap(mapOf("nickname" to "旧昵称", "sign_input" to "旧签名"))
        val firstPostArrived = CompletableDeferred<Unit>()
        val releaseFirstPost = CompletableDeferred<Unit>()
        val secondRead = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val posts = AtomicInteger()
        val api = WebSessionApi(
            createWebHttpClient(
                MockEngine { request ->
                    if (request.method.value == "GET") {
                        if (reads.incrementAndGet() == 2) secondRead.complete(Unit)
                        val page = """<form method="post" action="/settings" enctype="multipart/form-data" name="set">
                            <input type="hidden" name="formhash" value="f0"><input name="nickname" value="${values["nickname"]}">
                            <input name="sign_input" value="${values["sign_input"]}"><input type="file" name="picfile">
                            <textarea name="newbio"></textarea><input type="submit" name="submit" value="保存修改"></form>"""
                        respond(page, HttpStatusCode.OK)
                    } else {
                        val body = assertIs<MultiPartFormDataContent>(request.body).toByteArray().decodeToString()
                        if (posts.incrementAndGet() == 1) {
                            firstPostArrived.complete(Unit)
                            releaseFirstPost.await()
                        }
                        multipartOf(body).filter { it.first in values.keys }.forEach { (name, value) -> values[name] = value }
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/settings"))
                    }
                },
                customizers = emptyList(),
            ),
        )

        val nickname = launch(Dispatchers.Default) { api.editProfile(session, ProfileForm(nickname = "新昵称"), beforeSend = {}) }
        firstPostArrived.await()
        val sign = launch(Dispatchers.Default) { api.editProfile(session, ProfileForm(sign = "新签名"), beforeSend = {}) }
        // 前一次的提交在途时后一次不该读页：读了就放行前一次，让两次交叠；一直没读（只为它等一会儿）也放行
        withTimeoutOrNull(1_000) { secondRead.await() }
        releaseFirstPost.complete(Unit)
        nickname.join()
        sign.join()

        assertEquals(mapOf("nickname" to "新昵称", "sign_input" to "新签名"), values.toMap())
    }

    /** 分段表单正文里的各段：字段名、内容、这一段的头。 */
    private fun multipartOf(body: String): List<Triple<String, String, String>> {
        val boundary = body.substringBefore("\r\n")
        return body.split(boundary).drop(1).dropLast(1).map { part ->
            val headers = part.removePrefix("\r\n").substringBefore("\r\n\r\n")
            val name = Regex("""name="?([^";\r\n]+)"?""").find(headers)!!.groupValues[1]
            Triple(name, part.substringAfter("\r\n\r\n").removeSuffix("\r\n"), headers)
        }
    }

    /** 记下每个请求，按 [answer] 回答（地址 → 状态、跳转地址、正文）。 */
    private fun answeringApi(answer: (HttpRequestData) -> Triple<HttpStatusCode, String?, String>) = WebSessionApi(
        createWebHttpClient(
            MockEngine { request ->
                requests += request
                val (status, location, body) = answer(request)
                respond(body, status, if (location != null) headersOf(HttpHeaders.Location, location) else headersOf())
            },
            customizers = emptyList(),
        ),
    )

    private val reply = PmOutgoing(receiverId = 400100, title = "", body = "回复 & 换行\n第二行", related = 500001, newTopic = false)

    @Test
    fun `发信照网页的表单提交_跳到会话就是发出去了_回会话id`() = runTest {
        val api = answeringApi { Triple(HttpStatusCode.Found, "/pm/conversation/300003.chii", "") }
        var checks = 0

        val id = api.sendMessage(session, "https://bgm.tv/pm/create.chii", "f0", reply.copy(newTopic = true, title = "新主题"), "https://bgm.tv/pm/conversation/300003.chii") { checks++ }

        assertEquals(300003, id)
        assertEquals(1, checks, "发送前确认会话")
        val request = requests.single()
        assertEquals("https://bgm.tv/pm/create.chii", request.url.toString())
        assertEquals("chii_auth=c00k1e", request.headers[HttpHeaders.Cookie])
        assertEquals("https://bgm.tv/pm/conversation/300003.chii", request.headers[HttpHeaders.Referrer])
        assertEquals(
            listOf(
                "formhash" to "f0", "msg_receivers" to "400100", "related" to "500001", "new_topic" to "1",
                "msg_title" to "新主题", "msg_body" to "回复 & 换行\n第二行", "submit" to "发送",
            ),
            (request.body as FormDataContent).formData.entries().flatMap { (name, values) -> values.map { name to it } },
        )
    }

    /** 回复表单照 2026-11-08 实测的话题页裁剪（六类页面同一张表单）。 */
    private fun replyPage(extra: String = "") = """<form id="ReplyForm" name="new_comment" method="post" action="/group/topic/375793/new_reply">
        <input type="hidden" name="formhash" value="560139dc" /><input type="hidden" name="lastview" value="1794120762" />
        <textarea name="content" id="content" class="reply newReply"></textarea>$extra
        <div id="submitBtnO"><input class="inputBtn" value="加上去" name="submit" type="submit" /></div></form>"""

    private fun answeringReplyPage(page: String) = answeringApi { request ->
        if (request.method.value == "GET") Triple(HttpStatusCode.OK, null, page) else Triple(HttpStatusCode.Found, "/group/topic/375793", "")
    }

    private fun HttpRequestData.formFields() = (body as FormDataContent).formData.entries().flatMap { (name, values) -> values.map { name to it } }

    @Test
    fun `回复照页面上的回复表单提交_新开一层只填正文`() = runTest {
        val api = answeringReplyPage(replyPage())

        api.reply(session, "https://bgm.tv/group/topic/375793", "回复 & 换行\n第二行", parent = null) {}

        val post = requests.last()
        assertEquals("https://bgm.tv/group/topic/375793/new_reply", post.url.toString())
        assertEquals("https://bgm.tv/group/topic/375793", post.headers[HttpHeaders.Referrer])
        assertEquals("chii_auth=c00k1e", post.headers[HttpHeaders.Cookie])
        assertEquals(
            listOf("formhash" to "560139dc", "lastview" to "1794120762", "content" to "回复 & 换行\n第二行", "submit" to "加上去"),
            post.formFields(),
        )
    }

    @Test
    fun `回复一层楼时带上主楼与它的作者`() = runTest {
        val api = answeringReplyPage(replyPage("""<input type="hidden" name="related" value="0" />"""))

        api.reply(session, "https://bgm.tv/group/topic/375793", "楼中楼", WebReplyParent(postId = 4103407, userId = 1183252)) {}

        val fields = requests.last().formFields()
        assertEquals(listOf("4103407"), fields.filter { it.first == "related" }.map { it.second }, "页面上的 related 被换掉，不重复")
        assertEquals("1183252", fields.single { it.first == "sub_reply_uid" }.second)
        assertEquals("1183252", fields.single { it.first == "post_uid" }.second)
        assertEquals("楼中楼", fields.single { it.first == "content" }.second)
    }

    @Test
    fun `回复表单要人机验证或页面上没有回复表单时不提交_按坏响应抛出`() = runTest {
        for (page in listOf(replyPage("""<div class="cf-turnstile" data-sitekey="x"></div>"""), "<p>话题已关闭</p>")) {
            requests.clear()
            val api = answeringReplyPage(page)
            val failure = assertFailsWith<FailureException> { api.reply(session, "https://bgm.tv/group/topic/375793", "正文", null) {} }
            assertEquals(Failure.BadResponse, failure.failure)
            assertTrue(requests.none { it.method.value == "POST" })
        }
    }

    @Test
    fun `新信不带related与new_topic`() = runTest {
        val api = answeringApi { Triple(HttpStatusCode.Found, "/pm/conversation/300003.chii", "") }

        api.sendMessage(session, "https://bgm.tv/pm/create.chii", "f0", reply.copy(related = null, title = "Chogumi 测试"), "https://bgm.tv/pm/compose/400100.chii") {}

        val names = (requests.single().body as FormDataContent).formData.names()
        assertEquals(setOf("formhash", "msg_receivers", "msg_title", "msg_body", "submit"), names)
    }

    @Test
    fun `没发出去时网页停在写信页_抛网页给的原因_跳到别处是坏响应`() = runTest {
        val rejected = answeringApi { Triple(HttpStatusCode.OK, null, PmSamples.read("create-error-no-receiver.html")) }
        val e = assertFailsWith<FailureException> { rejected.sendMessage(session, "https://bgm.tv/pm/create.chii", "f0", reply, "https://bgm.tv/pm/compose.chii") {} }
        assertEquals(Failure.Rejected(RejectReason.PM_RECEIVER_NOT_FOUND), e.failure)

        val elsewhere = answeringApi { Triple(HttpStatusCode.Found, "/login", "") }
        val moved = assertFailsWith<FailureException> { elsewhere.sendMessage(session, "https://bgm.tv/pm/create.chii", "f0", reply, "https://bgm.tv/pm/compose.chii") {} }
        assertEquals(Failure.BadResponse, moved.failure)
    }

    @Test
    fun `全部已读照网页的脚本打开地址加ajax_回ok才算做成`() = runTest {
        var status = "ok"
        val api = answeringApi { Triple(HttpStatusCode.OK, null, """{"status":"$status"}""") }

        api.markMessagesRead(session, "/pm/ignore_unread.chii?gh=f0") {}
        assertEquals("https://bgm.tv/pm/ignore_unread.chii?gh=f0&ajax=1", requests.single().url.toString())
        assertEquals("chii_auth=c00k1e", requests.single().headers[HttpHeaders.Cookie])

        status = "error"
        val e = assertFailsWith<FailureException> { api.markMessagesRead(session, "/pm/ignore_unread.chii?gh=f0") {} }
        assertEquals(Failure.BadResponse, e.failure)
    }

    @Test
    fun `全部已读的地址指向外站时不打开`() = runTest {
        val api = answeringApi { Triple(HttpStatusCode.OK, null, """{"status":"ok"}""") }

        val e = assertFailsWith<FailureException> { api.markMessagesRead(session, "https://evil.example/pm/ignore_unread.chii?gh=f0") {} }

        assertEquals(Failure.BadResponse, e.failure)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `读页面时页面回跳转为null`() = runTest {
        val api = answeringApi { request ->
            if (request.url.encodedPath == "/pm/inbox.chii") Triple(HttpStatusCode.OK, null, "page") else Triple(HttpStatusCode.Found, "/pm", "")
        }

        assertEquals("page", api.pageOrNull(session, "https://bgm.tv/pm/inbox.chii?page=1"))
        assertNull(api.pageOrNull(session, "https://bgm.tv/pm/conversation/1.chii"))
    }
}

