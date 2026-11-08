package io.github.touhougo.chogumi.core.data.token

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 同步身份与本地持久会话一起前进，不依赖异步消费者是否已被调度。 */
class SessionCaptureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = Files.createTempDirectory("session-capture").toFile()
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
        directory.resolve("tokens.preferences_pb")
    }
    private val tokens = TokenStore(dataStore)

    @AfterTest
    fun tearDown() {
        cancelAndAwait(listOf(scope))
        directory.deleteRecursively()
    }

    @Test
    fun `未恢复的动作拒绝 延迟登录不能取得已失败动作`() = runBlocking {
        val captured = tokens.captureSession()
        val session = tokens.completeSession(tokens.beginSession("fixture"), 7, "fixture")
        assertEquals(Outcome.Failed(Failure.SessionChanged), captured)
        assertEquals(Outcome.Success(session), tokens.captureSession())
    }

    @Test
    fun `重新创建后先恢复持久身份 同步捕获与观察源相同`() = runBlocking {
        val signedIn = tokens.completeSession(tokens.beginSession("fixture"), 7, "fixture")
        val restored = TokenStore(dataStore)
        assertEquals(Outcome.Failed(Failure.SessionChanged), restored.captureSession())
        assertEquals(signedIn, withTimeout(WAIT_BUDGET_MS) { restored.sessionFlow.first() })
        assertEquals(Outcome.Success(signedIn), restored.captureSession())
    }

    @Test
    fun `清除返回时同步捕获已拒绝登录动作`() = runBlocking {
        tokens.completeSession(tokens.beginSession("fixture"), 7, "fixture")
        val captured = tokens.captureSession()
        tokens.clear()
        assertEquals(Outcome.Failed(Failure.NotLoggedIn), tokens.captureSession())
        assertEquals(7, (captured as Outcome.Success).value.userId)
        assertEquals(false, tokens.sessionFlow.first().authenticated)
    }

    @Test
    fun `令牌刷新不改变动作身份或触发新会话`() = runBlocking {
        val session = tokens.completeSession(tokens.beginSession("fixture"), 7, "fixture")
        val initial = CompletableDeferred<Unit>()
        val next = scope.async(start = CoroutineStart.UNDISPATCHED) {
            tokens.sessionFlow.take(2).toList().last()
        }
        val subscriber = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            tokens.sessionFlow.collect { initial.complete(Unit) }
        }
        withTimeout(WAIT_BUDGET_MS) { initial.await() }
        tokens.refreshSession(session, "refreshed", "refresh")
        assertEquals(Outcome.Success(session), tokens.captureSession())
        tokens.clear()
        assertEquals(false, withTimeout(WAIT_BUDGET_MS) { next.await() }.authenticated)
        subscriber.cancelAndJoin()
    }

    @Test
    fun `持久提交后取消仍发布同一身份 观察者不能停留旧账号`() = runBlocking {
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val wrapped = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = dataStore.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val value = dataStore.updateData(transform)
                written.complete(Unit)
                release.await()
                return value
            }
        }
        val store = TokenStore(wrapped)
        val observerReady = CompletableDeferred<Unit>()
        val next = scope.async(start = CoroutineStart.UNDISPATCHED) {
            store.sessionFlow.take(2).toList().last()
        }
        val subscriber = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.sessionFlow.collect { observerReady.complete(Unit) }
        }
        withTimeout(WAIT_BUDGET_MS) { observerReady.await() }
        val write = scope.launch { store.beginSession("fixture") }
        withTimeout(WAIT_BUDGET_MS) { written.await() }
        write.cancel()
        release.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { write.join() }
        assertEquals("fixture", store.accessToken())
        assertEquals(AccountSession(1, null, null, true), withTimeout(WAIT_BUDGET_MS) { next.await() })
        assertEquals(Outcome.Failed(Failure.NotLoggedIn), store.captureSession())
        subscriber.cancelAndJoin()
    }
}
