package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.web.TestWebSite
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [NsfwPreferenceRepository]：服务端偏好的缓存层。
 *
 * 覆盖设计要求的四类：GET/PATCH 成功、403、网络失败回滚、换号/登出不串号。
 * 全部走真实 TokenStore + 真实仓库 + MockEngine 冒充 `/p1/privacy`，
 * 不 mock 仓库本身 —— 缓存分区与"未登录恒 false"正是被测行为。
 */
class NsfwPreferenceRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private class Harness(
        val tokenStore: TokenStore,
        val repository: NsfwPreferenceRepository,
    )

    /** 每次请求由 [responder] 决定 /p1/privacy 的返回。 */
    private fun harness(site: TestWebSite? = null, responder: suspend MockRequestScope.() -> HttpResponseData): Harness {
        val dir = resources.directory("nsfw-repo")
        // MockEngine 的 handler 带 MockRequestHandleScope 接收者，respond/respondError 都是它的扩展，
        // 故把它连同请求一起交给 responder，否则 stand-alone 的 respond 无处可挂。
        val client = resources.apiClient(MockEngine { responder(MockRequestScope(this, it)) })
        val tokenStore = testTokenStore(resources)
        val repository = NsfwPreferenceRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = resources.scope) { File(dir, "prefs.preferences_pb") },
            privacyApi = PrivacyApi(client),
            tokenStore = tokenStore,
            webSessions = testWebSessionRepository(tokenStore, site?.client ?: noWebClient()),
        )
        return Harness(tokenStore, repository)
    }

    /** 请求上下文：[request] 供 responder 按方法/令牌分支，[ok]/[forbidden] 造响应。 */
    private inner class MockRequestScope(
        private val handle: MockRequestHandleScope,
        private val request: HttpRequestData,
    ) {
        val isPatch: Boolean get() = request.method == HttpMethod.Patch

        /** 响应体里的 allowNsfw 等服务端判定结果。 */
        fun ok(
            allowNsfw: Boolean,
            showNsfwSubject: Boolean = allowNsfw,
            canSet: Boolean = true,
        ): HttpResponseData = with(handle) {
            respond(
                content = privacyJson(allowNsfw, showNsfwSubject, canSet),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }

        /** PATCH 被拒：账号当前无权修改。 */
        fun forbidden(): HttpResponseData = with(handle) { respondError(HttpStatusCode.Forbidden) }

        /** PATCH 命中服务端 5xx：端点整体不可用（线上实测的形态）。 */
        fun serverError(): HttpResponseData = with(handle) {
            respond(
                content = """{"error":"Internal Server Error","message":"internal error, please contact admin","statusCode":500}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }

        /** PATCH 实际发出的请求体原文，用于钉死请求体形状。 */
        suspend fun patchBody(): String =
            (request.body as io.ktor.http.content.TextContent).text

        /** 该请求是否携带指定令牌（用于按账号分支的假服务端）。 */
        fun hasToken(token: String): Boolean = request.headers["Authorization"]?.contains(token) == true
    }

    private suspend fun TokenStore.loginAs(userId: Int, username: String = "user$userId") {
        val session = beginSession("token-$userId")
        completeSession(session, userId, username)
    }

    // ---- 未登录 ----

    @Test
    fun `未登录时开关恒为false且不读任何缓存`() = runTest {
        val h = harness { ok(allowNsfw = true) }

        assertFalse(h.repository.showNsfw(), "未登录必须恒为 false，即便服务端会返回 true")

        // 未登录连请求都不该发：没有账号可读，发出去只会拿 401
        val result = h.repository.refresh()
        assertIs<Outcome.Failed>(result)
        assertFalse(h.repository.showNsfw())
    }

    @Test
    fun `登出后开关回到false`() = runTest {
        val h = harness { ok(allowNsfw = true) }
        h.tokenStore.loginAs(1001)
        assertIs<Outcome.Success<NsfwPreference>>(h.repository.refresh())
        assertTrue(h.repository.showNsfw(), "已登录且服务端允许时开关打开")

        h.tokenStore.clear()

        assertFalse(h.repository.showNsfw(), "登出后无账号，恒为 false")
    }

    // ---- GET / PATCH 成功 ----

    @Test
    fun `GET成功后写入缓存且允许显示`() = runTest {
        val h = harness { ok(allowNsfw = true, showNsfwSubject = true) }
        h.tokenStore.loginAs(1001)

        val result = h.repository.refresh()

        val preference = assertIs<Outcome.Success<NsfwPreference>>(result).value
        assertEquals(NsfwPreference(allowNsfw = true, showNsfwSubject = true, canSetNsfwSubject = true), preference)
        assertTrue(h.repository.showNsfw())
        assertEquals(preference, h.repository.cached())
    }

    @Test
    fun `PATCH成功以响应体为准写入_而非乐观写入请求值`() = runTest {
        // 请求 showNsfwSubject=true，服务端因"注册未满 60 天"仍返回 allowNsfw=false。
        // 客户端必须信响应体：乐观写入 true 会让列表假装放出 NSFW，实际服务端照样剔除。
        val h = harness { ok(allowNsfw = false, showNsfwSubject = false, canSet = true) }
        h.tokenStore.loginAs(1001)
        h.repository.refresh()

        val result = h.repository.setShowNsfw(true)

        val preference = assertIs<Outcome.Success<NsfwPreference>>(result).value
        assertFalse(preference.allowNsfw, "以服务端响应体为准")
        assertFalse(preference.showNsfwSubject)
        assertFalse(h.repository.showNsfw(), "缓存里也不能是乐观值")
        assertEquals(preference, h.repository.cached())
    }

    @Test
    fun `PATCH成功后开启_缓存与过滤源同步为true`() = runTest {
        val h = harness { if (isPatch) ok(allowNsfw = true, showNsfwSubject = true) else ok(allowNsfw = false, showNsfwSubject = false) }
        h.tokenStore.loginAs(1001)
        h.repository.refresh()
        assertFalse(h.repository.showNsfw())

        assertIs<Outcome.Success<NsfwPreference>>(h.repository.setShowNsfw(true))

        assertTrue(h.repository.showNsfw())
    }

    @Test
    fun `关开关之前发出的读取晚到_作废重读_不盖掉刚写下的值`() = runTest {
        val serverShows = AtomicBoolean(true)
        val reads = AtomicInteger()
        val firstReadSent = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        val h = harness {
            if (isPatch) {
                serverShows.set(false)
                ok(allowNsfw = false)
            } else {
                val shows = serverShows.get()
                if (reads.incrementAndGet() == 1) {
                    firstReadSent.complete(Unit)
                    releaseFirstRead.await()
                }
                ok(allowNsfw = shows)
            }
        }
        h.tokenStore.loginAs(1001)

        val early = async { h.repository.refresh() }
        firstReadSent.await()
        assertIs<Outcome.Success<NsfwPreference>>(h.repository.setShowNsfw(false))
        releaseFirstRead.complete(Unit)

        assertFalse(assertIs<Outcome.Success<NsfwPreference>>(early.await()).value.allowNsfw, "旧读取作废，交出的是重读到的值")
        assertEquals(2, reads.get(), "关开关之后重读了一次")
        assertFalse(h.repository.showNsfw(), "缓存仍是关掉后的值，NSFW 不会因为旧读取重新露出")
    }

    // ---- 403 ----

    @Test
    fun `PATCH返回403视为账号无权修改_提示且置canSet为false`() = runTest {
        val h = harness { if (isPatch) forbidden() else ok(allowNsfw = false, showNsfwSubject = false, canSet = false) }
        h.tokenStore.loginAs(1001)
        h.repository.refresh()

        val result = h.repository.setShowNsfw(true)

        assertEquals(Failure.Rejected(RejectReason.NSFW_ACCOUNT_TOO_NEW), assertIs<Outcome.Failed>(result).failure)
        // 服务端是权威：记下"不可改"，UI 据此切禁用态
        assertFalse(h.repository.cached()!!.canSetNsfwSubject)
        assertFalse(h.repository.showNsfw(), "被拒后开关不得停在用户拨到的位置")
    }

    // ---- 5xx：端点整体不可用（2026-09-28 线上实测） ----

    @Test
    fun `PATCH返回500且没有网页会话时要账号密码登录_且不锁死开关`() = runTest {
        // 线上实测：此端点当前对**任何**请求体都返回 500，只能改走网页个人设置页；
        // 没有网页会话时给出可操作的原因（账号密码登录），而不是通用"服务器开小差"。
        val h = harness { if (isPatch) serverError() else ok(allowNsfw = false, showNsfwSubject = false, canSet = true) }
        h.tokenStore.loginAs(1001)
        h.repository.refresh()

        val result = h.repository.setShowNsfw(true)

        assertEquals(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED), assertIs<Outcome.Failed>(result).failure)
        // 5xx 不是"你无权"：服务端只是坏了。锁死开关会把一次故障变成永久降级，
        // 与 403 分支（记 canSet=false 并切禁用态）必须区分开。
        assertTrue(h.repository.cached()!!.canSetNsfwSubject, "5xx 不得把账号记成不可改")
        assertFalse(h.repository.showNsfw(), "服务端未接受，开关不得停在用户拨到的位置")
    }


    /** 个人设置页（形状照网页的个人设置表单裁剪）：NSFW 勾选框之外的每一项都要原样提交回去。 */
    private fun settingsPage(nsfwChecked: Boolean) = """<script>var CHOBITS_UID = 1001;</script>
        <form name="profile" method="post" action="/settings">
        <input type="hidden" name="formhash" value="0a1b2c3d" />
        <input type="text" name="nickname" value="Fixture User &amp; Co" />
        <input type="text" name="sign_input" value="hi" />
        <textarea name="newbio">第一行
第二行</textarea>
        <select name="timeoffsetnew"><option value="7">UTC+7</option><option value="8" selected="selected">UTC+8</option></select>
        <input type="checkbox" name="show_nsfw_subject" value="1" ${if (nsfwChecked) "checked=\"checked\"" else ""} />
        <input type="file" name="avatar" />
        <input type="submit" name="submit" value="保存修改" />
        </form>"""

    @Test
    fun `PATCH返回500时用网页会话提交个人设置表单_只改NSFW一项_回读确认后写缓存`() = runTest {
        val site = TestWebSite(userId = 1001)
        site.pages["/settings"] = settingsPage(nsfwChecked = false)
        val h = harness(site) {
            if (isPatch) serverError() else ok(allowNsfw = site.submitted.isNotEmpty(), showNsfwSubject = site.submitted.isNotEmpty())
        }
        h.tokenStore.loginAs(1001)
        testWebSessionRepository(h.tokenStore, site.client).save(h.tokenStore.session(), WebSession("chii_auth=c00k1e"))

        val result = h.repository.setShowNsfw(true)

        assertTrue(assertIs<Outcome.Success<NsfwPreference>>(result).value.showNsfwSubject)
        assertEquals(
            listOf(
                "https://bgm.tv/settings" to "formhash=0a1b2c3d&nickname=Fixture+User+%26+Co&sign_input=hi&newbio=%E7%AC%AC%E4%B8%80%E8%A1%8C%0A%E7%AC%AC%E4%BA%8C%E8%A1%8C" +
                    "&timeoffsetnew=8&show_nsfw_subject=1&submit=%E4%BF%9D%E5%AD%98%E4%BF%AE%E6%94%B9",
            ),
            site.submitted,
        )
        assertTrue(h.repository.showNsfw())
    }

    @Test
    fun `PATCH超时也用网页会话提交个人设置表单_回读确认后写缓存`() = runTest {
        val site = TestWebSite(userId = 1001)
        site.pages["/settings"] = settingsPage(nsfwChecked = false)
        val h = harness(site) {
            if (isPatch) throw java.net.SocketTimeoutException("Read timed out")
            ok(allowNsfw = site.submitted.isNotEmpty(), showNsfwSubject = site.submitted.isNotEmpty())
        }
        h.tokenStore.loginAs(1001)
        testWebSessionRepository(h.tokenStore, site.client).save(h.tokenStore.session(), WebSession("chii_auth=c00k1e"))

        val result = h.repository.setShowNsfw(true)

        assertTrue(assertIs<Outcome.Success<NsfwPreference>>(result).value.showNsfwSubject)
        assertEquals(listOf("https://bgm.tv/settings"), site.submitted.map { it.first }, "超时后要走网页提交个人设置表单")
        assertTrue(h.repository.showNsfw())
    }

    @Test
    fun `网页提交后回读仍没打开_是去网页端改`() = runTest {
        val site = TestWebSite(userId = 1001)
        site.pages["/settings"] = settingsPage(nsfwChecked = false)
        val h = harness(site) { if (isPatch) serverError() else ok(allowNsfw = false, showNsfwSubject = false) }
        h.tokenStore.loginAs(1001)
        testWebSessionRepository(h.tokenStore, site.client).save(h.tokenStore.session(), WebSession("chii_auth=c00k1e"))

        val result = h.repository.setShowNsfw(true)

        assertEquals(Failure.Rejected(RejectReason.PRIVACY_SETTING_UNAVAILABLE), assertIs<Outcome.Failed>(result).failure)
    }

    // ---- 请求体形状 ----

    @Test
    fun `PATCH请求体只含preferences_不擅自补settings`() = runTest {
        // 2026-09-28 实测：补全 settings **不能**修好 500 —— 两种形态都 500。
        // 故请求体保持最小形态，并在此钉死：补了 settings 的请求体会在此失败。
        var captured: String? = null
        val h = harness {
            if (isPatch) {
                captured = patchBody()
                ok(allowNsfw = true, showNsfwSubject = true)
            } else {
                ok(allowNsfw = false, showNsfwSubject = false)
            }
        }
        h.tokenStore.loginAs(1001)
        h.repository.refresh()

        h.repository.setShowNsfw(true)

        val json = Json.parseToJsonElement(captured!!).jsonObject
        assertEquals(setOf("preferences"), json.keys, "请求体只应有 preferences（实测补 settings 无助于修 500）")
        val prefs = json["preferences"]!!.jsonObject
        assertEquals(setOf("showNsfwSubject"), prefs.keys)
        assertEquals(true, prefs["showNsfwSubject"]!!.jsonPrimitive.boolean)
    }

    // ---- 网络失败 / 回滚 ----

    @Test
    fun `网络失败时保留已有缓存_不把已开启的NSFW突然藏起来`() = runTest {
        // 第一次成功开启，之后网络全挂
        var fail = false
        val h = harness {
            if (fail) throw java.io.IOException("boom")
            ok(allowNsfw = true, showNsfwSubject = true)
        }
        h.tokenStore.loginAs(1001)
        h.repository.refresh()
        assertTrue(h.repository.showNsfw())

        fail = true
        val result = h.repository.refresh()

        assertIs<Outcome.Failed>(result)
        assertTrue(h.repository.showNsfw(), "刷新失败不得清空缓存，否则已开启的 NSFW 会突然消失")
    }

    @Test
    fun `网络失败时cached为null_开关按false处理`() = runTest {
        val h = harness { throw java.io.IOException("boom") }
        h.tokenStore.loginAs(1001)

        assertIs<Outcome.Failed>(h.repository.refresh())

        assertEquals(null, h.repository.cached(), "首次就失败时缓存为空")
        assertFalse(h.repository.showNsfw(), "拿不准时不显示 NSFW（失败开放会意外露出）")
    }

    // ---- 换号 / 分区隔离 ----

    @Test
    fun `换号后读不到旧账号的缓存`() = runTest {
        // 服务端按 token 区分账号：A 允许、B 不允许
        val h = harness { val allow = hasToken("token-1001"); ok(allowNsfw = allow, showNsfwSubject = allow) }
        h.tokenStore.loginAs(1001)
        h.repository.refresh()
        assertTrue(h.repository.showNsfw(), "账号 A 允许")

        // 换号到 B：尚未 refresh 时也不能读到 A 的值
        h.tokenStore.loginAs(2002)

        assertFalse(h.repository.showNsfw(), "换号后绝不能读到旧账号的值")
        assertEquals(null, h.repository.cached(), "B 分区此刻为空")

        h.repository.refresh()
        assertFalse(h.repository.showNsfw(), "B 服务端不允许")
        assertEquals(false, h.repository.cached()!!.allowNsfw)
    }

    @Test
    fun `换回旧账号仍读到自己的缓存`() = runTest {
        val h = harness { val allow = hasToken("token-1001"); ok(allowNsfw = allow, showNsfwSubject = allow) }
        h.tokenStore.loginAs(1001)
        h.repository.refresh()
        h.tokenStore.loginAs(2002)
        h.repository.refresh()

        h.tokenStore.loginAs(1001)

        assertTrue(h.repository.showNsfw(), "分区隔离：各自缓存互不覆盖")
    }

    private fun privacyJson(allowNsfw: Boolean, showNsfwSubject: Boolean, canSet: Boolean): String = """
        {
          "settings": {
            "privateMessage": "all", "timelineReply": "all", "timelineCollectReply": "all",
            "follow": "all", "mentionNotification": "all", "commentNotification": "all",
            "friendNotification": "all"
          },
          "preferences": {
            "showNsfwSubject": $showNsfwSubject,
            "canSetNsfwSubject": $canSet,
            "allowNsfw": $allowNsfw
          }
        }
    """.trimIndent()
}
