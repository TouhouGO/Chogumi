package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class UnreadSessionTest {
    private class Harness : AutoCloseable {
        private val dir = Files.createTempDirectory("unread-session").toFile()
        private val storesScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val tokens = PreferenceDataStoreFactory.create(scope = storesScope) { File(dir, "tokens.preferences_pb") }
        private val preferences = PreferenceDataStoreFactory.create(scope = storesScope) { File(dir, "preferences.preferences_pb") }
        val tokenStore = TokenStore(tokens)
        val settings = PreferencesRepository(preferences)
        private val observerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val observerScope = CoroutineScope(SupervisorJob() + observerDispatcher)
        val requests = ConcurrentRequestRecorder()
        @Volatile var total = 8
        @Volatile var onNotify: suspend () -> Unit = {}
        @Volatile var onClear: suspend () -> Unit = {}
        @Volatile var clearStatus = HttpStatusCode.OK
        private val engine = MockEngine { request ->
            requests.record(request)
            when (request.url.encodedPath) {
                "/p1/notify" -> {
                    val count = total
                    onNotify()
                    respond("""{"data":[],"total":$count}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/p1/clear-notify" -> {
                    onClear()
                    respond("{}", clearStatus, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> error("意外请求")
            }
        }
        private val client = testApiClient(engine)
        val repository = NotificationRepository(NextApi(client), tokenStore, testWebSessionRepository(tokenStore, noWebClient()), settings, observerScope)

        suspend fun signIn(id: Int) {
            tokenStore.completeSession(tokenStore.beginSession("test-token-$id"), id, "user$id")
        }

        /**
         * 占住观察者的线程，让它错过 [block] 期间的中间拍。占住前确认它已处理之前的任务。
         * 它那一轮什么时候结束只有生产代码知道（锁内还要去 DataStore 读会话与开关）：调用方等 [NotificationRepository.unreadCount]，不等调度队列排空。
         */
        suspend fun whileObserverPaused(block: suspend () -> Unit) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val blocker = observerScope.launch {
                entered.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
            }
            withTimeout(WAIT_BUDGET_MS) { entered.await() }
            try {
                block()
            } finally {
                release.countDown()
            }
            blocker.join()
        }

        override fun close() {
            client.close()
            engine.close()
            cancelAndAwait(listOf(client, engine, observerScope, storesScope))
            observerDispatcher.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `观察者错过未认证中间拍换号仍清掉前账号未读数`() = runBlocking {
        Harness().use { h ->
            h.signIn(7)
            h.settings.setExperienceMode(ExperienceMode.FULL)
            h.repository.refreshUnreadCount()
            assertEquals(8, h.repository.unreadCount.value)

            h.whileObserverPaused { h.signIn(8) }

            awaitUntil("true到true也已经是另一个会话，未读数清零") { h.repository.unreadCount.value == 0 }
        }
    }

    @Test
    fun `通知在途切专注模式后迟到响应不能恢复未读数`() = runBlocking {
        Harness().use { h ->
            h.signIn(7)
            h.settings.setExperienceMode(ExperienceMode.FULL)
            h.repository.refreshUnreadCount()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.onNotify = { entered.complete(Unit); release.await() }
            val response = async(Dispatchers.IO) { h.repository.getNotifications() }
            withTimeout(WAIT_BUDGET_MS) { entered.await() }
            h.settings.setExperienceMode(ExperienceMode.FOCUS)
            awaitUntil("切到专注模式后未读数清零") { h.repository.unreadCount.value == 0 }
            release.complete(Unit)
            response.await()

            assertEquals(0, h.repository.unreadCount.value, "专注模式不接收在途的旧通知")
        }
    }

    @Test
    fun `清已读失败结束占用后新的刷新仍可发布`() = runBlocking {
        Harness().use { h ->
            h.signIn(7)
            h.settings.setExperienceMode(ExperienceMode.FULL)
            h.repository.refreshUnreadCount()
            h.clearStatus = HttpStatusCode.InternalServerError
            assertEquals(Outcome.Failed(Failure.Http(500)), h.repository.markAllAsRead())
            assertEquals(8, h.repository.unreadCount.value)

            h.total = 3
            h.repository.refreshUnreadCount()
            assertEquals(3, h.repository.unreadCount.value)
        }
    }

    @Test
    fun `清已读请求被取消后释放占用新的读取可发布`() = runBlocking {
        Harness().use { h ->
            h.signIn(7)
            h.settings.setExperienceMode(ExperienceMode.FULL)
            h.repository.refreshUnreadCount()
            val entered = CompletableDeferred<Unit>()
            val never = CompletableDeferred<Unit>()
            h.onClear = { entered.complete(Unit); never.await() }
            val clear = async(Dispatchers.IO) { h.repository.markRead(101) }
            withTimeout(WAIT_BUDGET_MS) { entered.await() }
            clear.cancel()
            clear.join()

            h.total = 4
            h.repository.refreshUnreadCount()
            assertEquals(4, h.repository.unreadCount.value)
        }
    }

    @Test
    fun `清已读成功后旧列表响应不能恢复已清零的数`() = runBlocking {
        Harness().use { h ->
            h.signIn(7)
            h.settings.setExperienceMode(ExperienceMode.FULL)
            h.repository.refreshUnreadCount()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.onNotify = { entered.complete(Unit); release.await() }
            val response = async(Dispatchers.IO) { h.repository.getNotifications() }
            withTimeout(WAIT_BUDGET_MS) { entered.await() }

            assertIs<Outcome.Success<Unit>>(h.repository.markAllAsRead())
            assertEquals(0, h.repository.unreadCount.value)
            release.complete(Unit)
            response.await()

            assertEquals(0, h.repository.unreadCount.value, "清已读后的计数不由旧GET倒退")
        }
    }
}
