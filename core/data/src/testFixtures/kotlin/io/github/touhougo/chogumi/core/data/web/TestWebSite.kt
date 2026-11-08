package io.github.touhougo.chogumi.core.data.web

import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.headersOf

/**
 * 假的 bgm.tv 网页：账号页（隐私设置页）回 [userId] 登录着的页面（登出链接上带表单校验值 [formhash]；[userId] 为 0
 * 是没登录），[pages] 里写了这一页时回那一页；[pages] 里的路径回那一页的内容，别的地址记进 [opened]（连同带来的 Cookie）
 * 后回一个跳转，同网站的操作链接；[confirming] 里的操作先回一页提交到自己的 POST 确认表单。POST（确认表单、网页上的表单）
 * 一律记进 [submitted] 后回跳转。
 */
class TestWebSite(var userId: Int, val formhash: String = "0a1b2c3d") {
    /** 打开过的操作链接与带来的 Cookie，按顺序。 */
    private val requests = ConcurrentRequestRecorder()
    private val openRequests get() = requests.filter { it.method != HttpMethod.Post }
    val opened: List<Pair<String, String?>> get() = openRequests.map { it.url.toString() to it.headers[HttpHeaders.Cookie] }

    /** 操作链接被处理时执行，用来模拟响应期间的账号切换。 */
    var onOpen: suspend () -> Unit = {}

    /** 普通网页：路径 → 页面内容（测试途中可以改，模拟操作前后的页面）。 */
    val pages = mutableMapOf<String, String>()

    /** 跳转的网页：路径 → 跳去的地址（如 `/user/{id}` 跳到 `/user/{用户名}`）。 */
    val redirects = mutableMapOf<String, String>()

    /** 打开操作链接时带来的 Referer，与 [opened] 一一对应。 */
    val referers: List<String?> get() = openRequests.map { it.headers[HttpHeaders.Referrer] }

    /** 先要确认的操作的路径。 */
    val confirming = mutableSetOf<String>()

    /** 提交过几次 POST（含分段上传的表单，[submitted] 只读得了普通表单）。 */
    val postCount: Int get() = requests.count { it.method == HttpMethod.Post }

    /** 提交过的确认表单：地址与表单内容，按顺序。 */
    val submitted: List<Pair<String, String>> get() = requests.filter { it.method == HttpMethod.Post }
        .map { it.url.toString() to (it.body as FormDataContent).formData.formUrlEncode() }

    val client: HttpClient = testWebClient { request ->
        val page = pages[request.url.encodedPath]
        if (request.method == HttpMethod.Post) {
            requests.record(request)
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/"))
        } else if (request.url.encodedPath == "/settings/privacy" && "ignore_reset" in request.url.parameters) {
            requests.record(request)
            onOpen()
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/settings/privacy"))
        } else if (request.url.encodedPath == "/settings/privacy") {
            respond(page ?: """<script>var CHOBITS_UID = $userId;</script><a href="/logout/$formhash">登出</a>""", HttpStatusCode.OK)
        } else if (request.url.encodedPath in redirects) {
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, redirects.getValue(request.url.encodedPath)))
        } else if (page != null) {
            respond(page, HttpStatusCode.OK)
        } else if (request.url.encodedPath in confirming) {
            requests.record(request)
            onOpen()
            val action = request.url.encodedPath + "?" + request.url.encodedQuery.replace("&", "&amp;")
            respond(
                """<form action="/subject_search" method="post"><input type="hidden" name="q" value=""></form>
                   <form action="$action" method="post"><input type="hidden" name="formhash" value="$formhash">
                   <input type="text" name="note" value=""><input type="submit" name="submit" value="确定"></form>""",
                HttpStatusCode.OK,
            )
        } else {
            requests.record(request)
            onOpen()
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/"))
        }
    }
}
