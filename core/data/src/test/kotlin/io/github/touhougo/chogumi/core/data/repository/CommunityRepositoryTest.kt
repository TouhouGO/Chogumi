package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CommunityTopicBo
import io.github.touhougo.chogumi.core.model.CommunityTopicSource
import io.github.touhougo.chogumi.core.model.GroupBo
import io.github.touhougo.chogumi.core.model.GroupFeedCursor
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.model.GroupTopicScope
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.RakuenFilter
import io.github.touhougo.chogumi.core.model.groupOf
import io.github.touhougo.chogumi.core.network.api.CommunityApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * 社区帖子流仓库：小组新帖的范围（我加入的 → 没有时退回全部）、超展开各分类的请求与映射。
 * JSON 形状照 2026-10-13 实测响应裁剪，用户与小组换成虚构值。
 */
class CommunityRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private val requests = ConcurrentRequestRecorder()

    private val user = """{"id":1,"username":"u1","nickname":"族长","avatar":{"small":"s","medium":"m","large":"l"},"group":10,"sign":"","joinedAt":0,"isFriend":false}"""

    private val group = """{"id":3,"name":"boring","nsfw":false,"title":"靠谱人生茶话会","icon":{"small":"gs","medium":"gm","large":"gl"},
        "creatorID":1,"members":16144,"accessible":true,"createdAt":1}"""

    private fun groupTopic(id: Int, title: String, creator: String = user) =
        """{"id":$id,"title":"$title","creatorID":1,"creator":$creator,"parentID":3,"replyCount":9,"createdAt":1,"updatedAt":1791900335,
            "state":0,"display":1,"group":$group,"replies":[]}"""

    private val subject = """{"id":545008,"name":"Akujo","nameCN":"恶女不才","type":2,
        "images":{"large":"cl","common":"cc","medium":"cm","small":"cs","grid":"cg"},
        "info":"","metaTags":[],"rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false}"""

    /** 按请求路径与 `mode` 给响应；没给的组合回空页。 */
    private suspend fun TestScope.repository(
        loggedIn: Boolean,
        responses: Map<String, String>,
        tokenStore: TokenStore = testTokenStore(scope = backgroundScope),
        /** 回响应之前先跑它：用例在这里卡住请求，期间切换账号。 */
        onRequest: (suspend () -> Unit)? = null,
        /** 实时加入名单（顶部那一行用的那份）。 */
        joined: List<GroupSummaryBo> = emptyList(),
        /** 按路径与 `offset` 给响应（同一路径不同页回不同内容）；回 null 时照 [responses]。 */
        pageOf: (path: String, offset: String?) -> String? = { _, _ -> null },
        /** 这些路径回 500。 */
        failing: Set<String> = emptySet(),
    ): CommunityRepository {
        val engine = MockEngine { request ->
            requests.record(request)
            onRequest?.invoke()
            val key = listOfNotNull(request.url.encodedPath, request.url.parameters["mode"]).joinToString("?")
            if (request.url.encodedPath in failing) return@MockEngine respond("{}", HttpStatusCode.InternalServerError)
            respond(
                pageOf(request.url.encodedPath, request.url.parameters["offset"]) ?: responses[key] ?: """{"data":[],"total":0}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        if (loggedIn) {
            val session = tokenStore.beginSession("token")
            tokenStore.completeSession(session, userId = 7, username = "me")
        }
        return CommunityRepositoryImpl(CommunityApi(client), tokenStore, JoinedGroupsOnly(joined))
    }

    /** 只回实时加入名单的小组仓库：社区帖子流只用它这一项。 */
    private class JoinedGroupsOnly(private val joined: List<GroupSummaryBo>) : GroupRepository {
        override suspend fun getJoinedGroups(): List<GroupSummaryBo> = joined
        override suspend fun getGroup(name: String): GroupBo = error("不在这里用")
        override suspend fun getTopics(name: String, offset: Int): PagedResult<CommunityTopicBo> = error("不在这里用")
        override suspend fun getAllGroups(offset: Int): PagedResult<GroupSummaryBo> = error("不在这里用")
        override suspend fun createTopic(name: String, title: String, content: String, turnstileToken: String): Outcome<Int> = error("不在这里用")
        override suspend fun setJoined(name: String, joined: Boolean): Outcome<GroupBo> = error("不在这里用")
        override val pinned: Flow<Load<List<String>>> = emptyFlow()
        override suspend fun setPinned(name: String, pinned: Boolean) = Unit
    }

    /** 小组新帖的一页。 */
    private suspend fun CommunityRepository.feed(scope: GroupTopicScope?, cursor: GroupFeedCursor = GroupFeedCursor.START) =
        getGroupFeed(scope, cursor)

    @Test
    fun `我的帖子请求途中换了账号 丢掉上一个账号的帖子`() = runTest {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tokenStore = testTokenStore(scope = backgroundScope)
        val created = """{"data":[${groupTopic(473005, "1TB 根本不够用")}],"total":1}"""
        val repository = repository(
            loggedIn = true,
            responses = mapOf("/p1/groups/-/topics?created" to created),
            tokenStore = tokenStore,
            onRequest = { arrived.complete(Unit); release.await() },
        )
        val result = async { outcomeOf { repository.getMyTopics(GroupTopicScope.CREATED, offset = 0) } }
        arrived.await()

        tokenStore.completeSession(tokenStore.beginSession("other-token"), userId = 8, username = "other")
        release.complete(Unit)

        assertEquals(Outcome.Failed(Failure.SessionChanged), result.await())
    }

    @Test
    fun `小组新帖：登录了但没加入小组时是全部小组，后面的页沿用全部`() = runTest {
        val all = """{"data":[${groupTopic(473005, "1TB 根本不够用")}],"total":45}"""
        val repository = repository(loggedIn = true, responses = mapOf("/p1/groups/-/topics?all" to all))

        val first = repository.feed(scope = null)

        assertEquals(listOf("all"), requests.map { it.url.parameters["mode"] })
        assertEquals(GroupTopicScope.ALL, first.scope)
        assertEquals(20, first.next?.offset)
        val topic = first.items.single()
        assertEquals("group:473005", topic.key)
        assertEquals("https://bgm.tv/group/topic/473005", topic.webUrl)
        assertEquals(CommunityTopicSource.Group(GroupSummaryBo(name = "boring", title = "靠谱人生茶话会", icon = "gl", members = 16144)), topic.source)
        assertEquals("族长", topic.author?.nickname)
        assertEquals(1_791_900_335_000L, topic.updatedAt)

        requests.clear()
        repository.feed(scope = first.scope, cursor = first.next!!)
        assertEquals("all", requests.single().url.parameters["mode"])
        assertEquals("20", requests.single().url.parameters["offset"])
    }

    @Test
    fun `小组新帖：未登录是全部小组`() = runTest {
        val guest = repository(loggedIn = false, responses = emptyMap(), joined = listOf(summary("boring")))
        assertEquals(GroupTopicScope.ALL, guest.feed(scope = null).scope)
        assertEquals(listOf("all"), requests.map { it.url.parameters["mode"] })
    }

    @Test
    fun `超展开：一次取到上限、没有下一页，五种帖子各自映射成来源与去处`() = runTest {
        val rakuen = """{"data":[
            {"type":"group","id":472624,"title":"深夜所思","replyCount":4,"creator":$user,"group":$group,"updatedAt":1791900443},
            {"type":"subject","id":36001,"title":"第三季 &amp; 续作","replyCount":12,"creator":$user,"subject":$subject,"updatedAt":1791900400},
            {"type":"episode","id":1704982,"subject":$subject,
             "episode":{"id":1704982,"sort":11,"type":0,"name":"Akujo","nameCN":"真是个不得了的恶女","comment":89},"updatedAt":1791900437},
            {"type":"character","id":133201,"name":"Mutsu","nameCN":"陆奥驹絽",
             "images":{"large":"pl","medium":"pm","small":"ps","grid":"pg"},"comment":38,"updatedAt":1791900447},
            {"type":"person","id":5203,"name":"本多真梨子","nameCN":"","comment":47,"updatedAt":1791900324}
        ],"total":5}"""
        val page = repository(loggedIn = false, responses = mapOf("/p1/rakuen/topics" to rakuen)).getRakuen(RakuenFilter.ALL, offset = 0)

        assertEquals("all", requests.single().url.parameters["type"])
        assertEquals("200", requests.single().url.parameters["limit"])
        assertNull(page.nextOffset)
        assertEquals(
            listOf("group:472624", "subject:36001", "episode:1704982", "character:133201", "person:5203"),
            page.items.map { it.key },
        )
        assertEquals(
            listOf(
                "https://bgm.tv/group/topic/472624",
                "https://bgm.tv/subject/topic/36001",
                "https://bgm.tv/ep/1704982",
                "https://bgm.tv/character/133201",
                "https://bgm.tv/person/5203",
            ),
            page.items.map { it.webUrl },
        )
        val (_, subjectTopic, episode, character, person) = page.items
        assertEquals("第三季 & 续作", subjectTopic.title)
        assertEquals("恶女不才", (subjectTopic.source as CommunityTopicSource.Subject).subject.nameCn)
        assertEquals("真是个不得了的恶女", episode.title)
        assertEquals(11.0, episode.episode?.sort)
        assertEquals(89, episode.replies)
        assertNull(episode.author)
        assertEquals("陆奥驹絽", character.title)
        assertEquals(CommunityTopicSource.Mono(MonoType.CHARACTER, imageUrl = "pm"), character.source)
        assertEquals("本多真梨子", person.title)
        assertEquals(CommunityTopicSource.Mono(MonoType.PERSON, imageUrl = null), person.source)
    }

    @Test
    fun `超展开里有认不出种类的一条_跳过它_其余照常`() = runTest {
        val rakuen = """{"data":[
            {"type":"group","id":1,"title":"前","replyCount":0,"creator":$user,"group":$group,"updatedAt":1791900443},
            {"type":"blog","id":2,"title":"服务端新加的种类","creator":$user,"updatedAt":1791900440},
            {"type":"person","id":3,"name":"后","nameCN":"","comment":0,"updatedAt":1791900324}
        ],"total":3}"""
        val page = repository(loggedIn = false, responses = mapOf("/p1/rakuen/topics" to rakuen)).getRakuen(RakuenFilter.ALL, offset = 0)

        assertEquals(listOf("group:1", "person:3"), page.items.map { it.key })
        assertEquals(2, page.total)
    }

    @Test
    fun `超展开的「小组」分类取全部小组的新帖，按 offset 翻页`() = runTest {
        val all = """{"data":[${groupTopic(2, "全站小组帖")}],"total":45}"""
        val page = repository(loggedIn = false, responses = mapOf("/p1/groups/-/topics?all" to all)).getRakuen(RakuenFilter.GROUP, offset = 40)

        assertEquals("/p1/groups/-/topics", requests.single().url.encodedPath)
        assertEquals("40", requests.single().url.parameters["offset"])
        assertEquals("group:2", page.items.single().key)
        assertNull(page.nextOffset)
    }

    /** 小组自己的帖子接口回的一帖（不带小组）。 */
    private fun ownTopic(id: Int, updatedAt: Long, replies: Int = 0) =
        """{"id":$id,"title":"t$id","creatorID":1,"creator":$user,"parentID":4,"replyCount":$replies,"createdAt":1,"updatedAt":$updatedAt,"state":0,"display":1}"""

    private fun page(total: Int, vararg topics: String) = """{"data":[${topics.joinToString(",")}],"total":$total}"""

    private fun summary(name: String) = GroupSummaryBo(name = name, title = name, icon = null, members = 1)

    @Test
    fun `我加入的小组：每个小组取自己的帖子列表，按最后回复时间合成一页，回复数是列表里的`() = runTest {
        // 服务端「我加入的」流里同一帖是缓存里的 0 回复：不该再用它
        val stale = page(45, groupTopic(11, "t11").replace(""""replyCount":9""", """"replyCount":0"""))
        val repository = repository(
            loggedIn = true,
            responses = mapOf(
                "/p1/groups/-/topics?joined" to stale,
                "/p1/groups/boring/topics" to page(2, ownTopic(11, updatedAt = 950, replies = 22), ownTopic(12, updatedAt = 800, replies = 2)),
                "/p1/groups/a/topics" to page(2, ownTopic(21, updatedAt = 900), ownTopic(22, updatedAt = 100)),
            ),
            joined = listOf(summary("boring"), summary("a")),
        )

        val first = repository.feed(scope = null)

        assertEquals(GroupTopicScope.JOINED, first.scope)
        assertEquals(listOf("group:11", "group:21", "group:12", "group:22"), first.items.map { it.key })
        assertEquals(listOf(22, 0, 2, 0), first.items.map { it.replies })
        assertEquals(listOf("boring", "a", "boring", "a"), first.items.map { groupOf(it)?.name })
        assertEquals(950_000L, first.items.first().updatedAt)
        assertNull(first.next)
        assertEquals(0, requests.count { it.url.encodedPath == "/p1/groups/-/topics" })
    }

    @Test
    fun `一个小组取到的帖子排完、它后面还有时先取它的下一页；没排进这一页的留到下一页，不再重取`() = runTest {
        val busy = page(25, *(1..20).map { ownTopic(100 + it, updatedAt = 1000L - 2 * it) }.toTypedArray())
        val busyMore = page(25, *(21..25).map { ownTopic(100 + it, updatedAt = 1000L - 2 * it) }.toTypedArray())
        val quiet = page(2, ownTopic(1, updatedAt = 981), ownTopic(2, updatedAt = 955))
        val repository = repository(
            loggedIn = true,
            responses = mapOf("/p1/groups/quiet/topics" to quiet),
            joined = listOf(summary("busy"), summary("quiet")),
            pageOf = { path, offset -> if (path == "/p1/groups/busy/topics") (if (offset == "0") busy else busyMore) else null },
        )

        val first = repository.feed(scope = null)

        // busy 前 9 帖（998..982）、quiet 的 981、busy 再 10 帖（980..962），共 20 帖
        assertEquals((101..109).map { "group:$it" } + "group:1" + (110..119).map { "group:$it" }, first.items.map { it.key })
        assertEquals(2, requests.count())
        assertEquals(setOf("/p1/groups/busy/topics" to "0", "/p1/groups/quiet/topics" to "0"), requests.map { it.url.encodedPath to it.url.parameters["offset"] }.toSet())

        requests.clear()
        val second = repository.feed(scope = first.scope, cursor = first.next!!)

        // busy 剩下的 120（960）、quiet 的 2（955）都在手里；busy 排完 120 后先取它的下一页（offset 20，958..950）再接着排
        assertEquals(listOf("group:120", "group:121", "group:122", "group:2", "group:123", "group:124", "group:125"), second.items.map { it.key })
        assertEquals(listOf("/p1/groups/busy/topics" to "20"), requests.map { it.url.encodedPath to it.url.parameters["offset"] })
        assertNull(second.next)
    }

    @Test
    fun `我加入的小组：一个小组取帖失败时其余小组照常出帖，失败的那一路下一页再试`() = runTest {
        val repository = repository(
            loggedIn = true,
            responses = mapOf("/p1/groups/a/topics" to page(2, ownTopic(21, updatedAt = 900), ownTopic(22, updatedAt = 100))),
            joined = listOf(summary("bad"), summary("a")),
            failing = setOf("/p1/groups/bad/topics"),
        )

        val first = repository.feed(scope = null)

        assertEquals(listOf("group:21", "group:22"), first.items.map { it.key })
        assertEquals(1, first.failedRuns)
        requests.clear()
        // 只剩失败的那一路、仍取不到时，这一页就是失败
        assertFailsWith<FailureException> { repository.feed(scope = first.scope, cursor = first.next!!) }
        assertEquals(listOf("/p1/groups/bad/topics"), requests.map { it.url.encodedPath })
    }
}
