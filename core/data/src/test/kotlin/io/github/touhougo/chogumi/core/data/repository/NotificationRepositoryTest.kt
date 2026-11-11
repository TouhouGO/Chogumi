package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class NotificationRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterTest
    fun tearDown() {
        cancelAndAwait(listOf(testScope, ioScope))
    }

    private class MockState {
        val notifyRequests = AtomicInteger(0)
        val clearRequests = AtomicInteger(0)
        @Volatile var lastClearBody: String? = null
        @Volatile var lastNotifyUnread: String? = null
        @Volatile var lastNotifyLimit: String? = null
        var shouldFailClear = false
        var unreadTotal = 3
        /** `/p1/notify` 回响应之前先跑它：用例在这里卡住请求，期间切换账号。 */
        var onNotify: (suspend () -> Unit)? = null
    }

    private fun createRepository(
        state: MockState = MockState(),
    ): Triple<NotificationRepository, TokenStore, PreferencesRepository> {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/p1/notify" -> {
                    state.notifyRequests.incrementAndGet()
                    state.lastNotifyUnread = request.url.parameters["unread"]
                    state.lastNotifyLimit = request.url.parameters["limit"]
                    state.onNotify?.invoke()
                    val responseJson = """
                        {
                            "data": [
                                {
                                    "id": 101,
                                    "type": 1,
                                    "sender": {
                                        "id": 42,
                                        "username": "alice",
                                        "nickname": "Alice &amp; Friends",
                                        "avatar": {
                                            "large": "https://bgm.tv/avatar/l.jpg",
                                            "medium": "https://bgm.tv/avatar/m.jpg",
                                            "small": "https://bgm.tv/avatar/s.jpg"
                                        },
                                        "group": 10,
                                        "sign": "Hello",
                                        "joinedAt": 1600000000,
                                        "isFriend": false
                                    },
                                    "title": "Hello &quot;World&quot;",
                                    "mainID": 1001,
                                    "relatedID": 2002,
                                    "createdAt": 1789691721,
                                    "unread": true
                                }
                            ],
                            "total": ${state.unreadTotal}
                        }
                    """.trimIndent()
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                "/p1/clear-notify" -> {
                    state.clearRequests.incrementAndGet()
                    state.lastClearBody = request.body.toByteArray().decodeToString()
                    if (state.shouldFailClear) {
                        respond(
                            content = """{"code":"INTERNAL_SERVER_ERROR","error":"Internal Server Error","message":"Failed","statusCode":500}""",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond(
                            content = "{}",
                            status = HttpStatusCode.OK,
                            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    }
                }
                else -> respond("Not Found", HttpStatusCode.NotFound)
            }
        }

        val httpClient = resources.apiClient(engine)
        val nextApi = NextApi(httpClient)

        val tokenStore = testTokenStore(scope = ioScope)

        val tempPrefs = File(resources.directory("prefs"), "p.preferences_pb")
        val preferencesRepository = PreferencesRepository(PreferenceDataStoreFactory.create(scope = ioScope) { tempPrefs })

        val repository = NotificationRepository(
            nextApi = nextApi,
            tokenStore = tokenStore,
            webSessions = testWebSessionRepository(tokenStore, noWebClient()),
            preferencesRepository = preferencesRepository,
            scope = testScope,
        )

        return Triple(repository, tokenStore, preferencesRepository)
    }

    private suspend fun TokenStore.login(userId: Int = 1001, username: String = "tester", token: String = "test_token") {
        val session = beginSession(token)
        completeSession(session, userId = userId, username = username)
    }

    /** 用 [state] 建好已登录、开着社区的仓库，`/p1/notify` 卡在 [MockState.onNotify] 上，等请求到了换成另一个账号再放行。 */
    private suspend fun <T> switchAccountDuringNotify(
        state: MockState,
        call: suspend (NotificationRepository) -> T,
    ): Pair<T, NotificationRepository> {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        state.onNotify = { arrived.complete(Unit); release.await() }
        val (repo, tokenStore, prefs) = createRepository(state)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FULL)
        val result = testScope.async { call(repo) }
        withTimeout(WAIT_BUDGET_MS) { arrived.await() }
        tokenStore.login(userId = 2002, username = "other", token = "other_token")
        release.complete(Unit)
        return withTimeout(WAIT_BUDGET_MS) { result.await() } to repo
    }

    @Test
    fun `通知请求途中换了账号 不交出上一个账号的通知 也不记它的未读数`() = runBlocking {
        val (result, repo) = switchAccountDuringNotify(MockState().apply { unreadTotal = 5 }) { it.getNotifications() }
        assertEquals(Outcome.Failed(Failure.SessionChanged), result)
        assertEquals(0, repo.unreadCount.value)
    }

    @Test
    fun `未读数请求途中换了账号 不记上一个账号的未读数`() = runBlocking {
        val (_, repo) = switchAccountDuringNotify(MockState().apply { unreadTotal = 5 }) { it.refreshUnreadCount() }
        assertEquals(0, repo.unreadCount.value)
    }

    @Test
    fun `未登录时不发起网络请求且未读数保持为0`() = runBlocking {
        val mockState = MockState()
        val (repo, _, prefs) = createRepository(mockState)
        prefs.setExperienceMode(ExperienceMode.FULL)

        repo.refreshUnreadCount()

        assertEquals(0, mockState.notifyRequests.get())
        assertEquals(0, repo.unreadCount.value)

        val result = repo.getNotifications()
        assertTrue(result is Outcome.Success)
        assertTrue(result.value.isEmpty())
        assertEquals(0, mockState.notifyRequests.get())
    }

    @Test
    fun `专注模式下不发起网络请求且未读数保持为0`() = runBlocking {
        val mockState = MockState()
        val (repo, tokenStore, prefs) = createRepository(mockState)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FOCUS)

        repo.refreshUnreadCount()

        assertEquals(0, mockState.notifyRequests.get())
        assertEquals(0, repo.unreadCount.value)

        val result = repo.getNotifications()
        assertTrue(result is Outcome.Success)
        assertTrue(result.value.isEmpty())
        assertEquals(0, mockState.notifyRequests.get())
    }

    @Test
    fun `登录且开启社区功能时，刷新未读数成功更新 unreadCount`() = runBlocking {
        val mockState = MockState().apply { unreadTotal = 5 }
        val (repo, tokenStore, prefs) = createRepository(mockState)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FULL)

        repo.refreshUnreadCount()

        assertEquals(1, mockState.notifyRequests.get())
        assertEquals(5, repo.unreadCount.value)
        assertEquals("1", mockState.lastNotifyLimit, "未读数只读 total，不必取回整页通知")
    }

    @Test
    fun `通知列表已读未读都取，未读数只取未读`() = runBlocking {
        val mockState = MockState()
        val (repo, tokenStore, prefs) = createRepository(mockState)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FULL)

        repo.getNotifications()
        assertEquals(null, mockState.lastNotifyUnread)

        repo.refreshUnreadCount()
        assertEquals("true", mockState.lastNotifyUnread)
    }

    @Test
    fun `获取通知列表成功并正确映射字段`() = runBlocking {
        val mockState = MockState().apply { unreadTotal = 3 }
        val (repo, tokenStore, prefs) = createRepository(mockState)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FULL)

        val result = repo.getNotifications()

        assertTrue(result is Outcome.Success)
        assertEquals(1, result.value.size)
        val notice = result.value.first()
        assertEquals(101, notice.id)
        assertEquals(1, notice.type)
        assertEquals(1001, notice.mainId)
        assertEquals(2002, notice.relatedId)
        assertEquals(1789691721000L, notice.createdAt)
        assertEquals("Hello \"World\"", notice.title)
        assertEquals("Alice & Friends", notice.sender.nickname)
        assertEquals("https://bgm.tv/avatar/l.jpg", notice.sender.avatar)
        assertTrue(notice.unread)
        assertEquals(3, repo.unreadCount.value)
    }

    @Test
    fun `全部标为已读成功：发空JSON体，未读数重置为0`() = runBlocking {
        val mockState = MockState().apply { unreadTotal = 8 }
        val (repo, tokenStore, prefs) = createRepository(mockState)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FULL)

        repo.refreshUnreadCount()
        assertEquals(8, repo.unreadCount.value)

        val clearResult = repo.markAllAsRead()
        assertTrue(clearResult is Outcome.Success)
        assertEquals(1, mockState.clearRequests.get())
        assertEquals("{}", mockState.lastClearBody)
        assertEquals(0, repo.unreadCount.value)
    }

    @Test
    fun `标一条为已读：请求体只带这条的id，未读数减一`() = runBlocking {
        val mockState = MockState().apply { unreadTotal = 8 }
        val (repo, tokenStore, prefs) = createRepository(mockState)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FULL)

        repo.refreshUnreadCount()
        assertEquals(8, repo.unreadCount.value)

        val result = repo.markRead(101)
        assertTrue(result is Outcome.Success)
        assertEquals(1, mockState.clearRequests.get())
        assertEquals("""{"id":[101]}""", mockState.lastClearBody)
        assertEquals(7, repo.unreadCount.value)
    }

    @Test
    fun `标一条为已读失败：返回失败，未读数不变`() = runBlocking {
        val mockState = MockState().apply {
            unreadTotal = 8
            shouldFailClear = true
        }
        val (repo, tokenStore, prefs) = createRepository(mockState)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FULL)

        repo.refreshUnreadCount()
        assertEquals(Outcome.Failed(Failure.Http(500)), repo.markRead(101))
        assertEquals(1, mockState.clearRequests.get())
        assertEquals("""{"id":[101]}""", mockState.lastClearBody)
        assertEquals(8, repo.unreadCount.value)
    }

    @Test
    fun `全部标为已读服务端返回错误：返回失败且不破坏状态`() = runBlocking {
        val mockState = MockState().apply {
            unreadTotal = 8
            shouldFailClear = true
        }
        val (repo, tokenStore, prefs) = createRepository(mockState)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FULL)

        repo.refreshUnreadCount()
        assertEquals(8, repo.unreadCount.value)

        val clearResult = repo.markAllAsRead()
        assertEquals(Outcome.Failed(Failure.Http(500)), clearResult)
        assertEquals(1, mockState.clearRequests.get())
        assertEquals("{}", mockState.lastClearBody)
        assertEquals(8, repo.unreadCount.value)
    }

    @Test
    fun `用户登出或切换至专注模式时，未读数立即归零`() = runBlocking {
        val mockState = MockState().apply { unreadTotal = 12 }
        val (repo, tokenStore, prefs) = createRepository(mockState)
        tokenStore.login()
        prefs.setExperienceMode(ExperienceMode.FULL)

        repo.refreshUnreadCount()
        assertEquals(12, repo.unreadCount.value)

        // 切换至专注模式
        prefs.setExperienceMode(ExperienceMode.FOCUS)
        awaitUntil("切换专注模式后未读数归零") { repo.unreadCount.value == 0 }

        // 恢复完整模式
        prefs.setExperienceMode(ExperienceMode.FULL)
        repo.refreshUnreadCount()
        assertEquals(12, repo.unreadCount.value)

        // 登出
        tokenStore.clear()
        awaitUntil("登出后未读数归零") { repo.unreadCount.value == 0 }
    }

    @Test
    fun `读不出本机偏好时刷新未读数不抛出_访问观察不带崩进程`() = runBlocking {
        val escaped = Collections.synchronizedList(mutableListOf<Throwable>())
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> escaped += e })
        val failedReads = AtomicInteger(0)
        val unreadable = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { failedReads.incrementAndGet(); throw IOException("偏好文件读不出") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw IOException("偏好文件写不进")
        }
        val tokenStore = testTokenStore(scope = ioScope).apply { login() }
        val repository = NotificationRepository(
            nextApi = NextApi(resources.apiClient(MockEngine { respond("", HttpStatusCode.NotFound) })),
            tokenStore = tokenStore,
            webSessions = testWebSessionRepository(tokenStore, noWebClient()),
            preferencesRepository = PreferencesRepository(unreadable),
            scope = appScope,
        )
        awaitUntil("访问观察读过一次偏好") { failedReads.get() >= 1 }

        repository.refreshUnreadCount()

        assertEquals(emptyList(), escaped.toList(), "偏好读失败不得漏到应用级作用域")
        assertEquals(0, repository.unreadCount.value)
        appScope.cancel()
    }
}
