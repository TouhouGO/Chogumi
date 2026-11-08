package io.github.touhougo.chogumi.core.network.api

import java.util.concurrent.CopyOnWriteArrayList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.createWebHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class WebLoginApiTest {

    private val config = AuthConfig("cid", "secret", "chogumi://oauth/callback")
    private val authorizeUrl = "https://bgm.tv/oauth/authorize?client_id=cid&response_type=code&redirect_uri=chogumi%3A%2F%2Foauth%2Fcallback&state=s"

    private val loginPage = """
        <form id="loginForm" method="post" action="/FollowTheRabbit">
        <input type="hidden" name="formhash" value="f0f0" />
        <input type="text" name="email" />
        </form>
    """.trimIndent()

    private fun errorPage(text: String) =
        """<div id="colunmNotice"><div class="message"><h2>出错了</h2><p class="text">$text</p></div></div>"""

    /** 授权页：授权表单之外还有一张搜索表单（GET），只该提交授权那张。 */
    private val authorizePage = """
        <form action="/subject_search" method="get"><input type="hidden" name="cat" value="all"></form>
        <form action="/oauth/authorize?client_id=cid&amp;response_type=code&amp;state=s" method="post" name="new_authorization">
        <input type="hidden" name="formhash" value="f0f0">
        <input type="hidden" name="redirect_uri" value="">
        <input type="hidden" name="client_id" value="cid">
        <input type="text" name="note" value="不提交">
        <input class="inputBtn" type="submit" name="submit" value="授权">
        </form>
    """.trimIndent()

    private val captchaImage = byteArrayOf(0x47, 0x49, 0x46, 0x38)

    /** 记下每个请求（方法、路径、带来的 Cookie、表单）。 */
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): WebLoginApi {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return WebLoginApi(createWebHttpClient("test", engine), config)
    }

    private fun HttpRequestData.form(): Parameters = (body as FormDataContent).formData

    /**
     * 网站：登录页发会话 Cookie，验证码 abcd + 密码 pw 才登录成功（发 chii_auth 并跳转，同时用 deleted / delete
     * 两种标记删掉别的 Cookie），未授权过本应用时先出授权页。
     */
    private fun site(alreadyAuthorized: Boolean = false) = api { request ->
        when (request.url.encodedPath) {
            "/login" -> respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "chii_sid=s1; path=/"))
            "/signup/captcha" -> respond(captchaImage, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "chii_sec=v1; path=/"))
            "/FollowTheRabbit" -> {
                val form = request.form()
                when {
                    form["captcha_challenge_field"] != "abcd" -> respond(errorPage("验证码错误，请返回重新输入"), HttpStatusCode.OK)
                    form["password"] != "pw" -> respond(errorPage("用户名或密码错误"), HttpStatusCode.OK)
                    else -> respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(
                            HttpHeaders.Location to listOf("/"),
                            HttpHeaders.SetCookie to listOf("chii_auth=AUTH; path=/", "chii_sec=deleted; path=/", "chii_sid=delete; path=/"),
                        ),
                    )
                }
            }
            "/oauth/authorize" -> when {
                request.method == HttpMethod.Get && !alreadyAuthorized -> respond(authorizePage, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "chogumi://oauth/callback?code=CODE&state=s"))
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `登录全程_会话Cookie跟着走_授权页提交授权表单拿到授权码`() = runTest {
        val api = site()
        val attempt = api.start()
        assertContentEquals(captchaImage, api.captcha(attempt))
        api.signIn(attempt, "a@b.c", "pw", "abcd")

        val signIn = requests.single { it.url.encodedPath == "/FollowTheRabbit" }
        assertEquals("chii_sid=s1; chii_sec=v1", signIn.headers[HttpHeaders.Cookie], "验证码按会话认：提交带着取图时的 Cookie")
        assertEquals("f0f0", signIn.form()["formhash"])
        assertEquals("a@b.c", signIn.form()["email"])
        assertEquals("chii_auth=AUTH", attempt.session.cookie, "网站删掉的 Cookie 不留（deleted 与 delete 两种标记）")

        assertEquals("CODE", api.authorize(attempt, authorizeUrl))
        val submitted = requests.last()
        assertEquals(HttpMethod.Post, submitted.method)
        assertEquals("https://bgm.tv/oauth/authorize?client_id=cid&response_type=code&state=s", submitted.url.toString())
        assertEquals("chii_auth=AUTH", submitted.headers[HttpHeaders.Cookie])
        assertEquals(
            mapOf("formhash" to listOf("f0f0"), "redirect_uri" to listOf(""), "client_id" to listOf("cid"), "submit" to listOf("授权")),
            submitted.form().entries().associate { (name, values) -> name to values },
            "只交隐藏字段与授权按钮",
        )
    }

    @Test
    fun `授权过的应用直接跳回回调地址_不再提交表单`() = runTest {
        val api = site(alreadyAuthorized = true)
        val attempt = api.start()
        api.signIn(attempt, "a@b.c", "pw", "abcd")

        assertEquals("CODE", api.authorize(attempt, authorizeUrl))
        assertEquals(HttpMethod.Get, requests.last().method)
    }

    @Test
    fun `登录出错页按那一句给出原因`() = runTest {
        val api = site()
        val attempt = api.start()

        val captcha = assertFailsWith<FailureException> { api.signIn(attempt, "a@b.c", "pw", "wrong") }
        assertEquals(Failure.Rejected(RejectReason.LOGIN_CAPTCHA_WRONG), captcha.failure)
        val password = assertFailsWith<FailureException> { api.signIn(attempt, "a@b.c", "nope", "abcd") }
        assertEquals(Failure.Rejected(RejectReason.LOGIN_CREDENTIALS_WRONG), password.failure)
        assertEquals(RejectReason.LOGIN_FAILED, signInRejectionOf(errorPage("操作过于频繁")))
        assertEquals(RejectReason.LOGIN_FAILED, signInRejectionOf("<html>维护中</html>"))
        assertEquals(
            RejectReason.LOGIN_TOO_MANY_ATTEMPTS,
            signInRejectionOf(errorPage("累计 5 次错误尝试，15 分钟内您将不能登录本站。")),
            "错误次数太多：句子里也有「登录」，但不是账号密码错",
        )
    }

    /** 表单属性用单引号、没写 action（提交到授权页自己）也认得出。 */
    @Test
    fun `单引号且没写action的授权表单提交到授权地址本身`() {
        val form = parseAuthorizeForm(
            """
            <form id='authForm' method='post' name='auth'>
            <input type='hidden' name='formhash' value='f0f0'>
            <input type='hidden' name='client_id' value='cid'>
            <button type='submit'>授权</button>
            </form>
            """.trimIndent(),
        )
        assertEquals(WebForm("", listOf("formhash" to "f0f0", "client_id" to "cid")), form)
    }

    /** 登录成功时不跳转、只回一页提示的网站：会话登没登上只能打开登录后才有内容的页面看。 */
    private fun siteAnsweringWithPage() = api { request ->
        when (request.url.encodedPath) {
            "/login" -> respond(loginPage, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "chii_sid=s1; path=/"))
            "/FollowTheRabbit" -> when (request.form()["password"]) {
                "pw" -> respond(errorPage("欢迎回来"), HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "chii_auth=AUTH; path=/"))
                else -> respond(errorPage("操作过于频繁"), HttpStatusCode.OK)
            }
            "/settings/privacy" -> {
                val signedIn = "chii_auth=AUTH" in request.headers[HttpHeaders.Cookie].orEmpty()
                respond("<script>var CHOBITS_UID = ${if (signedIn) 7 else 0}, CHOBITS_USERNAME = '';</script>", HttpStatusCode.OK)
            }
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `登录后回一页提示而不跳转_会话已登上就是成功`() = runTest {
        val api = siteAnsweringWithPage()
        val attempt = api.start()
        api.signIn(attempt, "a@b.c", "pw", "abcd")
        assertEquals("chii_sid=s1; chii_auth=AUTH", attempt.session.cookie)

        val failed = assertFailsWith<FailureException> { api.signIn(api.start(), "a@b.c", "nope", "abcd") }
        assertEquals(Failure.Rejected(RejectReason.LOGIN_FAILED), failed.failure, "认不出原因、会话也没登上")
    }

    @Test
    fun `授权页里找不到授权表单按坏响应处理`() = runTest {
        val api = api { request ->
            if (request.url.encodedPath == "/login") respond(loginPage, HttpStatusCode.OK) else respond("<html></html>", HttpStatusCode.OK)
        }
        val attempt = api.start()

        val e = assertFailsWith<FailureException> { api.authorize(attempt, authorizeUrl) }
        assertEquals(Failure.BadResponse, e.failure)
    }

    @Test
    fun `授权表单提交到外站时不提交_登录Cookie不出本站`() = runTest {
        val api = api { request ->
            when (request.url.encodedPath) {
                "/login" -> respond(loginPage, HttpStatusCode.OK)
                else -> respond(authorizePage.replace("/oauth/authorize?", "https://evil.example/oauth/authorize?"), HttpStatusCode.OK)
            }
        }
        val attempt = api.start()

        val e = assertFailsWith<FailureException> { api.authorize(attempt, authorizeUrl) }

        assertEquals(Failure.BadResponse, e.failure)
        assertEquals(listOf("bgm.tv", "bgm.tv"), requests.map { it.url.host })
    }
}
