package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.web.TestWebSite
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.ProfileChange
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** 改自己的资料：网页会话现读个人设置页、只换这一项提交，回读 p1 资料确认做成。 */
class ProfileEditTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private object NoCachedUsers : UserDao {
        override suspend fun get(id: Int): UserEntity? = null
        override suspend fun getByUsername(username: String): UserEntity? = null
        override suspend fun upsert(user: UserEntity) = Unit
    }

    /** 网页上的个人设置页：字段与线上 `/settings` 的表单一致（分段上传）。 */
    private val settingsPage = """
        <form name="set" method="post" action="/settings" enctype="multipart/form-data">
          <input type="hidden" name="formhash" value="0a1b2c3d">
          <input type="text" name="nickname" value="Fixture User">
          <input type="text" name="sign_input" value="hi">
          <input type="file" name="picfile">
          <input type="text" name="username" value="">
          <input type="text" name="newsite" value="">
          <textarea name="newbio">旧的介绍</textarea>
          <input type="checkbox" name="show_nsfw_subject" value="1">
          <select name="timeoffsetnew"><option value="8" selected>+8</option></select>
          <input type="submit" name="submit" value="保存修改">
        </form>
    """.trimIndent()

    /** p1 资料：提交前是 [before]，网页收到过 POST 之后是 [after]（回读时服务端已经改好）。 */
    private fun profileServer(site: TestWebSite, before: Pair<String, String>, after: Pair<String, String>) = resources.apiClient(
        MockEngine { request ->
            check(request.url.encodedPath == "/p1/users/fixture_user") { "不该请求 ${request.url}" }
            val (nickname, avatar) = if (site.postCount > 0) after else before
            respond(
                content = """
                    {"id":42,"username":"fixture_user","nickname":"$nickname","avatar":{"large":"$avatar","medium":"m","small":"s"},
                     "group":10,"joinedAt":1500000000,"sign":"hi","site":"","location":"","bio":"","networkServices":[],
                     "homepage":{"left":[],"right":[]},"stats":{"subject":{},"mono":{"character":0,"person":0},
                     "blog":0,"friend":0,"group":0,"index":{"create":0,"collect":0}},"isFriend":false}
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        },
    )

    private class Harness(val repo: UserRepository, val site: TestWebSite)

    private suspend fun harness(
        before: Pair<String, String> = "Fixture User" to "https://lain.bgm.tv/pic/user/l/42.jpg?r=1",
        after: Pair<String, String> = before,
        webSession: Boolean = true,
    ): Harness {
        val site = TestWebSite(userId = 42).apply { pages["/settings"] = settingsPage }
        val tokenStore: TokenStore = testTokenStore(resources, userId = 42)
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        if (webSession) webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val repo = testUserRepository(
            resources, UserApi(profileServer(site, before, after)), tokenStore, NoCachedUsers, webSessions = webSessions,
        )
        return Harness(repo, site)
    }

    @Test
    fun `改昵称提交到网页_回读是新昵称才算做成_做成后通知重读`() = runTest {
        val h = harness(after = "新昵称" to "https://lain.bgm.tv/pic/user/l/42.jpg?r=1")

        val result = h.repo.editProfile(ProfileChange.Nickname("  新昵称 "))
        assertEquals("新昵称", assertIs<Outcome.Success<UserBo>>(result).value.nickname, "回回读到的资料")
        assertEquals(1, h.site.postCount)
        assertEquals(1, h.repo.profileEdits.value)
    }

    @Test
    fun `提交了但回读还是旧值_说去网页端改_不通知重读`() = runTest {
        val h = harness()

        assertEquals(
            Outcome.Failed(Failure.Rejected(RejectReason.PRIVACY_SETTING_UNAVAILABLE)),
            h.repo.editProfile(ProfileChange.Nickname("新昵称")),
        )
        assertEquals(1, h.site.postCount)
        assertEquals(0, h.repo.profileEdits.value)
    }

    @Test
    fun `换头像看回读的地址变了没有`() = runTest {
        val old = "https://lain.bgm.tv/pic/user/l/42.jpg?r=1"
        val unchanged = harness(before = "Fixture User" to old)
        val changed = harness(before = "Fixture User" to old, after = "Fixture User" to "https://lain.bgm.tv/pic/user/l/42.jpg?r=2")

        assertEquals(
            Outcome.Failed(Failure.Rejected(RejectReason.PRIVACY_SETTING_UNAVAILABLE)),
            unchanged.repo.editProfile(ProfileChange.Avatar(byteArrayOf(1, 2, 3))),
        )
        assertIs<Outcome.Success<UserBo>>(changed.repo.editProfile(ProfileChange.Avatar(byteArrayOf(1, 2, 3))))
    }

    @Test
    fun `没有网页会话时不能改_改也是要账号密码登录_不打开网页`() = runTest {
        val h = harness(webSession = false)

        assertFalse(h.repo.canEditProfile())
        assertEquals(
            Outcome.Failed(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)),
            h.repo.editProfile(ProfileChange.Sign("新签名")),
        )
        assertEquals(0, h.site.postCount)
    }

    @Test
    fun `存着网页会话时能改`() = runTest {
        assertTrue(harness().repo.canEditProfile())
    }
}
