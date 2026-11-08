package io.github.touhougo.chogumi.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.image.AvatarImages
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.web.TestWebSite
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 设置 › 个人资料：面板只在写成功之后才滑下去，失败时草稿留着、说原因；旧的结果不落到新面板上。 */
class ProfileSettingsViewModelTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private val main = TestMain()
    private val viewModels = mutableListOf<ProfileSettingsViewModel>()

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(viewModels.map { it.viewModelScope })

    /** 本地的用户缓存：[cached] 为 null 是空的；写入照收、不改它（测试只看先出的那一拍）。 */
    private class CachedUsers(private val cached: UserEntity?) : UserDao {
        override suspend fun get(id: Int): UserEntity? = cached?.takeIf { it.id == id }
        override suspend fun getByUsername(username: String): UserEntity? = cached?.takeIf { it.username == username }
        override suspend fun upsert(user: UserEntity) = Unit
    }

    /** 刚写进缓存的自己（在新鲜期内）：缓存表不存自我介绍。 */
    private fun freshCache() = UserEntity(id = 42, username = "fixture_user", nickname = "旧昵称", updatedAt = System.currentTimeMillis())

    /** 选图之后的处理：放行 [result] 之前一直在处理中。 */
    private class GatedAvatarImages : AvatarImages {
        val result = CompletableDeferred<Outcome<ByteArray>>()
        override suspend fun squareJpeg(uri: String): Outcome<ByteArray> = result.await()
    }

    private val settingsPage = """
        <form name="set" method="post" action="/settings" enctype="multipart/form-data">
          <input type="hidden" name="formhash" value="0a1b2c3d">
          <input type="text" name="nickname" value="旧昵称">
          <input type="text" name="sign_input" value="">
          <input type="file" name="picfile">
          <textarea name="newbio"></textarea>
          <input type="submit" name="submit" value="保存修改">
        </form>
    """.trimIndent()

    /**
     * 自己的资料：v0 /me 与 p1 资料都是「旧昵称」，p1 的自我介绍是 [BIO]；[landed] 时网页收到 POST 之后 p1 回「新昵称」，
     * 否则一直是旧的。[profileGate] 放行之前 p1 资料不回；[profileFails] 时 p1 资料回 500。目录、好友计数的端点回 404（资料页不画它们）。
     */
    private fun server(site: TestWebSite, landed: Boolean, profileGate: CompletableDeferred<Unit>?, profileFails: Boolean) = resources.apiClient(
        MockEngine { request ->
            if (request.url.encodedPath == "/p1/users/fixture_user") {
                profileGate?.await()
                if (profileFails) return@MockEngine respond("", HttpStatusCode.InternalServerError)
            }
            val nickname = if (landed && site.postCount > 0) "新昵称" else "旧昵称"
            val json = headersOf("Content-Type", ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/v0/me" -> respond(
                    """{"id":42,"username":"fixture_user","nickname":"旧昵称","user_group":10,"avatar":{"large":"l","medium":"m","small":"s"},"sign":""}""",
                    HttpStatusCode.OK,
                    json,
                )
                "/p1/users/fixture_user" -> respond(
                    """
                        {"id":42,"username":"fixture_user","nickname":"$nickname","avatar":{"large":"l","medium":"m","small":"s"},
                         "group":10,"joinedAt":1500000000,"sign":"","site":"","location":"","bio":"$BIO_JSON","networkServices":[],
                         "homepage":{"left":[],"right":[]},"stats":{"subject":{},"mono":{"character":0,"person":0},
                         "blog":0,"friend":0,"group":0,"index":{"create":0,"collect":0}},"isFriend":false}
                    """.trimIndent(),
                    HttpStatusCode.OK,
                    json,
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        },
    )

    private class Harness(val viewModel: ProfileSettingsViewModel, val site: TestWebSite, val images: GatedAvatarImages)

    /** [webUserId] 是网页上登录着的人（不是 42 就是网页会话已换了人）。 */
    private fun harness(
        landed: Boolean = true,
        webSession: Boolean = true,
        webUserId: Int = 42,
        cached: UserEntity? = null,
        profileGate: CompletableDeferred<Unit>? = null,
        profileFails: Boolean = false,
    ): Harness {
        val site = TestWebSite(userId = webUserId).apply { pages["/settings"] = settingsPage }
        val tokenStore = testTokenStore(resources, userId = 42)
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        if (webSession) runBlocking { webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e")) }
        val users = testUserRepository(resources, UserApi(server(site, landed, profileGate, profileFails)), tokenStore, CachedUsers(cached), webSessions = webSessions)
        val images = GatedAvatarImages()
        val viewModel = ProfileSettingsViewModel(users, images).also { viewModels += it }
        return Harness(viewModel, site, images)
    }

    private suspend fun Harness.awaitEditable() =
        awaitUntil("资料读到、能改") { viewModel.uiState.value.me is Load.Ready && viewModel.uiState.value.canEdit == true }

    private val ProfileSettingsUiState.nickname get() = ((me as Load.Ready).value).nickname

    @Test
    fun 缓存里没有自我介绍_面板只用网上读到的资料打开() = runBlocking {
        val profileGate = CompletableDeferred<Unit>()
        val h = harness(cached = freshCache(), profileGate = profileGate)
        awaitUntil("查过能改") { h.viewModel.uiState.value.canEdit == true }

        h.viewModel.openText(ProfileTextField.BIO)
        assertNull(h.viewModel.uiState.value.textEditor, "网上的资料还没到：不拿缓存（没有自我介绍）开面板")
        assertEquals(Load.Pending, h.viewModel.uiState.value.me, "缓存那一拍不画")

        profileGate.complete(Unit)
        h.awaitEditable()
        h.viewModel.openText(ProfileTextField.BIO)
        assertEquals(BIO, h.viewModel.uiState.value.textEditor?.original)
    }

    @Test
    fun p1资料取不到时页面是加载失败_不拿退回的基础资料开面板() = runBlocking {
        val h = harness(profileFails = true)
        awaitUntil("资料加载失败、查过能改") { h.viewModel.uiState.value.me is Load.Failed && h.viewModel.uiState.value.canEdit == true }

        h.viewModel.openText(ProfileTextField.BIO)
        assertNull(h.viewModel.uiState.value.textEditor, "v0 的基础资料没有自我介绍：不拿它开面板")
    }

    @Test
    fun 改昵称写成功面板滑下去_页面直接是回读到的新昵称() = runBlocking {
        val h = harness(landed = true)
        h.awaitEditable()

        h.viewModel.openText(ProfileTextField.NICKNAME)
        h.viewModel.changeText("新昵称")
        h.viewModel.saveText()

        awaitUntil("面板写成功") { h.viewModel.uiState.value.textEditor?.saved == true }
        awaitUntil("页面是回读到的新昵称") { h.viewModel.uiState.value.nickname == "新昵称" }
        assertNull(h.viewModel.failures.current.value)
    }

    @Test
    fun 回读还是旧值时面板与框里的字都留着_说原因() = runBlocking {
        val h = harness(landed = false)
        h.awaitEditable()

        h.viewModel.openText(ProfileTextField.NICKNAME)
        h.viewModel.changeText("新昵称")
        h.viewModel.saveText()

        awaitUntil("提示写失败") { h.viewModel.failures.current.value != null }
        val editor = assertNotNull(h.viewModel.uiState.value.textEditor)
        assertEquals("新昵称", editor.text)
        assertEquals(false, editor.saving)
        assertEquals(false, editor.saved)
        assertTrue(editor.canSave, "失败之后还能再保存一次")
        assertEquals(NoticeText.Reason(Failure.Rejected(RejectReason.PRIVACY_SETTING_UNAVAILABLE)), h.viewModel.failures.current.value?.text)
    }

    @Test
    fun 网页上换了人时网页会话被丢掉_页面随之只读() = runBlocking {
        val h = harness(webUserId = 7)
        h.awaitEditable()

        h.viewModel.openText(ProfileTextField.SIGN)
        h.viewModel.changeText("新签名")
        h.viewModel.saveText()

        awaitUntil("页面变成只读") { h.viewModel.uiState.value.canEdit == false }
        assertEquals(NoticeText.Reason(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)), h.viewModel.failures.current.value?.text)
        assertEquals("新签名", h.viewModel.uiState.value.textEditor?.text)
        assertEquals(0, h.site.postCount)
    }

    @Test
    fun 没有网页会话时只读_点了也不开面板() = runBlocking {
        val h = harness(webSession = false)
        awaitUntil("资料读到") { h.viewModel.uiState.value.me is Load.Ready }

        awaitUntil("查过本地没有网页会话") { h.viewModel.uiState.value.canEdit == false }
        h.viewModel.openText(ProfileTextField.NICKNAME)
        h.viewModel.pickAvatar("content://media/picker/1")
        assertNull(h.viewModel.uiState.value.textEditor)
        assertNull(h.viewModel.uiState.value.avatarEditor)
    }

    @Test
    fun 图片处理中关掉面板_晚到的结果不把面板打开() = runBlocking {
        val h = harness()
        h.awaitEditable()

        h.viewModel.pickAvatar("content://media/picker/1")
        assertNotNull(h.viewModel.uiState.value.avatarEditor)
        h.viewModel.closeAvatar()
        h.images.result.complete(Outcome.Success(byteArrayOf(1, 2, 3)))

        // 处理结果整轮在 Main 上落地（假的处理在 Main 上恢复）：排空 Main 就是它落定之后
        withContext(main.dispatcher) {}
        assertNull(h.viewModel.uiState.value.avatarEditor)
    }

    @Test
    fun 选好的图处理完才能保存() = runBlocking {
        val h = harness()
        h.awaitEditable()

        h.viewModel.pickAvatar("content://media/picker/1")
        assertEquals(false, h.viewModel.uiState.value.avatarEditor?.canSave)
        h.images.result.complete(Outcome.Success(byteArrayOf(1, 2, 3)))

        awaitUntil("预览就绪、能保存") { h.viewModel.uiState.value.avatarEditor?.canSave == true }
    }

    private companion object {
        /** 网页上现在的自我介绍（服务端回的换行是 `\r\n`）。 */
        const val BIO = "网页上的介绍\r\n第二行"
        const val BIO_JSON = "网页上的介绍\\r\\n第二行"
    }
}
