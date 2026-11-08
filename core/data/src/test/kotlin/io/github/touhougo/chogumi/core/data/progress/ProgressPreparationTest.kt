package io.github.touhougo.chogumi.core.data.progress

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.NsfwFilter
import io.github.touhougo.chogumi.core.data.repository.NsfwPreferenceRepository
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.WebPageApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

/** 本地准备失败只使该次点击失败，应用级处理循环与后续点击仍可继续。 */
class ProgressPreparationTest {
    private val fixtures = mutableListOf<Fixture>()

    @AfterTest
    fun tearDown() {
        fixtures.forEach(Fixture::close)
    }

    private class Fixture {
        private val resources = TestResources()
        private val directory = Files.createTempDirectory("progress-preparation").toFile()
        val escaped = CompletableDeferred<Throwable>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> escaped.complete(error) })
        val tokens = TokenStore(PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("tokens.preferences_pb") })
        val failWatchRead = AtomicBoolean()
        val failCollectionRead = AtomicBoolean()
        val failOptimisticWrite = AtomicBoolean()
        val collectionDao = object : FakeUserCollectionDao() {
            override suspend fun getBySubjectId(subjectId: Int, accountId: Int): UserCollectionEntity? {
                if (failCollectionRead.compareAndSet(true, false)) throw IOException("收藏读取失败")
                return super.getBySubjectId(subjectId, accountId)
            }

            override suspend fun upsert(collection: UserCollectionEntity) {
                if (failOptimisticWrite.compareAndSet(true, false)) throw IOException("乐观写入失败")
                super.upsert(collection)
            }
        }
        private val watchRows = FakeEpisodeWatchStateDao()
        private val watchDao = object : EpisodeWatchStateDao by watchRows {
            override suspend fun getBySubject(subjectId: Int, accountId: Int) =
                if (failWatchRead.compareAndSet(true, false)) throw IOException("逐集读取失败")
                else watchRows.getBySubject(subjectId, accountId)
        }
        val requests = ConcurrentRequestRecorder()
        private val client = testApiClient(MockEngine { request ->
            requests.record(request)
            respond("", HttpStatusCode.NoContent)
        })
        private val webClient = testApiClient { error("书籍进度不访问网页") }
        private val preferences = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("prefs.preferences_pb") }
        val collections = testCollections(resources, CollectionApi(client), FakeSubjectDao(), collectionDao, tokens, watchDao, webClient = webClient)
        private val subjects = testSubjectRepository(
            resources,
            SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(),
            NsfwFilter(NsfwPreferenceRepository(preferences, PrivacyApi(client), tokens, testWebSessionRepository(tokens, webClient))),
            tokenStore = tokens, webPageApi = WebPageApi(webClient),
        )
        val progress = collections.epTapCoordinator(collections.progressOrchestrator(subjects), scope, { 0L }) { }
        val failure = CompletableDeferred<ProgressWriteError>()
        val book = ProgressSubject(SUBJECT, SubjectType.BOOK, emptyList(), totalEps = 10)

        suspend fun initialize() {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.errors.collect { failure.complete(it) } }
            tokens.completeSession(tokens.beginSession("fixture"), ACCOUNT, "fixture")
            collectionDao.upsert(UserCollectionEntity(SUBJECT, SubjectType.BOOK.value, CollectionType.DOING.value, updatedAt = 1L, accountId = ACCOUNT))
        }

        fun close() {
            cancelAndAwait(listOf(scope))
            client.close()
            webClient.close()
            directory.deleteRecursively()
            resources.close()
        }
    }

    private suspend fun assertSurvives(configure: (Fixture) -> Unit) {
        val f = Fixture().also { fixtures += it }
        f.initialize()
        configure(f)
        f.progress.advance(ProgressOrigin.HOME, f.book, TODAY)
        val result = withTimeout(WAIT_BUDGET_MS) {
            select {
                f.failure.onAwait { it.failure }
                f.escaped.onAwait { null }
            }
        }
        assertFalse(f.escaped.isCompleted, "DAO 异常不能逃出应用级协调器")
        assertEquals(Failure.Interrupted, result)
        assertTrue(f.progress.active.value.isEmpty())
        assertTrue(f.progress.pending.value.isEmpty())
        assertFalse(f.collections.holds.isHeld(SUBJECT))
        assertFalse(f.requests.isNotEmpty())

        f.progress.advance(ProgressOrigin.HOME, f.book, TODAY)
        awaitUntil("下一次点击成功写入并收尾") {
            f.collectionDao.store[ACCOUNT to SUBJECT]?.epStatus == 1 && SUBJECT !in f.progress.active.value
        }
        assertEquals(1, f.requests.count())
        assertFalse(f.collections.holds.isHeld(SUBJECT))
        assertFalse(f.escaped.isCompleted)
    }

    @Test
    fun `逐集准备首读失败返回失败 后续点击照常提交`() = runBlocking {
        assertSurvives { it.failWatchRead.set(true) }
    }

    @Test
    fun `收藏准备首读失败返回失败 后续点击照常提交`() = runBlocking {
        assertSurvives { it.failCollectionRead.set(true) }
    }

    @Test
    fun `乐观写入失败释放已经建立的占用 后续点击照常提交`() = runBlocking {
        assertSurvives { it.failOptimisticWrite.set(true) }
    }

    private companion object {
        const val ACCOUNT = 7
        const val SUBJECT = 9
        val TODAY: LocalDate = LocalDate.of(2026, 10, 24)
    }
}
