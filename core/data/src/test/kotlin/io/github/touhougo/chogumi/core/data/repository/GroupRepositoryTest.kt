package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.web.TestWebSite
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.network.api.CommunityApi
import io.github.touhougo.chogumi.core.network.api.groupMembershipLinkOf
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * 小组仓库：我加入的小组的请求与映射、小组页头部的「已加入」、用网页会话加入与退出、本机置顶名单按账号分开。
 * JSON 形状照 p1 接口文档裁剪，小组换成虚构值。
 */
class GroupRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private val requests = ConcurrentRequestRecorder()

    private val slimGroup = """{"id":3,"name":"boring","nsfw":false,"title":"靠谱人生茶话会","icon":{"small":"gs","medium":"gm","large":"gl"},
        "creatorID":1,"members":16144,"accessible":true,"createdAt":1}"""

    private fun group(role: Int?) = """{"id":3,"cat":1,"name":"boring","nsfw":false,"title":"靠谱人生茶话会","icon":{"small":"gs","medium":"gm","large":"gl"},
        "creatorID":1,"topics":120,"posts":900,"members":16144,"description":"闲聊","accessible":true,"createdAt":1}"""
        .let { if (role == null) it else it.dropLast(1) + ""","membership":{"uid":7,"role":$role,"joinedAt":1}}""" }

    private class Setup(val repository: GroupRepository, val tokenStore: TokenStore)

    /**
     * 按请求路径给响应，没给的回空页；[groupPage] 给了时小组页头部每次现取（网页操作前后不同）。
     * [site] 非空时账号同时有这个网页的会话（账号密码登录）。
     */
    private suspend fun TestScope.setup(
        userId: Int?,
        responses: Map<String, String> = emptyMap(),
        site: TestWebSite? = null,
        groupPage: (() -> String)? = null,
        onRequest: suspend () -> Unit = {},
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Setup {
        val engine = MockEngine { request ->
            requests.record(request)
            onRequest()
            val path = request.url.encodedPath
            respond(
                groupPage?.takeIf { path == "/p1/groups/boring" }?.invoke() ?: responses[path] ?: """{"data":[],"total":0}""",
                status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val tokenStore = testTokenStore(scope = backgroundScope)
        val session = userId?.let { tokenStore.completeSession(tokenStore.beginSession("token"), userId = it, username = "u$it") }
        val webSessions = testWebSessionRepository(tokenStore, site?.client ?: noWebClient())
        if (site != null && session != null) webSessions.save(session, WebSession("chii_auth=c00k1e"))
        val dir = resources.directory("group")
        val preferences = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "prefs.preferences_pb") }
        return Setup(GroupRepositoryImpl(CommunityApi(client), tokenStore, preferences, webSessions), tokenStore)
    }

    @Test
    fun `我加入的小组：按成员数一次取满一页，图标取最大的一份`() = runTest {
        val joined = """{"data":[$slimGroup],"total":1}"""
        val repository = setup(userId = 7, responses = mapOf("/p1/groups" to joined)).repository

        val groups = repository.getJoinedGroups()

        assertEquals(listOf(GroupSummaryBo(name = "boring", title = "靠谱人生茶话会", icon = "gl", members = 16144)), groups)
        val params = requests.single().url.parameters
        assertEquals(listOf("joined", "members", "100", "0"), listOf(params["mode"], params["sort"], params["limit"], params["offset"]))
    }

    @Test
    fun `我加入的小组：同时要两次只发一次请求，两边拿到同一份`() = runTest {
        val joined = """{"data":[$slimGroup],"total":1}"""
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = setup(
            userId = 7,
            responses = mapOf("/p1/groups" to joined),
            onRequest = { arrived.complete(Unit); release.await() },
        ).repository

        val row = async { repository.getJoinedGroups() }
        arrived.await()
        val feed = async { repository.getJoinedGroups() }
        // 第二次进到等待里再放行第一次的响应
        testScheduler.runCurrent()
        release.complete(Unit)

        assertEquals(row.await(), feed.await())
        assertEquals(1, requests.toList().size)
    }

    @Test
    fun `我加入的小组：未登录不发请求，是空表`() = runTest {
        val repository = setup(userId = null).repository

        assertEquals(emptyList(), repository.getJoinedGroups())
        assertTrue(requests.toList().isEmpty())
    }

    @Test
    fun `小组页头部：成员与禁言成员算已加入，访客、游客与没有身份不算`() = runTest {
        suspend fun joinedAs(role: Int?): Boolean =
            setup(userId = 7, responses = mapOf("/p1/groups/boring" to group(role))).repository.getGroup("boring").joined

        assertEquals(listOf(true, true, true, true), listOf(0, 1, 2, 3).map { joinedAs(it) })
        assertEquals(listOf(false, false, false), listOf(-2, -1, null).map { joinedAs(it) })
    }

    /** 小组网页：未加入时侧栏是加入链接，加入后是退出链接（校验值由网页给，与账号页的不同）。 */
    private fun groupWebPage(joined: Boolean) =
        if (joined) """<span id="groupJoinAction"><a href="/group/boring/bye?gh=77aa" class="chiiBtn">退出小组</a></span>"""
        else """<span id="groupJoinAction"><a href="/group/boring/join?gh=66bb&amp;x=1" class="chiiBtn">加入小组</a></span>"""

    @Test
    fun `加入与退出：带网页会话点小组网页上的链接，回读接口确认`() = runTest {
        val site = TestWebSite(userId = 7)
        site.pages["/group/boring"] = groupWebPage(joined = false)
        val repository = setup(userId = 7, site = site, groupPage = { group(role = if (site.opened.size % 2 == 1) 0 else null) }).repository

        val joined = repository.setJoined("boring", joined = true)
        site.pages["/group/boring"] = groupWebPage(joined = true)
        val left = repository.setJoined("boring", joined = false)

        assertEquals(true, (joined as Outcome.Success).value.joined)
        assertEquals(false, (left as Outcome.Success).value.joined)
        assertEquals(
            listOf<Pair<String, String?>>(
                "https://bgm.tv/group/boring/join?gh=66bb&x=1" to "chii_auth=c00k1e",
                "https://bgm.tv/group/boring/bye?gh=77aa" to "chii_auth=c00k1e",
            ),
            site.opened,
        )
        assertEquals(listOf<String?>("https://bgm.tv/group/boring", "https://bgm.tv/group/boring"), site.referers)
    }

    @Test
    fun `加入小组响应期间换号不回读新账号的小组身份`() = runTest {
        val site = TestWebSite(userId = 7)
        site.pages["/group/boring"] = groupWebPage(joined = false)
        val setup = setup(userId = 7, site = site, groupPage = { group(role = 0) })
        site.onOpen = { setup.tokenStore.completeSession(setup.tokenStore.beginSession("other"), userId = 8, username = "bob") }

        assertEquals(Outcome.Failed(Failure.SessionChanged), setup.repository.setJoined("boring", joined = true))
        assertTrue(requests.toList().isEmpty())
    }

    @Test
    fun `小组发帖响应期间换号不返回新帖成功`() = runTest {
        lateinit var tokenStore: TokenStore
        val setup = setup(userId = 7, responses = mapOf("/p1/groups/boring/topics" to """{"id":4321}"""), onRequest = {
            tokenStore.completeSession(tokenStore.beginSession("other"), userId = 8, username = "bob")
        })
        tokenStore = setup.tokenStore

        assertEquals(Outcome.Failed(Failure.SessionChanged), setup.repository.createTopic("boring", "标题", "正文", "tk"))
        assertEquals("Bearer token", requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `小组确认回读失败期间换号不报告旧账号失败`() = runTest {
        val site = TestWebSite(userId = 7)
        site.pages["/group/boring"] = groupWebPage(joined = false)
        lateinit var tokenStore: TokenStore
        val setup = setup(userId = 7, site = site, status = HttpStatusCode.InternalServerError, onRequest = {
            tokenStore.completeSession(tokenStore.beginSession("other"), userId = 8, username = "bob")
        })
        tokenStore = setup.tokenStore

        assertEquals(Outcome.Failed(Failure.SessionChanged), setup.repository.setJoined("boring", joined = true))
        assertEquals("Bearer token", requests.single().headers[HttpHeaders.Authorization])
    }

    /** 小组简介里用户写的链接排在侧栏的加入按钮前面：外站的同名链接不算，只点站内那个，登录 Cookie 不出本站。 */
    @Test
    fun `页面先出现外站的加入链接：只点站内的`() = runTest {
        val site = TestWebSite(userId = 7)
        site.pages["/group/boring"] =
            """<div class="intro"><a href="https://evil.example/group/boring/join?gh=1">点这里</a></div>""" + groupWebPage(joined = false)
        val repository = setup(userId = 7, site = site, groupPage = { group(role = if (site.opened.isEmpty()) null else 0) }).repository

        val joined = repository.setJoined("boring", joined = true)

        assertEquals(true, (joined as Outcome.Success).value.joined)
        assertEquals(listOf("https://bgm.tv/group/boring/join?gh=66bb&x=1"), site.opened.map { it.first })
        assertEquals("https://bgm.tv/group/boring/bye", groupMembershipLinkOf("""<a href="https://bgm.tv/group/boring/bye">""", "boring", joined = false))
    }

    /** 网页对加入链接先回一页确认表单：照页面提交（只带隐藏字段与提交按钮），提交后才算点了。 */
    @Test
    fun `加入链接回确认表单：照页面提交到加入地址`() = runTest {
        val site = TestWebSite(userId = 7)
        site.pages["/group/boring"] = groupWebPage(joined = false)
        site.confirming += "/group/boring/join"
        val repository = setup(userId = 7, site = site, groupPage = { group(role = if (site.submitted.isEmpty()) null else 0) }).repository

        val joined = repository.setJoined("boring", joined = true)

        assertEquals(true, (joined as Outcome.Success).value.joined)
        assertEquals(
            listOf("https://bgm.tv/group/boring/join?gh=66bb&x=1" to "formhash=0a1b2c3d&submit=%E7%A1%AE%E5%AE%9A"),
            site.submitted,
        )
    }

    @Test
    fun `网页上没有要的链接：说成在网页端操作，不开任何操作链接`() = runTest {
        val site = TestWebSite(userId = 7)
        site.pages["/group/boring"] = groupWebPage(joined = true)
        val repository = setup(userId = 7, site = site, groupPage = { group(role = 0) }).repository

        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.GROUP_MEMBERSHIP_UNAVAILABLE)), repository.setJoined("boring", joined = true))
        assertTrue(site.opened.isEmpty())
    }

    @Test
    fun `加入后回读仍没加入：说成在网页端操作`() = runTest {
        val site = TestWebSite(userId = 7)
        site.pages["/group/boring"] = groupWebPage(joined = false)
        val repository = setup(userId = 7, site = site, groupPage = { group(role = null) }).repository

        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.GROUP_MEMBERSHIP_UNAVAILABLE)), repository.setJoined("boring", joined = true))
    }

    @Test
    fun `没有网页会话：要账号密码登录，不开网页`() = runTest {
        val repository = setup(userId = 7, groupPage = { group(role = null) }).repository

        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)), repository.setJoined("boring", joined = true))
        assertTrue(requests.toList().isEmpty())
    }

    @Test
    fun `置顶名单：最近置顶的在前，取消置顶即移出，换号各看各的`() = runTest {
        val setup = setup(userId = 7)
        val repository = setup.repository

        repository.setPinned("boring", pinned = true)
        repository.setPinned("a", pinned = true)
        repository.setPinned("boring", pinned = true)
        assertEquals(Load.Ready(listOf("boring", "a")), repository.pinned.first())

        repository.setPinned("a", pinned = false)
        assertEquals(Load.Ready(listOf("boring")), repository.pinned.first())

        setup.tokenStore.completeSession(setup.tokenStore.beginSession("other"), userId = 8, username = "u8")
        assertEquals(Load.Ready(emptyList()), repository.pinned.first())
        repository.setPinned("c", pinned = true)
        assertEquals(Load.Ready(listOf("c")), repository.pinned.first())

        setup.tokenStore.completeSession(setup.tokenStore.beginSession("token"), userId = 7, username = "u7")
        assertEquals(Load.Ready(listOf("boring")), repository.pinned.first())
    }

    @Test
    fun `置顶名单：未登录为空，置顶什么都不存`() = runTest {
        val repository = setup(userId = null).repository

        repository.setPinned("boring", pinned = true)

        assertEquals(Load.Ready(emptyList()), repository.pinned.first())
    }
}
