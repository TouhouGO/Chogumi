package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.network.AuthConfig
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.datastore.preferences.core.emptyPreferences
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.SessionState
import org.junit.Assert.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
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

    @Test fun `协调器冷读失败不逸出_恢复后仍跟随会话与标记`() = runBlocking {
        val failures = CopyOnWriteArrayList<Throwable>()
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val scope = resources.own(CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> failures += e }))
        var broken = true
        var failedReads = 0
        val stored = MutableStateFlow<Preferences>(emptyPreferences())
        val disk = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (broken) { failedReads++; throw IOException("凭证暂时读不出") }
                stored.collect { emit(it) }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(stored.value).also { stored.value = it }
        }
        val token = TokenStore(disk)
        val client = resources.apiClient { error("不应请求网络") }
        val subjects = testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources), token)
        val rows = FakeUserCollectionDao()
        rows.upsert(UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, accountId = 7, updatedAt = 1L))
        val collections = testCollections(resources, CollectionApi(client), FakeSubjectDao(), rows, token, FakeEpisodeWatchStateDao(), computationDispatcher = dispatcher)
        val orchestrator = collections.progressOrchestrator(subjects)
        val episodes = collections.epTapCoordinator(orchestrator, scope, { 0L }, { awaitCancellation() })
        val marks = collections.markCoordinator(orchestrator, scope, { 0L }, { awaitCancellation() })
        try {
            scheduler.runCurrent()
            assertEquals(3, failedReads)
            assertTrue("应用作用域不能收到凭证读异常：$failures", failures.isEmpty())
            assertTrue(token.captureSession() is io.github.touhougo.chogumi.core.foundation.Outcome.Failed)
            broken = false
            token.completeSession(token.beginSession("fixture-token"), 7, "fixture_user")
            scheduler.advanceTimeBy(1000)
            scheduler.runCurrent()
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
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            scheduler.runCurrent()
        }
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
