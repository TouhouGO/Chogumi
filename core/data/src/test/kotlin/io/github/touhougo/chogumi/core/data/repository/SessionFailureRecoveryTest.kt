package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.datastore.preferences.core.emptyPreferences
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.search.RecentSearchEntity
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.ExperienceMode
import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.model.SignedInAccount
import org.junit.Assert.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class SessionFailureRecoveryTest {
    @get:Rule val resources = TestResources()

    /** 应用作用域：协程由测试推进，漏出来的异常记在 [escaped]。 */
    private class AppScope {
        val escaped = CopyOnWriteArrayList<Throwable>()
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> escaped += e })

        /** 走完第一次退避（一秒），重新订阅的那一拍也跑完。 */
        fun passFirstBackoff() {
            scheduler.advanceTimeBy(1000)
            scheduler.runCurrent()
        }
    }

    /** 在一个应用作用域里跑 [block]；收尾时取消它并把取消推到底（协程都挂在测试调度器上，不推就结束不了）。 */
    private fun appTest(block: suspend AppScope.() -> Unit) = runBlocking {
        val app = AppScope()
        try {
            app.block()
        } finally {
            app.scope.cancel()
            app.scheduler.runCurrent()
        }
    }

    /** 读得出也会读不出的本机存储：[broken] 期间每次订阅都失败，写入照常。 */
    private class FaultableStore(@Volatile var broken: Boolean) : DataStore<Preferences> {
        val failedReads = AtomicInteger()
        private val stored = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = flow {
            if (broken) { failedReads.incrementAndGet(); throw IOException("本机存储暂时读不出") }
            emitAll(stored)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(stored.value).also { stored.value = it }
    }

    private suspend fun TokenStore.signIn() = completeSession(beginSession("fixture-token"), 7, "fixture_user")

    @Test fun `协调器冷读失败不逸出_恢复后仍跟随会话与标记`() = appTest {
        val disk = FaultableStore(broken = true)
        val token = TokenStore(disk)
        val client = resources.apiClient { error("不应请求网络") }
        val subjects = testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources), token)
        val rows = FakeUserCollectionDao()
        rows.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, accountId = 7, updatedAt = 1L))
        val collections = testCollections(resources, CollectionApi(client), FakeSubjectDao(), rows, token, FakeEpisodeWatchStateDao(), computationDispatcher = dispatcher)
        val orchestrator = collections.progressOrchestrator(subjects)
        val episodes = collections.epTapCoordinator(orchestrator, scope, { 0L }, { awaitCancellation() })
        val marks = collections.markCoordinator(orchestrator, scope, { 0L }, { awaitCancellation() })
        scheduler.runCurrent()
        assertEquals(3, disk.failedReads.get())
        assertTrue("应用作用域不能收到凭证读异常：$escaped", escaped.isEmpty())
        assertTrue(token.captureSession() is io.github.touhougo.chogumi.core.foundation.Outcome.Failed)
        disk.broken = false
        token.signIn()
        passFirstBackoff()
        assertEquals(io.github.touhougo.chogumi.core.model.CollectionType.DOING, marks.marks.value.types[8])
        episodes.advance(io.github.touhougo.chogumi.core.data.progress.ProgressOrigin.HOME,
            io.github.touhougo.chogumi.core.data.progress.ProgressSubject(8, io.github.touhougo.chogumi.core.model.SubjectType.BOOK, emptyList()),
            java.time.LocalDate.of(2026, 11, 11))
        scheduler.runCurrent()
        assertTrue("恢复后的 pending 投影仍接着动作", 8 in episodes.pending.value)
        token.clear()
        scheduler.runCurrent()
        assertTrue(episodes.pending.value.isEmpty())
        assertTrue(marks.marks.value.types.isEmpty())
    }

    @Test fun `凭证冷读失败后恢复_会话跟随仍把被动登出同步到登录状态`() = appTest {
        val disk = FaultableStore(broken = false)
        TokenStore(disk).signIn()
        disk.broken = true
        val token = TokenStore(disk)
        val auth = testAuthRepository(resources, resources.apiClient { error("不应请求网络") }, token, AuthConfig("fixture", "fixture", "chogumi://oauth/callback"), scope)
        scheduler.runCurrent()
        assertEquals(SessionState.InitializationError, auth.sessionState.value)

        disk.broken = false
        passFirstBackoff()
        assertEquals("启动失败只经 ensureInitialized 走出去", SessionState.InitializationError, auth.sessionState.value)
        auth.ensureInitialized()
        assertEquals(SessionState.Authenticated(SignedInAccount(7, "fixture_user")), auth.sessionState.value)

        // 刷新令牌被拒时只清凭证，登录状态靠会话跟随同步
        token.clear()
        scheduler.runCurrent()
        assertEquals(SessionState.Unauthenticated, auth.sessionState.value)
        assertTrue("应用作用域不能收到凭证读异常：$escaped", escaped.isEmpty())
    }

    @Test fun `凭证冷读失败后恢复_登录仍触发一次偏好刷新`() = appTest {
        val disk = FaultableStore(broken = true)
        val token = TokenStore(disk)
        val requests = AtomicInteger()
        val client = resources.apiClient { requests.incrementAndGet(); respond("{}", HttpStatusCode.InternalServerError) }
        val preferences = NsfwPreferenceRepository(FaultableStore(broken = false), PrivacyApi(client), token, testWebSessionRepository(token, noWebClient()))
        NsfwPreferenceSynchronizer(token, preferences, scope)
        scheduler.runCurrent()
        assertEquals(1, disk.failedReads.get())

        disk.broken = false
        passFirstBackoff()
        token.signIn()

        awaitUntil("恢复之后的登录触发 /p1/privacy") { scheduler.runCurrent(); requests.get() >= 1 }
        assertTrue("应用作用域不能收到凭证读异常：$escaped", escaped.isEmpty())
    }

    @Test fun `凭证冷读失败后恢复_历史维护仍清掉不属于当前会话的记录`() = appTest {
        val disk = FaultableStore(broken = true)
        val rows = FakeRecentSearchDao().apply { upsert(RecentSearchEntity(query = "上一个会话搜过的", searchedAt = 1L)) }
        val client = resources.apiClient { error("历史维护不应请求网络") }
        val repository = testSearchRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), rows, testNsfwFilter(resources), TokenStore(disk))
        repository.maintainRecentSearches(scope)
        scheduler.runCurrent()
        assertEquals(1, disk.failedReads.get())

        disk.broken = false
        passFirstBackoff()

        assertEquals(emptySet<String>(), rows.store.keys)
        assertTrue("应用作用域不能收到凭证读异常：$escaped", escaped.isEmpty())
    }

    @Test fun `开关冷读失败后恢复_访问观察仍在登出时清零未读数`() = appTest {
        val disk = FaultableStore(broken = true)
        val token = TokenStore(FaultableStore(broken = false)).apply { signIn() }
        val client = resources.apiClient { request ->
            if (request.url.encodedPath == "/p1/notify") {
                respond("""{"data":[],"total":12}""", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
            } else {
                respond("", HttpStatusCode.NotFound)
            }
        }
        val preferences = PreferencesRepository(disk)
        val repository = NotificationRepository(NextApi(client), token, testWebSessionRepository(token, noWebClient()), preferences, scope)
        scheduler.runCurrent()
        assertEquals(1, disk.failedReads.get())

        disk.broken = false
        preferences.setExperienceMode(ExperienceMode.FULL)
        passFirstBackoff()
        repository.refreshUnreadCount()
        assertEquals(12, repository.unreadCount.value)

        token.clear()
        scheduler.runCurrent()
        assertEquals(0, repository.unreadCount.value)
        assertTrue("应用作用域不能收到开关读异常：$escaped", escaped.isEmpty())
    }

    @Test fun `登录清理写失败仍结束忙态并允许存储恢复后重试`() = runBlocking {
        val real = PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("auth-cleanup"), "tokens.preferences_pb") }
        var failWrites = false
        val controlled = object : DataStore<Preferences> {
            override val data = real.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrites) throw IOException("清理时磁盘满")
                return real.updateData(transform)
            }
        }
        val token = TokenStore(controlled)
        val client = resources.apiClient {
            failWrites = true
            respond("{}", HttpStatusCode.Unauthorized)
        }
        val auth = testAuthRepository(resources, client, token, AuthConfig("fixture", "fixture", "chogumi://oauth/callback"), resources.scope)
        auth.ensureInitialized()
        var escaped: Throwable? = null
        try { auth.loginWithToken("fixture-token") } catch (e: Exception) { escaped = e }
        assertNull("登录清理失败不能裸抛", escaped)
        assertFalse(auth.loginProcessing.value)
        assertEquals(Failure.Storage, auth.loginError.value)
        assertEquals(SessionState.InitializationError, auth.sessionState.value)
        failWrites = false
        auth.ensureInitialized()
        assertEquals(SessionState.Unauthenticated, auth.sessionState.value)
        assertNull(token.accessToken())
    }
}
