package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [NsfwPreferenceSynchronizer]：**唯一刷新观察点**的行为契约。
 *
 * 这里用真实 TokenStore + 真实仓库 + MockEngine 冒充 `/p1/privacy`，
 * 观察点用真实的类（不 mock），因为被测的正是"它是否真的在观察"。
 * 等待一律用轮询最终态，不用固定 delay。
 *
 * ## 调度器为什么要专用（而不是 Dispatchers.IO）
 *
 * 观察点是"注册即生效"的常驻协程，若与 DataStore/其他测试共用全局
 * `Dispatchers.IO`，其调度时机取决于该池的繁忙程度。用例等的是"观察者产生
 * 一次请求"这个**最终态**，一旦观察者在该窗口内没被调度到，等待就会超时 ——
 * 这类失败与被测逻辑无关，却会在 CI 上偶发（同一用例两次构建分别
 * 超时，负载不同挂的用例不同）。故每个用例给观察者一个**专用单线程调度器**，
 * 把"能否及时调度"从被测契约里剔除。
 *
 * ## 用例覆盖的真实缺陷
 *
 * 1. 观察点必须**真的被创建并开始观察**（只在 Koin 里声明、无人 get 的话，
 *    lazy single 永不构造 → 登录后永远不刷新）；
 * 2. 刷新失败**不得打死观察者**（否则第一次失败后，换号/重登都不再刷新）；
 * 3. **换号必须刷新**，且不得依赖"能否观察到中间态"——`sessionFlow` 的底层是
 *    DataStore 的 conflating StateFlow，`beginSession`（未认证）与
 *    `completeSession`（已认证）两次写库之间的中间态会被合并；
 *    按 `authenticated` 布尔位去重时，换号 A→B 被看成 true→true 而**漏刷新**。
 */
class NsfwPreferenceSynchronizerTest {
    /** 临时目录与 HTTP 客户端的收尾；DataStore 与观察者的作用域跑在下面的专用线程上，由 [tearDown] 结束。 */
    @get:org.junit.Rule
    val resources = TestResources()

    /** DataStore 专用调度器：隔离全局 IO 池的繁忙程度。 */
    private val dataStoreExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nsfw-datastore").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(dataStoreExecutor.asCoroutineDispatcher() + SupervisorJob())

    /** 观察者专用调度器：保证它必然被调度，隔离全局 IO 池的繁忙程度。 */
    private val observerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nsfw-observer").apply { isDaemon = true }
    }
    private val observerDispatcher: CoroutineDispatcher = observerExecutor.asCoroutineDispatcher()

    /** 各用例建的观察者作用域：收尾时取消并等它们结束，再关调度器。 */
    private val observerScopes = mutableListOf<CoroutineScope>()

    private fun observerScope() = CoroutineScope(observerDispatcher + SupervisorJob()).also { observerScopes += it }

    @AfterTest
    fun tearDown() {
        cancelAndAwait(observerScopes + scope)
        observerExecutor.shutdownNow()
        dataStoreExecutor.shutdownNow()
    }

    private class Harness(
        val tokenStore: TokenStore,
        val privacyRequests: AtomicInteger,
        val observerScope: CoroutineScope,
        val observerDispatcher: CoroutineDispatcher,
        val requests: ConcurrentRequestRecorder,
        val preferences: DataStore<Preferences>,
    )

    /**
     * 组装真实的同步链路：MockEngine 统计 `/p1/privacy` 的 GET 次数。
     * [failRequest] > 0 时前 N 次 GET 抛 IO 异常（模拟网络失败）。
     * [allowByToken] 按 Authorization 里的 token 决定响应值，用于验证换号不串号。
     */
    private fun harness(
        failRequest: AtomicInteger = AtomicInteger(0),
        allow: Boolean = true,
        allowByToken: Map<String, Boolean> = emptyMap(),
    ): Harness {
        val dir = resources.directory("sync")
        val requests = AtomicInteger(0)
        val recorder = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            recorder.record(request)
            requests.incrementAndGet()
            if (failRequest.getAndDecrement() > 0) throw java.io.IOException("boom")
            val token = request.headers["Authorization"]?.substringAfter("Bearer ")?.trim()
            val value = allowByToken[token] ?: allow
            respond(
                content = privacyJson(value),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val tokenStore = testTokenStore(scope = scope)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "prefs.preferences_pb") }
        val repository = NsfwPreferenceRepository(
            dataStore = preferences,
            privacyApi = PrivacyApi(client),
            tokenStore = tokenStore,
            webSessions = testWebSessionRepository(tokenStore, noWebClient()),
        )
        // 真正的观察点，跑在专用调度器上（生产为 App 级作用域，见类文档）。
        val observerScope = observerScope()
        NsfwPreferenceSynchronizer(tokenStore, repository, observerScope)
        return Harness(tokenStore, requests, observerScope, observerDispatcher, recorder, preferences)
    }

    @Test
    fun `登录即触发刷新_无需打开设置页`() = runBlocking {
        val h = harness()

        // 启动初期未登录：观察点不应发请求。
        assertEquals(0, h.privacyRequests.get(), "未登录不该请求 /p1/privacy")

        // 模拟登录：会话变为已认证。观察点应自行发 GET，没有任何人打开设置页。
        val session = h.tokenStore.beginSession("tok")
        h.tokenStore.completeSession(session, userId = 1001, username = "tester")

        awaitUntil("登录必须触发一次 /p1/privacy 刷新") { h.privacyRequests.get() >= 1 }
    }

    @Test
    fun `启动时已登录_观察点构造即触发刷新`() = runBlocking {
        // 会话先就位，再构造观察点 —— 模拟"App 启动时本地已有登录态"。
        val dir = resources.directory("sync-boot")
        val requests = AtomicInteger(0)
        val engine = MockEngine {
            requests.incrementAndGet()
            respond(
                content = privacyJson(true),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val tokenStore = testTokenStore(scope = scope)
        val repository = NsfwPreferenceRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "prefs.preferences_pb") },
            privacyApi = PrivacyApi(client),
            tokenStore = tokenStore,
            webSessions = testWebSessionRepository(tokenStore, noWebClient()),
        )
        val session = tokenStore.beginSession("tok")
        tokenStore.completeSession(session, userId = 1001, username = "tester")

        NsfwPreferenceSynchronizer(tokenStore, repository, observerScope())

        awaitUntil("启动恢复后自动刷新") { requests.get() >= 1 }
    }

    @Test
    fun `首次刷新失败后_登出再登录仍会再次刷新`() = runBlocking {
        // 第一次 GET 失败，验证观察者没被异常打死。
        val h = harness(failRequest = AtomicInteger(1))

        val first = h.tokenStore.beginSession("tok-1")
        h.tokenStore.completeSession(first, userId = 1001, username = "tester")
        awaitUntil("第一次刷新已发生（且失败）") { h.privacyRequests.get() >= 1 }

        // 登出再登录：若观察者已死，这一步不会产生新请求。
        h.tokenStore.clear()
        val second = h.tokenStore.beginSession("tok-2")
        h.tokenStore.completeSession(second, userId = 1001, username = "tester")

        awaitUntil("重新登录后再次刷新") { h.privacyRequests.get() >= 2 }
    }

    @Test
    fun `换号也会触发刷新且不串号`() = runBlocking {
        withTimeout(WAIT_BUDGET_MS) {
            val h = harness(allowByToken = mapOf("tok-a" to true, "tok-b" to false), allow = true)
            val a = h.tokenStore.beginSession("tok-a")
            h.tokenStore.completeSession(a, userId = 1001, username = "a")
            h.preferences.data.first { it[PreferenceKeys.nsfwAllowKey(1001)] == true }

            val b = h.tokenStore.beginSession("tok-b")
            h.tokenStore.completeSession(b, userId = 2002, username = "b")

            // canSet 与 allow 在同一次 edit 里写，等账号 B 的持久快照而不是刚发出请求那一拍。
            val saved = h.preferences.data.first { it[PreferenceKeys.nsfwCanSetKey(2002)] == true }
            assertEquals(listOf("Bearer tok-a", "Bearer tok-b"), h.requests.map { it.headers["Authorization"] })
            assertEquals(true, saved[PreferenceKeys.nsfwAllowKey(1001)], "B 的关闭不能覆盖 A 的允许值")
            assertEquals(false, saved[PreferenceKeys.nsfwAllowKey(2002)], "B 的响应必须落到 B 的分区")
            assertEquals(false, saved[PreferenceKeys.nsfwShowKey(2002)])
        }
    }

    /**
     * **回归用例**：不经过登出，直接从账号 A 换到账号 B，
     * 且**故意让观察者错过中间态** —— 用占位任务占住观察者的专用线程，
     * 使 `beginSession`（未认证）与 `completeSession`（已认证）两次写库都落在
     * 观察者两次运行之间，`sessionFlow` 的 conflating StateFlow 把中间态合并掉。
     *
     * 按 `authenticated` 布尔位去重，观察者只看到 true→true，会**漏刷新**；按会话代数去重，
     * 末值换成了 B 的会话，**必然刷新**。中间态被合并是确定发生的（见下方同步点），所以这条每次都能分出两种写法。
     */
    @Test
    fun `换号_即使观察者错过中间态也必须刷新`() = runBlocking {
        val h = harness()
        val a = h.tokenStore.beginSession("tok-a")
        h.tokenStore.completeSession(a, userId = 1001, username = "a")
        awaitUntil("账号 A 刷新") { h.privacyRequests.get() >= 1 }

        // 占住观察者专用线程，期间完成 A → B 的两次写库。
        // 确定性同步点：blockerStarted 确认占位任务已抢到单线程，CountDownLatch 阻塞持有线程，
        // 待 A → B 两次写库完成后再精准释放，不靠任何经验时间窗。
        val blockerStarted = CompletableDeferred<Unit>()
        val releaseLatch = CountDownLatch(1)
        val blocker = h.observerScope.launch(h.observerDispatcher) {
            blockerStarted.complete(Unit)
            releaseLatch.await(10, TimeUnit.SECONDS)
        }
        blockerStarted.await()
        try {
            val b = h.tokenStore.beginSession("tok-b")
            h.tokenStore.completeSession(b, userId = 2002, username = "b")
        } finally {
            releaseLatch.countDown()
        }
        blocker.join()

        awaitUntil("不经过登出的 A→B 换号必须刷新，且不得依赖中间态可见") { h.privacyRequests.get() >= 2 }
    }

    /**
     * **回归用例**：登出再登录**同一个账号**，观察者错过两次写库之间的中间态（同上，用占位任务占住它的线程）。
     * 按 userId 去重时末值仍是 1001→1001，重登后不刷新；按会话代数去重，末值变了，必然刷新。
     */
    @Test
    fun `同一账号登出再登录_即使观察者错过中间态也必须刷新`() = runBlocking {
        val h = harness()
        val first = h.tokenStore.beginSession("tok-1")
        h.tokenStore.completeSession(first, userId = 1001, username = "tester")
        awaitUntil("第一次登录刷新") { h.privacyRequests.get() >= 1 }

        val blockerStarted = CompletableDeferred<Unit>()
        val releaseLatch = CountDownLatch(1)
        val blocker = h.observerScope.launch(h.observerDispatcher) {
            blockerStarted.complete(Unit)
            releaseLatch.await(10, TimeUnit.SECONDS)
        }
        blockerStarted.await()
        try {
            h.tokenStore.clear()
            val second = h.tokenStore.beginSession("tok-2")
            h.tokenStore.completeSession(second, userId = 1001, username = "tester")
        } finally {
            releaseLatch.countDown()
        }
        blocker.join()

        awaitUntil("同号重登后刷新（不依赖观察到中间态）") { h.privacyRequests.get() >= 2 }
    }

    @Test
    fun `refresh失败只返回错误态_不抛非取消异常`() = runBlocking {
        // 直接对仓库断言其契约：这是观察者能存活的前提。
        val dir = resources.directory("sync-c")
        val engine = MockEngine { throw java.io.IOException("boom") }
        val client = resources.apiClient(engine)
        val tokenStore = testTokenStore(scope = scope)
        val repository = NsfwPreferenceRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "prefs.preferences_pb") },
            privacyApi = PrivacyApi(client),
            tokenStore = tokenStore,
            webSessions = testWebSessionRepository(tokenStore, noWebClient()),
        )
        val session = tokenStore.beginSession("tok")
        tokenStore.completeSession(session, userId = 1001, username = "tester")

        // 不 try/catch：若抛异常本用例即失败。
        val result = repository.refresh()
        assertTrue(result is Outcome.Failed, "网络失败必须收敛成错误态而非抛出")
        // 再取一次缓存值，确认失败没有把状态搞坏。
        assertEquals(false, repository.showNsfw())
    }

    private fun privacyJson(allowNsfw: Boolean): String = """
        {
          "settings": {
            "privateMessage": "all", "timelineReply": "all", "timelineCollectReply": "all",
            "follow": "all", "mentionNotification": "all", "commentNotification": "all",
            "friendNotification": "all"
          },
          "preferences": {
            "showNsfwSubject": $allowNsfw, "canSetNsfwSubject": true, "allowNsfw": $allowNsfw
          }
        }
    """.trimIndent()
}
