package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.data.web.TestWebSite
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.network.TokenProvider
import io.github.touhougo.chogumi.core.network.TokenRefresher
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.createHttpClient
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class UserRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private class FakeUserDao : UserDao {
        val store = mutableMapOf<Int, UserEntity>()
        override suspend fun get(id: Int): UserEntity? = store[id]
        override suspend fun getByUsername(username: String): UserEntity? =
            store.values.find { it.username == username }
        override suspend fun upsert(user: UserEntity) { store[user.id] = user }
    }

    private val meJson =
        """{"id":42,"username":"fixture_user","nickname":"Fixture User","user_group":10,"avatar":{"large":"l","medium":"m","small":"s"},"sign":"hi"}"""

    /** p1 资料：形状取自本机实测的 /p1/users/{username} 响应。 */
    private val p1UserJson = """
        {"id":42,"username":"fixture_user","nickname":"Fixture User","avatar":{"large":"l","medium":"m","small":"s"},
         "group":10,"joinedAt":1500000000,"sign":"hi","site":"https://bgm.tv/user/fixture_user","location":"",
         "bio":"","networkServices":[],"homepage":{"left":[],"right":[]},
         "stats":{"subject":{"2":{"1":4,"2":0,"3":5,"4":0,"5":0}},"mono":{"character":2,"person":0},
         "blog":0,"friend":0,"group":1,"index":{"create":0,"collect":0}},"isFriend":false}
    """.trimIndent()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterTest
    fun tearDown() { io.github.touhougo.chogumi.core.data.repository.cancelAndAwait(listOf(scope)) }

    private fun newStore(): TokenStore {
        return testTokenStore(scope = scope)
    }

    private fun newClient(engine: MockEngine): HttpClient = resources.apiClient(engine)

    /**
     * 构造**已登录**会话：有 token、有 userId、有 username。
     *
     * 只写 userId 不写 token 是"已登录却无凭据"的矛盾状态：它让 [TokenStore.tokenFor] 的兜底断言成为唯一防线，
     * 掩盖"读路径根本不取 token"这一类缺陷。
     */
    private suspend fun TokenStore.loginAs(userId: Int = 42, username: String = "fixture_user", token: String = "real_token") {
        val pending = beginSession(token)
        completeSession(pending, userId, username)
    }

    @Test
    fun `getMe 网络成功写入缓存`() = runTest {
        val engine = MockEngine {
            respond(
                content = meJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = newClient(engine)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val dao = FakeUserDao()
        val repo = testUserRepository(resources, UserApi(client), tokenStore, dao)
        val results = mutableListOf<Load<*>>()
        repo.getMe().collect { results.add(it) }
        assertEquals(2, results.size)
        assertTrue(results[1] is Load.Ready && !(results[1] as Load.Ready<*>).fromCache)
        assertEquals(42, dao.store[42]?.id)
    }

    @Test
    fun `个人资料读过的账号时区供网页复用`() = runTest {
        val meCalls = java.util.concurrent.atomic.AtomicInteger()
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/v0/me") meCalls.incrementAndGet()
            respond(
                content = meJson.dropLast(1) + ""","time_offset":-5}""",
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val tokenStore = newStore()
        tokenStore.loginAs()
        val api = UserApi(newClient(engine))
        val accountMe = AccountMe(api, tokenStore)
        val repo = testUserRepository(resources, api, tokenStore, FakeUserDao(), accountMe = accountMe)

        repo.getMe().collect {}
        assertEquals(java.time.ZoneOffset.ofHours(-5), accountMe.webZone(tokenStore.session()))
        assertEquals(1, meCalls.get())
    }

    /**
     * `/v0/me` 是需要鉴权的端点，读路径**必须**携带该会话的 token。
     *
     * 生产客户端 `loadTokens { null }` 是刻意的（防串号），不 `pinSessionToken` 的请求没有任何一方补头 →
     * 请求不带令牌 → 服务端 401 `need Login` → 已登录、本地 token 完全有效的用户看到「登录状态已失效」。
     *
     * 本用例必须跑在**生产客户端** [createHttpClient] 上：裸 HttpClient 没有 Auth 插件，
     * 观察不到"插件丢弃/覆盖头部"这一类问题（参见 SessionRequestTest 的同类教训）。
     * 假服务端像 bgm.tv 一样挑食：Authorization 不对就 401 need Login。
     */
    @Test
    fun `getMe 已登录时必须携带该会话的 token`() = runTest {
        val seen = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            seen.record(request)
            // 两个 host 都要带同一个 token：v0 取私密资料，p1 取统计与公开字段
            val ok = auth == "Bearer real_token"
            respond(
                content = if (request.url.host == "next.bgm.tv") p1UserJson else meJson,
                status = if (ok) HttpStatusCode.OK else HttpStatusCode.Unauthorized,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = createHttpClient(
            tokenProvider = object : TokenProvider {
                override suspend fun accessToken(): String? = null
            },
            tokenRefresher = object : TokenRefresher {
                override suspend fun refresh(rejectedAccessToken: String?): Boolean = false
            },
            engine = engine,
            appVersion = "test",
            customizers = emptyList(),
        ).also { resources.own(it) }
        val tokenStore = newStore()
        tokenStore.loginAs()
        val repo = testUserRepository(resources, UserApi(client), tokenStore, FakeUserDao())

        val results = mutableListOf<Load<*>>()
        repo.getMe().collect { results.add(it) }

        assertTrue(seen.isNotEmpty(), "读路径必须真的发出请求")
        assertTrue(
            seen.all { it.headers[HttpHeaders.Authorization] == "Bearer real_token" },
            "/v0/me 与 /p1/users 都必须带上当前会话的 token，否则服务端 401，" +
                "界面显示「登录状态已失效」。实际发出：$seen",
        )
        assertTrue(results.last() is Load.Ready<*>, "已登录用户拉取自己的资料应当成功，实际：${results.last()}")
    }

    @Test
    fun `getMe 过期缓存先展示 随后网络失败仍上报且缓存不变`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            requests.record(request)
            respond(content = "down", status = HttpStatusCode.ServiceUnavailable)
        }
        val client = newClient(engine)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val dao = FakeUserDao()
        val saved = UserEntity(id = 42, username = "fixture_user", nickname = "Fixture User", updatedAt = 1L)
        dao.upsert(saved)
        val repo = testUserRepository(resources, UserApi(client), tokenStore, dao, clock = TestClock(CacheTtl.USER_PROFILE + 1L))
        val results = mutableListOf<Load<UserBo>>()
        repo.getMe().collect { results.add(it) }
        assertEquals(
            listOf(Load.Ready(UserBo(42, "fixture_user", "Fixture User"), fromCache = true), Load.Failed(Failure.Http(503))),
            results,
        )
        assertEquals(1, requests.count { it.url.encodedPath == "/v0/me" }, "必须实际刷新，不能命中新鲜缓存就结束")
        assertEquals(saved, dao.store[42])
    }

    @Test
    fun `未登录时报错`() = runTest {
        val engine = MockEngine { respond(content = "{}", status = HttpStatusCode.OK) }
        val client = newClient(engine)
        val repo = testUserRepository(resources, UserApi(client), newStore(), FakeUserDao())
        val result = repo.getMe().first()
        assertTrue(result is Load.Failed)
    }


    @Test
    fun `getMe 时间戳为 0 的存量行走一次后台刷新后自愈并获得真实时间戳`() = runTest {
        val engine = MockEngine {
            respond(
                content = meJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = newClient(engine)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val dao = FakeUserDao()
        dao.upsert(UserEntity(id = 42, username = "fixture_user", nickname = "Fixture User", updatedAt = 0L))
        val clock = TestClock(time = 80_000L)
        val repo = testUserRepository(resources, UserApi(client), tokenStore, dao, clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getMe().collect { results.add(it) }

        assertEquals(2, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertTrue(results[1] is Load.Ready && !(results[1] as Load.Ready<*>).fromCache)
        assertEquals(80_000L, dao.store[42]?.updatedAt)
    }

    @Test
    fun `getMe 新鲜缓存直接直出不打网络`() = runTest {
        val networkCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val engine = MockEngine {
            networkCalled.set(true)
            respond(content = meJson, status = HttpStatusCode.OK, headers = headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = newClient(engine)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val dao = FakeUserDao()
        val clock = TestClock(time = 10_000_000L)
        // 5 分钟前写入，TTL 为 10 分钟
        dao.upsert(UserEntity(id = 42, username = "fixture_user", nickname = "Fixture User", updatedAt = 10_000_000L - 5 * 60 * 1000L))
        val repo = testUserRepository(resources, UserApi(client), tokenStore, dao, clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getMe().collect { results.add(it) }

        assertEquals(1, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertEquals(false, networkCalled.get())
    }

    @Test
    fun `getUser 时间戳为 0 的存量行走一次后台刷新后获得真实时间戳`() = runTest {
        val engine = MockEngine {
            respond(
                content = meJson,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = newClient(engine)
        val tokenStore = newStore()
        val dao = FakeUserDao()
        dao.upsert(UserEntity(id = 42, username = "fixture_user", nickname = "Fixture User", updatedAt = 0L))
        val clock = TestClock(time = 90_000L)
        val repo = testUserRepository(resources, UserApi(client), tokenStore, dao, clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getUser("fixture_user").collect { results.add(it) }

        assertEquals(2, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertTrue(results[1] is Load.Ready && !(results[1] as Load.Ready<*>).fromCache)
        assertEquals(90_000L, dao.store[42]?.updatedAt)
    }

    @Test
    fun `资料里的目录数与好友数取列表的total_不用资料stats`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            requests.record(request)
            val content = when (request.url.encodedPath) {
                // 实测的样子：建了 3 个目录，资料 stats 仍是 create 0；取消一个好友后列表 total 是 5，stats 还是 6
                "/p1/users/fixture_user" -> p1UserJson
                    .replace(""""index":{"create":0,"collect":0}""", """"index":{"create":0,"collect":7}""")
                    .replace(""""friend":0""", """"friend":6""")
                "/p1/users/fixture_user/indexes" -> """{"data":[],"total":3}"""
                "/p1/users/fixture_user/collections/indexes" -> """{"data":[],"total":7}"""
                "/p1/users/fixture_user/friends" -> """{"data":[],"total":5}"""
                else -> meJson
            }
            respond(content, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val repo = testUserRepository(resources, UserApi(newClient(engine)), newStore(), FakeUserDao())

        val user = (repo.getUser("fixture_user").first { it is Load.Ready && !it.fromCache } as Load.Ready).value

        assertEquals(3, user.indexCreateCount)
        assertEquals(7, user.indexCollectCount)
        assertEquals(5, user.friendCount)
        assertEquals(
            setOf("/p1/users/fixture_user/indexes", "/p1/users/fixture_user/collections/indexes", "/p1/users/fixture_user/friends"),
            requests.filter { it.url.parameters["limit"] == "1" }.map { it.url.encodedPath }.toSet(),
            "目录两个列表、好友列表各取 1 条，只为 total",
        )
    }

    @Test
    fun `getUser 请求途中登出 结果作废 不写进缓存`() = runTest {
        val tokenStore = newStore()
        tokenStore.loginAs()
        val engine = MockEngine {
            tokenStore.clear()
            respond(content = meJson, status = HttpStatusCode.OK, headers = headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val dao = FakeUserDao()
        val repo = testUserRepository(resources, UserApi(newClient(engine)), tokenStore, dao, clock = TestClock(time = 90_000L))

        val results = mutableListOf<Load<*>>()
        repo.getUser("fixture_user").collect { results.add(it) }

        assertTrue(results.last() is Load.Failed, "跨过换号的结果不能当成新账号视角的资料显示")
        assertEquals(null, dao.store[42])
    }

    @Test
    fun `getUser 新鲜缓存直接直出不打网络`() = runTest {
        val networkCalled = java.util.concurrent.atomic.AtomicBoolean(false)
        val engine = MockEngine {
            networkCalled.set(true)
            respond(content = meJson, status = HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = newClient(engine)
        val tokenStore = newStore()
        val dao = FakeUserDao()
        val clock = TestClock(time = 10_000_000L)
        // 5 分钟前写入，TTL 为 10 分钟
        dao.upsert(UserEntity(id = 42, username = "fixture_user", nickname = "Fixture User", updatedAt = 10_000_000L - 5 * 60 * 1000L))
        val repo = testUserRepository(resources, UserApi(client), tokenStore, dao, clock = clock)

        val results = mutableListOf<Load<*>>()
        repo.getUser("fixture_user").collect { results.add(it) }

        assertEquals(1, results.size)
        assertTrue(results[0] is Load.Ready && (results[0] as Load.Ready<*>).fromCache)
        assertEquals(false, networkCalled.get())
    }

    /**
     * 解除好友的接口恒 5xx 的服务端：对方（id 99）的 p1 资料上 `isFriend` 恒为 true（服务端一小时的好友缓存，网页解除不清它）；
     * 自己（fixture_user）的好友列表直接查库，网页那边做没做成看 [stillFriend]。
     */
    private fun unfriendServer(duringRemove: suspend () -> Unit = {}, duringTarget: suspend () -> Unit = {}, stillFriend: () -> Boolean) = resources.apiClient(MockEngine { request ->
        when {
            request.url.encodedPath == "/p1/friends/bob" -> {
                duringRemove()
                respond("", HttpStatusCode.InternalServerError)
            }
            request.url.encodedPath == "/p1/users/bob" -> {
                duringTarget()
                respond(
                    content = p1UserJson.replace(""""id":42""", """"id":99""").replace(""""isFriend":false""", """"isFriend":true"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
            request.url.encodedPath == "/p1/users/fixture_user/friends" -> respond(
                content = if (stillFriend()) {
                    """{"data":[{"id":99,"username":"bob","nickname":"Bob","avatar":{"large":"","medium":"","small":""},
                        "group":10,"joinedAt":0,"sign":"","isFriend":true}],"total":1}"""
                } else {
                    """{"data":[],"total":0}"""
                },
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    /** 解除好友的接口恒 5xx：改用网页会话按对方的用户 id 解除，回读自己的好友列表确认对方已不在（对方资料上的 isFriend 是旧缓存）。 */
    @Test
    fun `解除好友遇服务端5xx时用网页会话解除_回读确认`() = runTest {
        val site = TestWebSite(userId = 42)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val repo = testUserRepository(resources, UserApi(unfriendServer { site.opened.isEmpty() }), tokenStore, FakeUserDao(), webSessions = webSessions)

        assertEquals(Outcome.Success(Unit), repo.removeFriend("bob"))
        assertEquals(listOf<Pair<String, String?>>("https://bgm.tv/disconnect/99?gh=0a1b2c3d" to "chii_auth=c00k1e"), site.opened)
    }

    @Test
    fun `网页解除做成后好友名单当场去掉对方`() = runTest {
        val site = TestWebSite(userId = 42)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val api = UserApi(unfriendServer { site.opened.isEmpty() })
        val friends = testFriendsRepository(resources, api, tokenStore)
        friends.users()
        val repo = testUserRepository(resources, api, tokenStore, FakeUserDao(), webSessions = webSessions, friends = friends)

        assertEquals(Outcome.Success(Unit), repo.removeFriend("bob"))
        assertEquals(Friends.NONE, friends.changes().first())
    }

    @Test
    fun `加好友做成后好友名单当场带上对方`() = runTest {
        val followed = AtomicBoolean(false)
        val api = UserApi(newClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/p1/friends/bob" -> respond("{}", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString())).also { followed.set(true) }
                "/p1/users/fixture_user/friends" -> respond(
                    content = if (followed.get()) {
                        """{"data":[{"id":99,"username":"bob","nickname":"Bob","avatar":{"large":"","medium":"","small":""},
                            "group":10,"joinedAt":0,"sign":"","isFriend":true}],"total":1}"""
                    } else {
                        """{"data":[],"total":0}"""
                    },
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }))
        val tokenStore = newStore()
        tokenStore.loginAs()
        val friends = testFriendsRepository(resources, api, tokenStore)
        friends.users()
        val repo = testUserRepository(resources, api, tokenStore, FakeUserDao(), friends = friends)

        assertEquals(Outcome.Success(Unit), repo.addFriend("bob"))
        assertEquals(Friends(setOf(99)), friends.changes().first())
    }

    @Test
    fun `网页解除后回读仍是好友_是去网页端解除`() = runTest {
        val site = TestWebSite(userId = 42)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val repo = testUserRepository(resources, UserApi(unfriendServer { true }), tokenStore, FakeUserDao(), webSessions = webSessions)

        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.UNFRIEND_UNAVAILABLE)), repo.removeFriend("bob"))
    }

    @Test
    fun `解除好友接口失败期间换号不使用新账号网页会话`() = runTest {
        val site = TestWebSite(userId = 42)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val client = unfriendServer(duringRemove = {
            tokenStore.loginAs(userId = 8, username = "bob", token = "other")
            site.userId = 8
            webSessions.save(tokenStore.session(), WebSession("chii_auth=other"))
        }) { false }
        val repo = testUserRepository(resources, UserApi(client), tokenStore, FakeUserDao(), webSessions = webSessions)

        assertEquals(Outcome.Failed(Failure.SessionChanged), repo.removeFriend("bob"))
        assertTrue(site.opened.isEmpty())
    }

    @Test
    fun `解除好友查询目标期间换号不打开解除链接`() = runTest {
        val site = TestWebSite(userId = 42)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val client = unfriendServer(duringTarget = {
            tokenStore.loginAs(userId = 8, username = "bob", token = "other")
        }) { false }
        val repo = testUserRepository(resources, UserApi(client), tokenStore, FakeUserDao(), webSessions = webSessions)

        assertEquals(Outcome.Failed(Failure.SessionChanged), repo.removeFriend("bob"))
        assertTrue(site.opened.isEmpty())
    }

    /**
     * 绝交接口恒 5xx 的服务端：对方 bob 的 id 是 99，回读的绝交名单里有没有对方看 [blocked]。
     */
    private fun blocklistServer(blocked: () -> Boolean) = resources.apiClient(MockEngine { request ->
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        when (request.url.encodedPath) {
            "/p1/blocklist/bob" -> respond("", HttpStatusCode.InternalServerError)
            "/p1/users/bob" -> respond(p1UserJson.replace(""""id":42""", """"id":99"""), HttpStatusCode.OK, json)
            "/p1/blocklist" -> respond(if (blocked()) """{"blocklist":[7,99]}""" else """{"blocklist":[7]}""", HttpStatusCode.OK, json)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })

    /** 绝交接口恒 5xx：改用网页会话提交隐私设置页的绝交表单（填对方的数字 id），回读绝交名单确认对方已在。 */
    @Test
    fun `绝交遇服务端5xx时用网页会话提交绝交表单_回读确认`() = runTest {
        val site = TestWebSite(userId = 42)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val repo = testUserRepository(resources, UserApi(blocklistServer { site.submitted.isNotEmpty() }), tokenStore, FakeUserDao(), webSessions = webSessions)

        assertEquals(Outcome.Success(Unit), repo.addUserToBlocklist("bob"))
        assertEquals(
            listOf("https://bgm.tv/settings/privacy" to "ignore_user=99&formhash=0a1b2c3d&submit_ignore=%E4%BF%9D%E5%AD%98"),
            site.submitted,
        )
    }

    @Test
    fun `是否已绝交读当前账号的绝交名单`() = runTest {
        val tokenStore = newStore()
        tokenStore.loginAs()
        val blocked = object : BlockedUsersSource {
            override suspend fun of(session: AccountSession) = BlockedUsers(setOf(99))
            override suspend fun record(session: AccountSession, ids: Collection<Int>) = Unit
        }
        val repo = testUserRepository(resources, UserApi(blocklistServer { false }), tokenStore, FakeUserDao(), blocked = blocked)

        assertEquals(Outcome.Success(true), repo.isBlocked(99))
        assertEquals(Outcome.Success(false), repo.isBlocked(7))
    }

    @Test
    fun `绝交做成后记下服务端回读的完整名单`() = runTest {
        val site = TestWebSite(userId = 42)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val recorded = mutableListOf<List<Int>>()
        val blocked = object : BlockedUsersSource {
            override suspend fun of(session: AccountSession) = BlockedUsers.NONE
            override suspend fun record(session: AccountSession, ids: Collection<Int>) { recorded += ids.toList() }
        }
        val api = UserApi(blocklistServer { site.submitted.isNotEmpty() })
        val repo = testUserRepository(resources, api, tokenStore, FakeUserDao(), webSessions = webSessions, blocked = blocked)

        assertEquals(Outcome.Success(Unit), repo.addUserToBlocklist("bob"))
        assertEquals(listOf(listOf(7, 99)), recorded)
    }

    @Test
    fun `网页绝交后回读名单里没有对方_是去网页端绝交`() = runTest {
        val site = TestWebSite(userId = 42)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val repo = testUserRepository(resources, UserApi(blocklistServer { false }), tokenStore, FakeUserDao(), webSessions = webSessions)

        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.BLOCK_UNAVAILABLE)), repo.addUserToBlocklist("bob"))
    }

    @Test
    fun `绝交没有网页会话时要账号密码登录_不查对方`() = runTest {
        val site = TestWebSite(userId = 42)
        val tokenStore = newStore()
        tokenStore.loginAs()
        val requests = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            requests.record(request)
            respond("", HttpStatusCode.InternalServerError)
        })
        val repo = testUserRepository(resources, UserApi(client), tokenStore, FakeUserDao(), webSessions = testWebSessionRepository(tokenStore, site.client))

        assertEquals(Outcome.Failed(Failure.Rejected(RejectReason.WEB_LOGIN_REQUIRED)), repo.addUserToBlocklist("bob"))
        assertEquals(listOf("/p1/blocklist/bob"), requests.map { it.url.encodedPath })
    }

    /** 取消绝交接口恒 5xx：改用网页会话点隐私设置页绝交名单上对方那一行的取消链接，回读名单确认对方已不在。 */
    @Test
    fun `取消绝交遇服务端5xx时点隐私设置页上对方那一行的取消链接_回读确认`() = runTest {
        val site = TestWebSite(userId = 42)
        site.pages["/settings/privacy"] = """<script>var CHOBITS_UID = 42;</script><a href="/logout/0a1b2c3d">登出</a>
            <table>
            <tr><td valign="top"><a href="/user/carol" class="avatar">Carol</a></td><td><p class="tip_j"><a href="/settings/privacy?ignore_reset=7&amp;gh=0a1b2c3d" class="tip_i rr">取消绝交</a></p></td></tr>
            <tr><td valign="top"><a href="/user/bob" class="avatar">Bob</a></td><td><p class="tip_j"><a href="/settings/privacy?ignore_reset=99&amp;gh=0a1b2c3d" class="tip_i rr">取消绝交</a></p></td></tr>
            </table>"""
        val tokenStore = newStore()
        tokenStore.loginAs()
        val webSessions = testWebSessionRepository(tokenStore, site.client)
        webSessions.save(tokenStore.session(), WebSession("chii_auth=c00k1e"))
        val repo = testUserRepository(resources, UserApi(blocklistServer { site.opened.isEmpty() }), tokenStore, FakeUserDao(), webSessions = webSessions)

        assertEquals(Outcome.Success(Unit), repo.removeUserFromBlocklist("bob"))
        assertEquals(listOf<Pair<String, String?>>("https://bgm.tv/settings/privacy?ignore_reset=99&gh=0a1b2c3d" to "chii_auth=c00k1e"), site.opened)
        assertEquals(listOf<String?>("https://bgm.tv/settings/privacy"), site.referers)
    }

    @Test
    fun `已登录时日志详情带令牌请求 与同页的作品和留言一致`() = runTest {
        val tokenStore = newStore()
        tokenStore.loginAs()
        val seen = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            seen.record(request)
            respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        val repo = testUserRepository(resources, UserApi(client), tokenStore, FakeUserDao())

        repo.getBlogDetails(1)

        assertEquals(listOf<String?>("Bearer real_token"), seen.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test
    fun `社交写响应期间换号不返回成功`() = runTest {
        val tokenStore = newStore()
        tokenStore.loginAs()
        val seen = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            seen.record(request)
            tokenStore.loginAs(userId = 8, username = "bob", token = "other")
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        })
        val repo = testUserRepository(resources, UserApi(client), tokenStore, FakeUserDao())

        assertEquals(Outcome.Failed(Failure.SessionChanged), repo.addFriend("bob"))
        assertEquals(listOf<String?>("Bearer real_token"), seen.map { it.headers[HttpHeaders.Authorization] })
    }

}
