package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.foundation.BGM_SITE
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.parameters
import io.ktor.http.setCookie
import kotlin.random.Random

/**
 * 一次账号密码登录途中的网页状态：网站发的 Cookie（验证码与登录都按它认）与登录表单的校验值。
 * 只在一次登录里用，登录成功后 [session] 就是这个账号的网页会话。
 */
class WebLoginAttempt internal constructor(internal val formhash: String) {
    private val cookies = linkedMapOf<String, String>()

    internal fun header(): String = cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    /** 记下响应里的 Cookie：同名的新值覆盖旧值，被网站删掉（空值或删除标记）的去掉。 */
    internal fun absorb(response: HttpResponse) {
        response.setCookie().forEach { cookie ->
            if (cookie.value.isEmpty() || cookie.value in DELETED) cookies.remove(cookie.name) else cookies[cookie.name] = cookie.value
        }
    }

    /** 这次登录得到的网页会话。 */
    val session: WebSession get() = WebSession(header())

    private companion object {
        /** 网站删 Cookie 时写的值。 */
        val DELETED = setOf("delete", "deleted")
    }
}

/**
 * bgm.tv 账号密码登录：网页的登录表单（邮箱、密码、图片验证码），登录后替用户在 OAuth 授权页点「授权」，
 * 一次拿到网页会话（Cookie）和授权码。表单地址与字段取自网站的登录页与授权页本身。
 *
 * 用网页客户端（不跟随跳转）：登录与授权的结果都在跳转里。
 */
class WebLoginApi(
    private val client: HttpClient,
    private val config: AuthConfig,
) {

    /** 开始一次登录：打开登录页，拿到 Cookie 与表单校验值。 */
    suspend fun start(): WebLoginAttempt {
        val response = client.get("$BGM_SITE$LOGIN_PAGE")
        requireOk(response)
        val formhash = formhashOf(response.bodyAsText()) ?: throw FailureException(Failure.BadResponse)
        return WebLoginAttempt(formhash).also { it.absorb(response) }
    }

    /** 一张新的验证码图（GIF）。网站按 Cookie 记住最后一张，提交时填的是它上面的字。 */
    suspend fun captcha(attempt: WebLoginAttempt): ByteArray {
        // 地址后的数只是让每次都是新图（网站自己的页面也这样做）
        val response = client.get("$BGM_SITE$CAPTCHA_PATH?${Random.nextLong(Long.MAX_VALUE)}") {
            header(HttpHeaders.Cookie, attempt.header())
        }
        requireOk(response)
        attempt.absorb(response)
        return response.bodyAsBytes()
    }

    /**
     * 提交登录表单。成功时网站发登录 Cookie，回一个跳转或一页提示；失败时回一页错误，原因认得出的给出具体原因
     * （验证码错、邮箱或密码错、错误次数太多）。回的是一页而原因认不出时，打开一个登录后才有内容的页面看这次会话登上没有：
     * 登上了就是成功，没登上按「登录没成功」。
     */
    suspend fun signIn(attempt: WebLoginAttempt, email: String, password: String, captcha: String) {
        val response = client.submitForm(
            url = "$BGM_SITE$SIGN_IN_PATH",
            formParameters = parameters {
                append("formhash", attempt.formhash)
                append("referer", "")
                append("dreferer", "")
                append("email", email)
                append("password", password)
                append("captcha_challenge_field", captcha)
                append("loginsubmit", SIGN_IN_BUTTON)
            },
        ) {
            header(HttpHeaders.Cookie, attempt.header())
        }
        attempt.absorb(response)
        if (response.status.value in 300..399) return
        requireOk(response)
        val reason = signInRejectionOf(response.bodyAsText())
        if (reason == RejectReason.LOGIN_FAILED && signedIn(attempt)) return
        throw FailureException(Failure.Rejected(reason))
    }

    /** 这次登录的会话现在是不是已经登上了某个账号（网页上的当前用户不是 0）。 */
    private suspend fun signedIn(attempt: WebLoginAttempt): Boolean {
        val page = client.get("$BGM_SITE$ACCOUNT_PAGE") { header(HttpHeaders.Cookie, attempt.header()) }
        attempt.absorb(page)
        return page.status.value in 200..299 && (webUserIdOf(page.bodyAsText()) ?: 0) > 0
    }

    /**
     * 用这次登录的网页会话打开 [authorizeUrl]（OAuth 授权页），替用户点「授权」，返回回调地址上的授权码。
     * 授权过的应用网站会直接跳回回调地址；没授权过的先出授权页，提交页上的授权表单。
     */
    suspend fun authorize(attempt: WebLoginAttempt, authorizeUrl: String): String {
        val page = client.get(requireBgmSite(authorizeUrl)) { header(HttpHeaders.Cookie, attempt.header()) }
        attempt.absorb(page)
        callbackCodeOf(page)?.let { return it }
        requireOk(page)
        val form = parseAuthorizeForm(page.bodyAsText()) ?: throw FailureException(Failure.BadResponse)
        val submitted = client.submitForm(
            url = requireBgmSite(Url(authorizeUrl).resolve(form.action)),
            formParameters = parameters { form.fields.forEach { (name, value) -> append(name, value) } },
        ) {
            header(HttpHeaders.Cookie, attempt.header())
        }
        attempt.absorb(submitted)
        return callbackCodeOf(submitted) ?: throw FailureException(Failure.BadResponse)
    }

    /** 跳到回调地址的响应里的授权码；不是跳回回调地址返回 null。 */
    private fun callbackCodeOf(response: HttpResponse): String? {
        if (response.status.value !in 300..399) return null
        val location = response.headers[HttpHeaders.Location] ?: return null
        if (!location.startsWith(config.redirectUri)) return null
        return Url(location).parameters["code"]?.takeIf { it.isNotBlank() } ?: throw FailureException(Failure.BadResponse)
    }

    private fun requireOk(response: HttpResponse) {
        if (response.status.value !in 200..299) throw FailureException(Failure.Http(response.status.value))
    }

    private companion object {
        const val LOGIN_PAGE = "/login"
        const val CAPTCHA_PATH = "/signup/captcha"
        const val SIGN_IN_PATH = "/FollowTheRabbit"
        const val SIGN_IN_BUTTON = "登录"
    }
}

/**
 * 登录出错页 → 原因。错误次数太多时网站整页说「N 分钟内您将不能登录本站」；其余只认出错页的那一句：
 * 提到验证码的是验证码错；提到密码、邮箱、帐号的是账号或密码错；其余（包括读不出那一句）按登录没成功。
 */
internal fun signInRejectionOf(html: String): RejectReason {
    if (LOCKED_OUT in html) return RejectReason.LOGIN_TOO_MANY_ATTEMPTS
    // 网站出错页正文：`<div class="message"> … <p class="text">原因</p>`
    val text = webDocumentOf(html).selectFirst("div.message p.text")?.text().orEmpty()
    return when {
        "验证码" in text -> RejectReason.LOGIN_CAPTCHA_WRONG
        CREDENTIAL_WORDS.any { it in text } -> RejectReason.LOGIN_CREDENTIALS_WRONG
        else -> RejectReason.LOGIN_FAILED
    }
}

private val CREDENTIAL_WORDS = listOf("密码", "邮箱", "Email", "帐号", "账号")

/** 错误次数太多被暂时禁止登录时，网站页面上的那句话。 */
private const val LOCKED_OUT = "分钟内您将不能登录本站"

/** 授权页里提交到授权地址的那张 POST 表单（[parsePostForm]）；找不到返回 null。 */
internal fun parseAuthorizeForm(html: String): WebForm? = parsePostForm(html, AUTHORIZE_PATH)
