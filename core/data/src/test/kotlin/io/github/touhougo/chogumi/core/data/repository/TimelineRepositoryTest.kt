package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.TimelineMode
import io.github.touhougo.chogumi.core.model.TimelineQuery
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.TimelineApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.model.p1.P1SlimSubjectDto
import io.github.touhougo.chogumi.core.network.model.p1.P1UserDto
import io.github.touhougo.chogumi.core.network.model.p1.ReactionDto
import io.github.touhougo.chogumi.core.network.model.p1.SimpleUserDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineMemoDto
import io.github.touhougo.chogumi.core.network.model.p1.TimelineSubjectDto
import io.github.touhougo.chogumi.core.network.model.v0.UserDto
import io.github.touhougo.chogumi.core.network.model.web.WebSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class TimelineRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private val json = Json { encodeDefaults = true }
    private val headers = headersOf("Content-Type", ContentType.Application.Json.toString())
    private fun page(vararg ids: Long) = json.encodeToString(ListSerializer(TimelineDto.serializer()),
        ids.map { TimelineDto(it, 42, cat = 5, type = 1) })

    @Test
    fun `时间线首载不亮刷新而用户下拉会亮`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val client = resources.apiClient(MockEngine {
            if (requests.incrementAndGet() == 1) {
                entered.complete(Unit)
                release.await()
            } else {
                secondEntered.complete(Unit)
                secondRelease.await()
            }
            respond(page(9), headers = headers)
        })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        val first = async { repo.refresh(query) }
        entered.await()
        assertFalse(repo.observe(query).value.isRefreshing)
        release.complete(Unit)
        first.await()

        val second = async { repo.refresh(query, userInitiated = true) }
        secondEntered.await()
        assertTrue(repo.observe(query).value.isRefreshing)
        secondRelease.complete(Unit)
        second.await()
        assertFalse(repo.observe(query).value.isRefreshing)
    }

    @Test
    fun `已加载的全站动态里绝交一个人_这个人的动态当场不显示且不重取_取消后回来`() = runTest {
        val requests = AtomicInteger()
        val client = resources.apiClient(MockEngine {
            requests.incrementAndGet()
            respond(
                json.encodeToString(ListSerializer(TimelineDto.serializer()), listOf(TimelineDto(9, 42, cat = 5, type = 1), TimelineDto(8, 43, cat = 5, type = 1))),
                headers = headers,
            )
        })
        val store = testTokenStore(scope = backgroundScope)
        val blocked = NoBlockedUsers()
        val repo = TimelineRepository(
            TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope,
            StandardTestDispatcher(testScheduler), blocked,
        )
        val query = TimelineQuery(TimelineMode.ALL)
        fun shown() = repo.observe(query).value.items.map { it.id }
        repo.refresh(query)
        assertEquals(listOf(9L, 8L), shown())

        blocked.list.value = BlockedUsers(setOf(43))
        testScheduler.runCurrent()
        assertEquals(listOf(9L), shown())
        assertEquals(1, requests.get(), "名单变了只重算已取到的动态，不重取")

        blocked.list.value = BlockedUsers.NONE
        testScheduler.runCurrent()
        assertEquals(listOf(9L, 8L), shown())
        assertEquals(1, requests.get())
    }

    @Test
    fun `共享feed按ID去重且重复游标终止`() = runTest {
        val until = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            until.record(request)
            respond(if (until.count() == 1) page(9, 8) else page(8), headers = headers)
        })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        assertSame(repo.observe(query), repo.observe(query))
        repo.refresh(query)
        repo.loadMore(query)
        repo.loadMore(query)
        assertEquals(listOf(9L, 8L), repo.observe(query).value.items.map { it.id })
        assertEquals(listOf(null, "8"), until.map { it.url.parameters["until"] })
        assertFalse(repo.observe(query).value.hasMore)
    }

    @Test
    fun `续取失败只记在页脚 已有内容不受影响`() = runTest {
        val requests = AtomicInteger()
        val client = resources.apiClient(MockEngine {
            if (requests.incrementAndGet() == 1) respond(page(9, 8), headers = headers)
            else respond("{}", HttpStatusCode.InternalServerError, headers)
        })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        repo.refresh(query)
        repo.loadMore(query)
        val state = repo.observe(query).value
        assertEquals(listOf(9L, 8L), state.items.map { it.id })
        assertEquals(Failure.Http(500), state.loadMoreError)
        assertNull(state.error, "有内容时不是整页失败")
    }

    @Test
    fun `已有内容时刷新失败 内容留着 原因交给调用方提示`() = runTest {
        val requests = AtomicInteger()
        val client = resources.apiClient(MockEngine {
            if (requests.incrementAndGet() == 1) respond(page(9), headers = headers)
            else respond("{}", HttpStatusCode.InternalServerError, headers)
        })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        assertNull(repo.refresh(query))
        val unshown = repo.refresh(query, userInitiated = true)
        assertEquals(Failure.Http(500), unshown)
        val state = repo.observe(query).value
        assertEquals(listOf(9L), state.items.map { it.id })
        assertNull(state.error)
        assertNull(state.loadMoreError)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun `续取按页码带上一页最后一条的游标 失败后重试带同一个游标`() = runTest {
        val until = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            until.record(request)
            when (until.count()) {
                1 -> respond(page(9, 8), headers = headers)
                2 -> respond("{}", HttpStatusCode.InternalServerError, headers)
                3 -> respond(page(7, 6), headers = headers)
                else -> respond(page(5), headers = headers)
            }
        })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        repo.refresh(query)
        repo.loadMore(query)
        assertEquals(Failure.Http(500), repo.observe(query).value.loadMoreError)
        repo.loadMore(query)
        assertNull(repo.observe(query).value.loadMoreError)
        repo.loadMore(query)
        assertEquals(listOf(null, "8", "8", "6"), until.map { it.url.parameters["until"] })
        assertEquals(listOf(9L, 8L, 7L, 6L, 5L), repo.observe(query).value.items.map { it.id })
    }

    /**
     * 后台重拉在途时页脚要的续取：不带旧游标去取（旧游标那页晚到会接在新第一页后面，游标表也跟着记成旧的），
     * 新第一页落定后带新第一页的游标接着取，之后的续取沿着新的游标表走。
     */
    @Test
    fun `后台重拉在途时要的续取 等新第一页落定后带新游标取 旧页不接上 游标表跟着新页`() = runTest {
        val recorder = ConcurrentRequestRecorder()
        val firstPages = AtomicInteger()
        val reloadEntered = CompletableDeferred<Unit>()
        val reloadRelease = CompletableDeferred<Unit>()
        val staleEntered = CompletableDeferred<Unit>()
        val staleRelease = CompletableDeferred<Unit>()
        val client = resources.apiClient(MockEngine { request ->
            recorder.record(request)
            when (request.url.parameters["until"]) {
                null -> if (firstPages.incrementAndGet() == 1) {
                    respond(page(9, 8), headers = headers)
                } else {
                    reloadEntered.complete(Unit)
                    reloadRelease.await()
                    respond(page(12, 11), headers = headers)
                }
                "11" -> respond(page(10), headers = headers)
                "10" -> respond(page(), headers = headers)
                else -> {
                    staleEntered.complete(Unit)
                    staleRelease.await()
                    respond(page(7), headers = headers)
                }
            }
        })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        repo.refresh(query)

        val silent = async { repo.refresh(query) }
        reloadEntered.await()
        val more = async { repo.loadMore(query) }
        // 续取已经过了分页器：记下等重拉（返回），或者带着旧游标发了出去
        select {
            more.onAwait {}
            staleEntered.onAwait {}
        }
        reloadRelease.complete(Unit)
        assertNull(silent.await())
        staleRelease.complete(Unit)
        more.await()
        assertEquals(listOf(12L, 11L, 10L), repo.observe(query).value.items.map { it.id })

        repo.loadMore(query)
        assertEquals(listOf(null, null, "11", "10"), recorder.map { it.url.parameters["until"] })
        assertFalse(repo.observe(query).value.hasMore)
    }

    @Test
    fun `删除写穿各份feed 操作之前发出的刷新回包不把它带回来`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val feedRequests = AtomicInteger()
        val client = resources.apiClient(MockEngine { request ->
            if (request.method == HttpMethod.Delete) {
                respond("{}", headers = headers)
            } else if (feedRequests.incrementAndGet() == 1) {
                respond(page(9, 8), headers = headers)
            } else {
                entered.complete(Unit)
                release.await()
                respond(page(9, 8), headers = headers)
            }
        })
        val store = testTokenStore(scope = backgroundScope)
        store.completeSession(store.beginSession("account"), 42, "token")
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        repo.refresh(query)

        val stale = async { repo.refresh(query, userInitiated = true) }
        entered.await()
        assertEquals(Outcome.Success(Unit), repo.delete(9))
        release.complete(Unit)
        stale.await()

        val state = repo.observe(query).value
        assertEquals(listOf(8L), state.items.map { it.id })
        assertFalse(state.isRefreshing)
    }

    /** Pager 不是线程安全的：重拉、续取、本地改条目都要落在 feed 调度器上，而不是调用方线程。 */
    @Test
    fun `重拉续取和删除都在feed调度器上执行`() = runTest {
        val dispatched = AtomicInteger()
        val feedDispatcher = object : CoroutineDispatcher() {
            private val inner = StandardTestDispatcher(testScheduler)
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched.incrementAndGet()
                inner.dispatch(context, block)
            }
        }
        val client = resources.apiClient(MockEngine { respond(page(9, 8), headers = headers) })
        val store = testTokenStore(scope = backgroundScope)
        store.completeSession(store.beginSession("account"), 42, "token")
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, feedDispatcher)
        val query = TimelineQuery(TimelineMode.ALL)

        var before = dispatched.get()
        repo.refresh(query)
        assertTrue(dispatched.get() > before, "refresh")
        before = dispatched.get()
        repo.loadMore(query)
        assertTrue(dispatched.get() > before, "loadMore")
        before = dispatched.get()
        assertEquals(Outcome.Success(Unit), repo.delete(9))
        assertTrue(dispatched.get() > before, "delete")
    }

    @Test
    fun `匿名默认请求all且403不静默回退`() = runTest {
        val modes = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            modes.record(request)
            respond("{}", HttpStatusCode.Forbidden, headersOf("Content-Type", "application/json"))
        })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        assertNull(repo.refresh(TimelineQuery()), "列表为空时失败记进状态，不再另行返回")
        assertEquals(listOf<String?>("all"), modes.map { it.url.parameters["mode"] })
        assertEquals(Failure.Http(403), repo.observe(TimelineQuery()).value.error)
        assertFalse(repo.observe(TimelineQuery()).value.isLoading)
    }

    @Test
    fun `未登录或没过人机验证不发送写请求`() = runTest {
        val calls = AtomicInteger()
        val client = resources.apiClient(MockEngine { calls.incrementAndGet(); respond("{}", headers = headers) })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        assertEquals(Outcome.Failed(Failure.NotLoggedIn), repo.publish("内容", "fake-turnstile"))
        assertEquals(Outcome.Failed(Failure.NotLoggedIn), repo.react(1, 0))
        assertEquals(Outcome.Failed(Failure.NotLoggedIn), repo.reply(1, "内容", 0, "fake-turnstile"))
        assertEquals(0, calls.get())

        store.completeSession(store.beginSession("account"), 42, "token")
        assertTrue(repo.publish("内容", "") is Outcome.Failed, "没过人机验证不发")
        assertEquals(0, calls.get())
    }

    @Test
    fun `回复动态带正文与验证令牌发出 成功后各列表里这条的回复数加一`() = runTest {
        val sent = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            val path = request.url.encodedPath
            if (path.endsWith("/replies")) {
                sent.record(request)
                respond("""{"id":501}""", headers = headers)
            } else {
                respond(page(7, 8), headers = headers)
            }
        })
        val store = testTokenStore(scope = backgroundScope)
        store.completeSession(store.beginSession("account"), 42, "token")
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        repo.refresh(query)

        assertTrue(repo.reply(7, "", 0, "fake-turnstile") is Outcome.Failed, "空白正文不发")
        assertTrue(repo.reply(7, "同意", 0, "") is Outcome.Failed, "没过人机验证不发")
        assertEquals(Outcome.Success(501L), repo.reply(7, "[user=fixture_user]Fixture User[/user] 同意", 0, "fake-turnstile"))
        assertEquals(
            listOf("""POST /p1/timeline/7/replies {"content":"[user=fixture_user]Fixture User[/user] 同意","turnstileToken":"fake-turnstile","replyTo":0}"""),
            sent.map { "${it.method.value} ${it.url.encodedPath} ${it.body.toByteArray().decodeToString()}" },
        )
        val items = repo.observe(query).value.items.associateBy { it.id }
        assertEquals(1, items.getValue(7).replies)
        assertEquals(0, items.getValue(8).replies, "别的动态不动")
    }

    @Test
    fun `账户变化后迟到请求不能恢复旧feed`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = resources.apiClient(MockEngine {
            entered.complete(Unit)
            release.await()
            respond(page(99), headers = headers)
        })
        val store = testTokenStore(scope = backgroundScope)
        val pending = store.beginSession("account-a")
        store.completeSession(pending, 42, "a")
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        val old = repo.observe(query)
        val request = async { runCatching { repo.refresh(query) } }
        entered.await()
        store.clear()
        release.complete(Unit)
        request.await()
        assertTrue(old.value.items.isEmpty())
    }

    /** 发出途中登出：旧账号的失败（这里是 400）不提示到新会话上，结果是「账号已切换」。 */
    @Test
    fun `发动态途中换号 迟到的失败归为账号已切换`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = resources.apiClient(MockEngine {
            entered.complete(Unit)
            release.await()
            respond("{}", status = HttpStatusCode.BadRequest, headers = headers)
        })
        val store = testTokenStore(scope = backgroundScope)
        store.completeSession(store.beginSession("account-a"), 42, "a")
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val result = async { repo.publish("新动态", "fake-turnstile") }
        entered.await()
        store.clear()
        release.complete(Unit)
        assertEquals(Outcome.Failed(Failure.SessionChanged), result.await())
    }

    /** 前一笔写还占着操作锁时又点了一笔，排队期间换了号：这一笔属于点的时候的账号，排到时不能发给新账号。 */
    @Test
    fun `排队等前一笔写时换了号 这一笔以账号已切换结束 不发给新账号`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            requests.record(request)
            entered.complete(Unit)
            release.await()
            respond("{}", status = HttpStatusCode.BadRequest, headers = headers)
        })
        val store = testTokenStore(scope = backgroundScope)
        store.completeSession(store.beginSession("account-a"), 42, "a")
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val first = async { repo.publish("第一条", "fake-turnstile") }
        entered.await()
        // 点第二笔：直接跑到它停在操作锁前
        val second = async(start = CoroutineStart.UNDISPATCHED) { repo.publish("第二条", "fake-turnstile") }

        store.clear()
        store.completeSession(store.beginSession("account-b"), 43, "b")
        release.complete(Unit)

        assertEquals(Outcome.Failed(Failure.SessionChanged), first.await())
        assertEquals(Outcome.Failed(Failure.SessionChanged), second.await())
        assertEquals(listOf("Bearer account-a"), requests.map { it.headers["Authorization"] }, "只有第一笔发出，带的是旧账号")
    }

    @Test
    fun `timelineMessage 错误脱敏不拼接服务端英文原文`() = runTest {
        val client = resources.apiClient(MockEngine {
            respond(
                content = """{"message":"Turnstile token is invalid","description":"Verification failed"}""",
                status = HttpStatusCode.BadRequest,
                headers = headers,
            )
        })
        val store = testTokenStore(scope = backgroundScope)
        val pending = store.beginSession("test-account")
        store.completeSession(pending, 42, "test-token")
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val result = repo.publish("新动态", "fake-turnstile")
        // 服务端原文（Turnstile / Verification…）只用来认错误码，不上屏：原因只有状态码
        assertEquals(Outcome.Failed(Failure.Http(400)), result)
    }

    /**
     * 贴贴按动态挂的地方发：说说发到动态本身，单条收藏发到那条收藏（作品吐槽的端点）；表情就是点的那个，不是固定的一个。
     * 成功后缓存按「每人只留一个」回写。
     */
    @Test
    fun `贴贴发到动态挂的地方并回写缓存`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val client = resources.apiClient(MockEngine { request ->
            val path = request.url.encodedPath
            if (path.endsWith("/like")) {
                requests.record(request)
                respond("{}", headers = headers)
            } else {
                respond(reactableFeed(), headers = headers)
            }
        })
        val store = testTokenStore(scope = backgroundScope)
        store.completeSession(store.beginSession("account"), 42, "token")
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        repo.refresh(query)

        assertEquals(Outcome.Success(Unit), repo.react(1, 79))
        assertEquals(Outcome.Success(Unit), repo.react(2, 104))
        assertEquals(Outcome.Success(Unit), repo.react(2, null))
        assertEquals(
            listOf(
                """PUT /p1/timeline/1/like {"value":79}""",
                """PUT /p1/subjects/-/collects/88/like {"value":104}""",
                "DELETE /p1/subjects/-/collects/88/like",
            ),
            requests.map { "${it.method.value} ${it.url.encodedPath} ${it.body.toByteArray().decodeToString()}".trim() },
        )
        val items = repo.observe(query).value.items.associateBy { it.id }
        assertEquals(listOf(CommentReactionBo(value = 79, count = 8, selected = true)), items.getValue(1).reactions)
        assertEquals(listOf(CommentReactionBo(value = 104, count = 1)), items.getValue(2).reactions)
    }

    @Test
    fun `再打开另一个人的动态时 没人订阅的人的动态不再留着 还在订阅的留着`() = runTest {
        val client = resources.apiClient(MockEngine { request ->
            val path = request.url.encodedPath
            if (path.startsWith("/v0/users/")) {
                val name = path.substringAfterLast('/')
                respond(json.encodeToString(UserDto.serializer(), UserDto(name.length, name, name)), headers = headers)
            } else {
                respond(page(9), headers = headers)
            }
        })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val watched = TimelineQuery(TimelineMode.USER, "bb")
        val left = TimelineQuery(TimelineMode.USER, "a")
        // 还在栈里的页面：一直订阅着
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { repo.observe(watched).collect {} }
        repo.refresh(watched)
        // 已经出栈的页面：取过，但没人订阅了
        repo.refresh(left)
        assertEquals(listOf(9L), repo.observe(left).value.items.map { it.id })

        repo.observe(TimelineQuery(TimelineMode.USER, "ccc"))

        assertTrue(repo.observe(left).value.items.isEmpty(), "没人订阅的那份已经丢掉")
        assertEquals(listOf(9L), repo.observe(watched).value.items.map { it.id }, "还在订阅的不受影响")
    }

    @Test
    fun `刷过很多人的动态后_最早按名字查过的人让出内存`() = runTest {
        val userLookups = AtomicInteger()
        val crowd = (1..TIMELINE_USER_CACHE_SIZE * 2).map { uid ->
            TimelineDto(uid.toLong(), 1000 + uid, user = P1UserDto(1000 + uid, "u$uid", "U$uid"), cat = 5, type = 1)
        }
        val client = resources.apiClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.startsWith("/v0/users/") -> {
                    userLookups.incrementAndGet()
                    respond(json.encodeToString(UserDto.serializer(), UserDto(1, "a", "a")), headers = headers)
                }
                path.contains("/users/a/") -> respond(page(9), headers = headers)
                else -> respond(json.encodeToString(ListSerializer(TimelineDto.serializer()), crowd), headers = headers)
            }
        })
        val store = testTokenStore(scope = backgroundScope)
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        val someone = TimelineQuery(TimelineMode.USER, "a")
        repo.refresh(someone)
        repo.refresh(someone)
        assertEquals(1, userLookups.get(), "刚查过的人还在内存里")

        repo.refresh(TimelineQuery(TimelineMode.ALL))
        repo.refresh(someone)

        assertEquals(2, userLookups.get(), "内存里的人有上限，刷过 ${crowd.size} 个人之后最早那个已经让出")
    }

    /** 收藏只能贴作品吐槽那一组：组外的表情不发请求。 */
    @Test
    fun `收藏的贴贴不发作品吐槽组外的表情`() = runTest {
        val likes = AtomicInteger()
        val client = resources.apiClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/like")) likes.incrementAndGet()
            respond(if (request.url.encodedPath.endsWith("/like")) "{}" else reactableFeed(), headers = headers)
        })
        val store = testTokenStore(scope = backgroundScope)
        store.completeSession(store.beginSession("account"), 42, "token")
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, testWebSessionRepository(store, noWebClient()), backgroundScope, StandardTestDispatcher(testScheduler))
        repo.refresh(TimelineQuery(TimelineMode.ALL))

        assertTrue(repo.react(2, 79) is Outcome.Failed)
        assertEquals(0, likes.get())
    }

    /** id 1 是说说（7 个人贴了 79），id 2 是单条收藏（collectID 88，有人贴了 104）。 */
    private fun reactableFeed(): String {
        val others = (1..7).map { SimpleUserDto(100 + it, "u$it", "U$it") }
        return json.encodeToString(ListSerializer(TimelineDto.serializer()), listOf(
            TimelineDto(1, 7, cat = 5, type = 1, reactions = listOf(ReactionDto(others, 79))),
            TimelineDto(2, 7, cat = 3, type = 2,
                memo = TimelineMemoDto(subject = listOf(TimelineSubjectDto(P1SlimSubjectDto(8, "A", type = 2), "评论", null, 88))),
                reactions = listOf(ReactionDto(others.take(1), 104))),
        ))
    }

    /** 删除动态的接口 5xx：改用网页会话在自己的时间线页上点这条的删除链接（加 ajax=1 提交），网页回 ok 即做成，本地列表摘掉这条。 */
    @Test
    fun `删除动态遇服务端5xx时用网页会话删除`() = runTest {
        val posts = ConcurrentRequestRecorder()
        val web = testWebClient { request ->
            when {
                request.method == HttpMethod.Post -> {
                    posts.record(request)
                    respond("""{"status":"ok"}""", HttpStatusCode.OK)
                }
                request.url.encodedPath == "/settings/privacy" -> respond("""<script>var CHOBITS_UID = 42;</script><a href="/logout/0a1b2c3d">登出</a>""", HttpStatusCode.OK)
                request.url.encodedPath == "/user/token/timeline" ->
                    respond("""<a href="/erase/tml/8?gh=0a1b2c3d" class="tml_del">x</a><a href="/erase/tml/9?gh=0a1b2c3d" class="tml_del">x</a>""", HttpStatusCode.OK)
                else -> error("不该打开：${request.url}")
            }
        }
        val client = resources.apiClient(MockEngine { request ->
            if (request.method == HttpMethod.Delete) respond("", HttpStatusCode.InternalServerError) else respond(page(9, 8), headers = headers)
        })
        val store = testTokenStore(scope = backgroundScope)
        store.completeSession(store.beginSession("account"), 42, "token")
        val webSessions = testWebSessionRepository(store, web)
        webSessions.save(store.session(), WebSession("chii_auth=c00k1e"))
        val repo = TimelineRepository(TimelineApi(client), NextApi(client), UserApi(client), store, webSessions, backgroundScope, StandardTestDispatcher(testScheduler))
        val query = TimelineQuery(TimelineMode.ALL)
        repo.refresh(query)

        assertEquals(Outcome.Success(Unit), repo.delete(9))
        assertEquals(listOf<Pair<String, String?>>("https://bgm.tv/erase/tml/9?gh=0a1b2c3d&ajax=1" to "chii_auth=c00k1e"), posts.map { it.url.toString() to it.headers["Cookie"] })
        assertEquals(listOf(8L), repo.observe(query).value.items.map { it.id })
    }
}
