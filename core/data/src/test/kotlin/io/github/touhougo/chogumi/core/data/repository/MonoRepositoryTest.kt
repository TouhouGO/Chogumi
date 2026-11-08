package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.web.TestWebSite
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.network.api.MonoApi
import io.github.touhougo.chogumi.core.network.api.MonoResource
import io.github.touhougo.chogumi.core.network.model.p1.AvatarDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterRelationDto
import io.github.touhougo.chogumi.core.network.model.p1.CharacterSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.CommentDto
import io.github.touhougo.chogumi.core.network.model.p1.InfoboxItemDto
import io.github.touhougo.chogumi.core.network.model.p1.InfoboxValueDto
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.P1UserDto
import io.github.touhougo.chogumi.core.network.model.p1.PagedDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonRelationDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonRelationTypeDto
import io.github.touhougo.chogumi.core.network.model.p1.PersonWorkDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimCharacterDto
import io.github.touhougo.chogumi.core.network.model.p1.SlimIndexDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectStaffPositionDto
import io.github.touhougo.chogumi.core.network.model.p1.SubjectStaffPositionTypeDto
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MonoRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    /**
     * 角色 / 人物接口的假服务端：真 [MonoApi] 接在 MockEngine 上，按路径回配置好的响应，序列化、鉴权头与错误映射都走生产配置。
     */
    private class FakeMonoServer(private val resources: TestResources) {
        @Volatile var lastCapturedToken: String? = null
        /** 最后一次收藏写入：（资源, id, 收藏与否）。 */
        @Volatile var lastCollected: Triple<MonoResource, Int, Boolean>? = null

        // 可配置的返回值
        /** 角色出演按请求的 offset 回一页；记下每次请求的（limit, offset）。 */
        var characterCastsPage: (offset: Int) -> PagedDto<CharacterSubjectDto> = { PagedDto(data = emptyList(), total = 0) }
        val characterCastsRequests: List<Pair<Int, Int>>
            get() = castsRequests("characters")
        /** 取角色出演时先跑它（模拟请求途中发生的事）。 */
        var duringCharacterCasts: suspend (offset: Int) -> Unit = {}
        /** 记录请求前的接收闸门，用于控制并发后页的接收顺序。 */
        var beforeRecord: suspend (HttpRequestData) -> Unit = {}
        var personWorksResponse: PagedDto<PersonWorkDto> = PagedDto(data = emptyList(), total = 0)
        val personWorksCalls = AtomicInteger()
        /** 人物详情被请求的次数。 */
        val personDetailsCalls = AtomicInteger()
        val personCastsRequests: List<Pair<Int, Int>>
            get() = castsRequests("persons")
        var personCastsResponse: PagedDto<PersonCharacterDto> = PagedDto(data = emptyList(), total = 0)
        var characterRelationsResponse: PagedDto<CharacterRelationDto> = PagedDto(data = emptyList(), total = 0)
        var personRelationsResponse: PagedDto<PersonRelationDto> = PagedDto(data = emptyList(), total = 0)
        var characterIndexesResponse: PagedDto<SlimIndexDto> = PagedDto(data = emptyList(), total = 0)
        var personIndexesResponse: PagedDto<SlimIndexDto> = PagedDto(data = emptyList(), total = 0)
        var characterCommentsResponse: List<CommentDto> = emptyList()
        var personCommentsResponse: List<CommentDto> = emptyList()

        /** 带令牌读角色时的收藏时间（null = 没收藏）。 */
        var characterCollectedAt: Long? = 1700000000L

        /** 置非空：收藏 / 取消收藏的请求以它的状态码失败。 */
        var collectFailure: Failure.Http? = null
        var duringCollect: suspend () -> Unit = {}

        private val requests = ConcurrentRequestRecorder()
        val api = MonoApi(resources.apiClient { request -> respondTo(request) })

        private fun castsRequests(resource: String): List<Pair<Int, Int>> = requests
            .filter { it.url.encodedPath.startsWith("/p1/$resource/") && it.url.encodedPath.endsWith("/casts") }
            .map { it.url.parameters["limit"]!!.toInt() to it.url.parameters["offset"]!!.toInt() }

        private suspend fun MockRequestHandleScope.respondTo(request: HttpRequestData): HttpResponseData {
            beforeRecord(request)
            requests.record(request)
            lastCapturedToken = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
            val segments = request.url.encodedPath.removePrefix("/p1/").split('/')
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            if (segments[0] == "collections") {
                val resource = resourceOf(segments[1])
                lastCollected = Triple(resource, segments[2].toInt(), request.method == HttpMethod.Put)
                duringCollect()
                return collectFailure?.let { respondError(HttpStatusCode.fromValue(it.status)) } ?: respondOk()
            }
            val resource = resourceOf(segments[0])
            val id = segments[1].toInt()
            val isCharacter = resource == MonoResource.CHARACTERS
            return when (segments.getOrNull(2)) {
                null -> if (isCharacter) {
                    json(character(id, collected = lastCapturedToken != null))
                } else {
                    personDetailsCalls.incrementAndGet()
                    json(person(id))
                }
                "casts" -> if (isCharacter) {
                    duringCharacterCasts(offset)
                    json(characterCastsPage(offset))
                } else {
                    json(personCastsResponse)
                }
                "works" -> {
                    personWorksCalls.incrementAndGet()
                    json(personWorksResponse)
                }
                "relations" -> if (isCharacter) json(characterRelationsResponse) else json(personRelationsResponse)
                "indexes" -> json(if (isCharacter) characterIndexesResponse else personIndexesResponse)
                "comments" -> json(if (isCharacter) characterCommentsResponse else personCommentsResponse)
                else -> error("没配置的路径：${request.url.encodedPath}")
            }
        }

        private fun resourceOf(segment: String) = if (segment == "characters") MonoResource.CHARACTERS else MonoResource.PERSONS

        private fun character(id: Int, collected: Boolean) = CharacterDto(
            id = id,
            name = "Anya",
            nameCN = "阿尼亚",
            role = 1,
            infobox = listOf(InfoboxItemDto(key = "性别", values = listOf(InfoboxValueDto(v = "女")))),
            info = "",
            summary = "角色简介",
            comment = 10,
            collects = 20,
            lock = false,
            redirect = 0,
            nsfw = false,
            collectedAt = if (collected) characterCollectedAt else null,
        )

        private fun person(id: Int) = PersonDto(
            id = id,
            name = "Atsumi",
            nameCN = "种崎敦美",
            type = 1,
            infobox = emptyList(),
            info = "",
            career = listOf("seiyu"),
            summary = "人物简介",
            comment = 5,
            collects = 15,
            lock = false,
            redirect = 0,
            nsfw = false,
            collectedAt = null,
        )

        private inline fun <reified T> MockRequestHandleScope.json(body: T): HttpResponseData = respond(
            content = WireJson.encodeToString(body),
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )

        private companion object {
            val WireJson = Json { encodeDefaults = true }
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var tokenStore: TokenStore
    private lateinit var server: FakeMonoServer
    private lateinit var repository: MonoRepository

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        tokenStore = testTokenStore(scope = scope)
        server = FakeMonoServer(resources)
        repository = testMonoRepository(resources, server.api, tokenStore, testNsfwFilter(resources))
    }

    @AfterTest
    fun tearDown() {
        io.github.touhougo.chogumi.core.data.repository.cancelAndAwait(listOf(scope))
    }

    private fun cast(id: Int) = CharacterSubjectDto(
        subject = P1SlimSubjectDto(id = id, name = "Subject $id", nameCN = "作品 $id", type = 2),
        casts = emptyList(),
        type = 1,
    )

    @Test
    fun `全部作品逐页取齐：每页 100，回不满一页也按请求跨度推进，直到末页`() = runTest {
        val laterReceived = kotlinx.coroutines.CompletableDeferred<Unit>()
        server.beforeRecord = { request ->
            if (request.url.parameters["offset"] == "100") laterReceived.await()
        }
        server.duringCharacterCasts = { offset -> if (offset == 200) laterReceived.complete(Unit) }
        // 服务端 total=250：第 1 页只回 60 条（NSFW 等原因），游标仍按 100 推进
        server.characterCastsPage = { offset ->
            when (offset) {
                0 -> PagedDto(data = (1..60).map(::cast), total = 250)
                100 -> PagedDto(data = (101..200).map(::cast), total = 250)
                200 -> PagedDto(data = (201..250).map(::cast), total = 250)
                else -> error("不该请求 offset=$offset")
            }
        }
        val works = repository.getAllWorks(MonoType.CHARACTER, 1)
        assertEquals(210, works.size)
        val requests = server.characterCastsRequests
        assertEquals(3, requests.size)
        assertEquals(100 to 0, requests.first(), "只有第一页先取，后页并发")
        assertEquals(setOf(100 to 100, 100 to 200), requests.drop(1).toSet())
        assertEquals((1..60).toList() + (101..250).toList(), works.map { it.id }, "响应逆序到达仍按页序合并")
        assertEquals(CastRole.MAIN, works.first().role)
    }

    @Test
    fun `全部作品留在内存里：再取不发请求，refresh 时重取`() = runTest {
        server.characterCastsPage = { PagedDto(data = listOf(cast(1)), total = 1) }
        repository.getAllWorks(MonoType.CHARACTER, 1)
        repository.getAllWorks(MonoType.CHARACTER, 1)
        assertEquals(1, server.characterCastsRequests.size, "同一实体第二次读缓存")

        repository.getAllWorks(MonoType.CHARACTER, 1, refresh = true)
        assertEquals(2, server.characterCastsRequests.size, "下拉刷新重取")

        repository.getAllWorks(MonoType.CHARACTER, 2)
        assertEquals(3, server.characterCastsRequests.size, "不同实体各自缓存")
    }

    @Test
    fun `游客取过的全部作品_登录后再取发新请求并带上令牌`() = runTest {
        server.characterCastsPage = { PagedDto(data = listOf(cast(1)), total = 1) }
        repository.getAllWorks(MonoType.CHARACTER, 1)
        tokenStore.completeSession(tokenStore.beginSession("valid_bearer_token"), userId = 7, username = "fixture_user")

        repository.getAllWorks(MonoType.CHARACTER, 1)

        assertEquals(2, server.characterCastsRequests.size, "游客的列表缺账号可见的 NSFW 条目，不能给登录后用")
        assertEquals("valid_bearer_token", server.lastCapturedToken)
    }

    @Test
    fun `全部作品只留最近几个实体 更早看过的再打开时重取`() = runTest {
        server.characterCastsPage = { PagedDto(data = listOf(cast(1)), total = 1) }
        for (id in 1..WORKS_CACHE_SIZE + 1) repository.getAllWorks(MonoType.CHARACTER, id)
        repository.getAllWorks(MonoType.CHARACTER, WORKS_CACHE_SIZE + 1)
        assertEquals(WORKS_CACHE_SIZE + 1, server.characterCastsRequests.size, "最近看过的还在缓存里")

        repository.getAllWorks(MonoType.CHARACTER, 1)
        assertEquals(WORKS_CACHE_SIZE + 2, server.characterCastsRequests.size, "最早看过的已经让出位置")
    }

    @Test
    fun `人物图留在内存里 同一人物再取不发请求 没有图也记下`() = runTest {
        assertNull(repository.getPersonImage(1))
        assertNull(repository.getPersonImage(1))
        assertEquals(1, server.personDetailsCalls.get(), "同一人物第二次读缓存，没有图的结果也算")

        repository.getPersonImage(2)
        assertEquals(2, server.personDetailsCalls.get(), "不同人物各自取")
    }

    @Test
    fun `未登录时执行收藏操作是需要登录且不发请求`() = runTest {
        val notLoggedIn = Outcome.Failed(Failure.NotLoggedIn)
        assertEquals(notLoggedIn, repository.setCollected(MonoType.CHARACTER, collected = true, id = 1))
        assertEquals(notLoggedIn, repository.setCollected(MonoType.CHARACTER, collected = false, id = 1))
        assertEquals(notLoggedIn, repository.setCollected(MonoType.PERSON, collected = true, id = 2))
        assertEquals(notLoggedIn, repository.setCollected(MonoType.PERSON, collected = false, id = 2))
        assertNull(server.lastCollected)
    }

    /** 登录为 7 号账号；[site] 非空时同时有这个网页的会话（账号密码登录）。 */
    private suspend fun signIn(site: TestWebSite? = null): MonoRepository {
        val pending = tokenStore.beginSession("valid_bearer_token")
        val session = tokenStore.completeSession(pending, userId = 7, username = "fixture_user")
        if (site == null) return repository
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(session, WebSession("chii_auth=c00k1e"))
        return testMonoRepository(resources, server.api, tokenStore, testNsfwFilter(resources), webSessions)
    }

    /** 取消收藏的接口在服务端恒 5xx：改用网页会话打开取消收藏的链接，回读接口确认已取消。 */
    @Test
    fun `取消收藏遇服务端5xx时用网页会话取消_回读确认`() = runTest {
        val site = TestWebSite(userId = 7)
        val repo = signIn(site)
        server.collectFailure = Failure.Http(500)
        server.characterCollectedAt = null

        assertEquals(Outcome.Success(Unit), repo.setCollected(MonoType.CHARACTER, collected = false, id = 101))
        assertEquals(listOf<Pair<String, String?>>("https://bgm.tv/character/101/erase_collect?gh=0a1b2c3d" to "chii_auth=c00k1e"), site.opened)
    }

    @Test
    fun `取消收藏接口失败期间换号不使用新账号网页会话`() = runTest {
        val site = TestWebSite(userId = 7)
        val repo = signIn(site)
        server.collectFailure = Failure.Http(500)
        server.characterCollectedAt = null
        server.duringCollect = {
            val next = tokenStore.completeSession(tokenStore.beginSession("other"), userId = 8, username = "bob")
            site.userId = 8
            testWebSessionRepository(tokenStore, site.client).save(next, WebSession("chii_auth=other"))
        }

        assertEquals(Outcome.Failed(Failure.SessionChanged), repo.setCollected(MonoType.CHARACTER, id = 101, collected = false))
        assertTrue(site.opened.isEmpty())
    }

    @Test
    fun `收藏响应期间换号不返回成功`() = runTest {
        signIn()
        server.duringCollect = { tokenStore.completeSession(tokenStore.beginSession("other"), userId = 8, username = "bob") }
        assertEquals(Outcome.Failed(Failure.SessionChanged), repository.setCollected(MonoType.CHARACTER, id = 101, collected = true))
    }

    @Test
    fun `网页取消后回读仍是收藏_是去网页端取消`() = runTest {
        val repo = signIn(TestWebSite(userId = 7))
        server.collectFailure = Failure.Http(500)

        val uncollect = repo.setCollected(MonoType.CHARACTER, collected = false, id = 101)
        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.MONO_UNCOLLECT_UNAVAILABLE)), uncollect)
    }

    /** 没有网页会话（浏览器授权或 Token 登录）：提示要账号密码登录，不开网页。收藏的 5xx 与别的失败原样。 */
    @Test
    fun `没有网页会话时取消收藏5xx是要账号密码登录_其余失败原样`() = runTest {
        signIn()
        server.collectFailure = Failure.Http(500)

        val uncollect = repository.setCollected(MonoType.CHARACTER, collected = false, id = 101)
        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)), uncollect)
        assertEquals(Outcome.Failed(Failure.Http(500)), repository.setCollected(MonoType.PERSON, collected = true, id = 202))

        server.collectFailure = Failure.Http(404)
        assertEquals(Outcome.Failed(Failure.Http(404)), repository.setCollected(MonoType.PERSON, collected = false, id = 202), "别的失败原样")
    }

    @Test
    fun `已登录时成功执行收藏增删`() = runTest {
        signIn()

        assertEquals(Outcome.Success(Unit), repository.setCollected(MonoType.CHARACTER, collected = true, id = 101))
        assertEquals(Triple(MonoResource.CHARACTERS, 101, true), server.lastCollected)
        assertEquals("valid_bearer_token", server.lastCapturedToken)

        repository.setCollected(MonoType.CHARACTER, collected = false, id = 101)
        assertEquals(Triple(MonoResource.CHARACTERS, 101, false), server.lastCollected)
        assertEquals("valid_bearer_token", server.lastCapturedToken)

        repository.setCollected(MonoType.PERSON, collected = true, id = 202)
        assertEquals(Triple(MonoResource.PERSONS, 202, true), server.lastCollected)
        assertEquals("valid_bearer_token", server.lastCapturedToken)

        repository.setCollected(MonoType.PERSON, collected = false, id = 202)
        assertEquals(Triple(MonoResource.PERSONS, 202, false), server.lastCollected)
        assertEquals("valid_bearer_token", server.lastCapturedToken)
    }

    @Test
    fun `拉取人物作品与关联列表的分页处理`() = runTest {
        val dummyWorks = (1..5).map { index ->
            PersonWorkDto(
                subject = P1SlimSubjectDto(id = index, name = "Work $index", nameCN = "作品 $index", type = 2),
                positions = listOf(
                    SubjectStaffPositionDto(
                        type = SubjectStaffPositionTypeDto(id = 1, en = "CV", cn = "声优", jp = "声優"),
                        summary = "",
                        appearEps = "",
                    ),
                ),
            )
        }
        server.personWorksResponse = PagedDto(data = dummyWorks, total = 5)
        val works = repository.getAllWorks(MonoType.PERSON, 2)
        assertEquals(5, works.size)
        assertEquals(listOf("声优"), works.first().positions)
        assertEquals(1, server.personWorksCalls.get(), "5 条一页取齐")

        server.characterRelationsResponse = PagedDto(
            data = listOf(
                CharacterRelationDto(
                    character = SlimCharacterDto(id = 1, name = "Anya", nameCN = "阿尼亚", role = 1, info = "", comment = 0, lock = false, nsfw = false),
                    relation = PersonRelationTypeDto(id = 1, cn = "女儿", desc = "Daughter"),
                    spoiler = false,
                    ended = false,
                    comment = "",
                ),
            ),
            total = 50,
        )
        val relationsResult = repository.getRelations(MonoType.CHARACTER, id = 1, limit = 30, offset = 0)
        assertEquals(1, relationsResult.items.size)
        assertEquals(50, relationsResult.total)
        assertEquals(30, relationsResult.nextOffset)
    }

    @Test
    fun `拉取角色与人物详情及吐槽箱评论树`() = runTest {
        tokenStore.beginSession("my_token")

        val charDetails = repository.getDetails(MonoType.CHARACTER, 1)
        assertEquals(1, charDetails.id)
        assertEquals("Anya", charDetails.name)
        assertTrue(charDetails.isCollected)
        assertEquals("my_token", server.lastCapturedToken)

        val user = P1UserDto(id = 10, username = "u10", nickname = "Nick", avatar = AvatarDto(large = "a.jpg"))
        server.characterCommentsResponse = listOf(
            CommentDto(
                id = 100L,
                mainID = 1L,
                creatorID = 10,
                relatedID = 0L,
                createdAt = 1700000000L,
                content = "精彩吐槽",
                state = 0,
                replies = emptyList(),
                user = user,
            ),
        )
        val comments = repository.getComments(MonoType.CHARACTER, 1)
        assertEquals(1, comments.size)
        assertEquals(100, comments[0].anchor.id)
        assertEquals("精彩吐槽", comments[0].anchor.content)
        assertEquals(CommentState.NORMAL, comments[0].anchor.state)
    }

    @Test
    fun `人物与角色各公开读接口在登录态下携带 token，未登录态不携带`() = runTest {
        // 1. 未登录
        repository.getDetails(MonoType.CHARACTER, 1)
        assertNull(server.lastCapturedToken)

        repository.getDetails(MonoType.PERSON, 1)
        assertNull(server.lastCapturedToken)

        repository.getAllWorks(MonoType.CHARACTER, 1)
        assertNull(server.lastCapturedToken)

        repository.getAllWorks(MonoType.PERSON, 1)
        assertNull(server.lastCapturedToken)

        repository.getPersonCharacters(1)
        assertNull(server.lastCapturedToken)

        repository.getRelations(MonoType.CHARACTER, 1)
        assertNull(server.lastCapturedToken)

        repository.getRelations(MonoType.PERSON, 1)
        assertNull(server.lastCapturedToken)

        repository.getIndexes(MonoType.CHARACTER, 1)
        assertNull(server.lastCapturedToken)

        repository.getIndexes(MonoType.PERSON, 1)
        assertNull(server.lastCapturedToken)

        repository.getComments(MonoType.CHARACTER, 1)
        assertNull(server.lastCapturedToken)

        repository.getComments(MonoType.PERSON, 1)
        assertNull(server.lastCapturedToken)

        // 2. 登录
        val session = tokenStore.beginSession("mono_token_123")
        tokenStore.completeSession(session, userId = 1, username = "mono_user")

        repository.getDetails(MonoType.CHARACTER, 1)
        assertEquals("mono_token_123", server.lastCapturedToken)

        repository.getDetails(MonoType.PERSON, 1)
        assertEquals("mono_token_123", server.lastCapturedToken)

        // 作品有内存缓存：换号后用 refresh 绕过缓存，验证请求确实带上 token
        repository.getAllWorks(MonoType.CHARACTER, 1, refresh = true)
        assertEquals("mono_token_123", server.lastCapturedToken)

        repository.getAllWorks(MonoType.PERSON, 1, refresh = true)
        assertEquals("mono_token_123", server.lastCapturedToken)

        repository.getPersonCharacters(1)
        assertEquals("mono_token_123", server.lastCapturedToken)

        repository.getRelations(MonoType.CHARACTER, 1)
        assertEquals("mono_token_123", server.lastCapturedToken)

        repository.getRelations(MonoType.PERSON, 1)
        assertEquals("mono_token_123", server.lastCapturedToken)

        repository.getIndexes(MonoType.CHARACTER, 1)
        assertEquals("mono_token_123", server.lastCapturedToken)

        repository.getIndexes(MonoType.PERSON, 1)
        assertEquals("mono_token_123", server.lastCapturedToken)

        repository.getComments(MonoType.CHARACTER, 1)
        assertEquals("mono_token_123", server.lastCapturedToken)

        repository.getComments(MonoType.PERSON, 1)
        assertEquals("mono_token_123", server.lastCapturedToken)
    }

    @Test
    fun `MonoRepository 读操作期间换号抛出 SessionChangedException`() = runTest {
        val sessionA = tokenStore.beginSession("token_a")
        tokenStore.completeSession(sessionA, userId = 1, username = "usera")

        var hookCalled = false
        server.duringCharacterCasts = {
            // 模拟调用期间换号
            hookCalled = true
            val sessionB = tokenStore.beginSession("token_b")
            tokenStore.completeSession(sessionB, userId = 2, username = "userb")
        }

        assertFailsWith<io.github.touhougo.chogumi.core.data.token.SessionChangedException> {
            repository.getAllWorks(MonoType.CHARACTER, 1)
        }
        assertTrue(hookCalled)
    }

    @Test
    fun `人物角色分页传递offset并按请求跨度推进游标`() = runTest {
        val character = SlimCharacterDto(
            id = 1, name = "角色", nameCN = "", role = 1, info = "",
            comment = 0, lock = false, nsfw = false,
        )
        server.personCastsResponse = PagedDto(
            data = listOf(PersonCharacterDto(character, emptyList())),
            total = 70,
        )
        val page = repository.getPersonCharacters(1, limit = 30, offset = 30)
        assertEquals(listOf(30 to 30), server.personCastsRequests)
        assertEquals(60, page.nextOffset)
        assertEquals(70, page.total)
        assertEquals(listOf(1), page.items.map { it.id })
        val last = repository.getPersonCharacters(1, limit = 30, offset = 60)
        assertNull(last.nextOffset)
    }

    @Test
    fun `人物角色首屏全被NSFW过滤时按原始页继续到下一页`() = runTest {
        val hidden = SlimCharacterDto(
            id = 1, name = "角色", nameCN = "", role = 1, info = "",
            comment = 0, lock = false, nsfw = true,
        )
        val repo = testMonoRepository(resources, server.api, tokenStore, testNsfwFilter(resources, showNsfw = false))
        server.personCastsResponse = PagedDto(
            data = (1..30).map { PersonCharacterDto(hidden.copy(id = it), emptyList()) },
            total = 31,
        )
        val first = repo.getPersonCharacters(1)
        assertTrue(first.items.isEmpty())
        assertEquals(30, first.nextOffset)
        assertEquals(31, first.total)
        server.personCastsResponse = PagedDto(
            data = listOf(PersonCharacterDto(hidden.copy(id = 31, nsfw = false), emptyList())),
            total = 31,
        )
        val last = repo.getPersonCharacters(1, offset = first.nextOffset!!)
        assertEquals(listOf(30 to 0, 30 to 30), server.personCastsRequests)
        assertEquals(listOf(31), last.items.map { it.id })
        assertNull(last.nextOffset)
    }

}
