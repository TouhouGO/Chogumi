package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.subject.EPISODES_STAMP
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.FetchStampEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionProgress
import io.github.touhougo.chogumi.core.model.CollectionSaveResult
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.WebPageApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 一张收藏表单从等待条目锁到记录与进度收尾都属于发起时的账号。 */
class CollectionSaveSessionTest {
    private val fixtures = mutableListOf<Fixture>()

    @AfterTest
    fun tearDown() {
        fixtures.forEach(Fixture::close)
    }

    private class Fixture {
        private val resources = TestResources()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val directory = Files.createTempDirectory("collection-save-session").toFile()
        private val storedTokens = PreferenceDataStoreFactory.create(scope = scope) {
            directory.resolve("tokens.preferences_pb")
        }
        var beforeTokenRead: suspend () -> Unit = {}
        val tokens = TokenStore(object : DataStore<Preferences> {
            override val data = flow {
                beforeTokenRead()
                emitAll(storedTokens.data)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                storedTokens.updateData(transform)
        })
        val requests = ConcurrentRequestRecorder()
        val recordReached = CompletableDeferred<Unit>()
        var pauseAfterRecord = false
        var allowRecord = CompletableDeferred<Unit>()
        private val client = testApiClient(MockEngine { request ->
            requests.record(request)
            if (request.method == HttpMethod.Get) {
                respond(
                    """{"total":1,"limit":100,"offset":0,"data":[{"episode":{"id":101,"type":0,"sort":1.0,"name":"第一集","name_cn":"","duration":"","airdate":"","comment":0,"desc":"","disc":0},"type":0,"updated_at":0}]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        })
        private val webClient = testApiClient { error("保存不访问网页") }
        private val subjectDao = FakeSubjectDao()
        private val episodeDao = FakeEpisodeDao()
        val collectionDao = object : FakeUserCollectionDao() {
            override suspend fun upsert(collection: UserCollectionEntity) {
                super.upsert(collection)
                if (pauseAfterRecord && collection.rate == 8) {
                    recordReached.complete(Unit)
                    allowRecord.await()
                }
            }
        }
        val watchDao = FakeEpisodeWatchStateDao()
        private val clock = TestClock(10_000L)
        private val collections = testCollections(
            resources,
            CollectionApi(client), subjectDao, collectionDao, tokens, watchDao,
            episodeDao = episodeDao, clock = clock, webClient = webClient,
        )
        private val preferences = PreferenceDataStoreFactory.create(scope = scope) {
            directory.resolve("prefs.preferences_pb")
        }
        private val subjectRepository = testSubjectRepository(
            resources,
            SubjectApi(client), NextApi(client), subjectDao, episodeDao, FakeSubjectSectionDao(),
            NsfwFilter(NsfwPreferenceRepository(
                preferences, PrivacyApi(client), tokens, testWebSessionRepository(tokens, webClient),
            )),
            tokenStore = tokens, clock = clock, webPageApi = WebPageApi(webClient),
        )
        val orchestrator = collections.progressOrchestrator(subjectRepository)
        val editing = collections.editing(subjectRepository, orchestrator)
        private val episode = EpisodeBo(101, SUBJECT, 0, "第一集", "", 1.0)
        val work = CollectionWork(
            SubjectBo(SUBJECT, SubjectType.ANIME, "作品", ""),
            UserCollectionBo(SUBJECT, SubjectType.ANIME, CollectionType.DOING),
            Load.Ready(listOf(episode)), emptyMap(),
        )
        val edits = CollectionEdits(
            rate = 8,
            progress = CollectionProgress.Episodes(io.github.touhougo.chogumi.core.model.EpisodeProgressTarget.Episode(101, 1.0)),
            finishAll = false,
        )

        init {
            runBlocking {
                signIn(ACCOUNT)
                subjectDao.upsert(SubjectEntity(SUBJECT, SubjectType.ANIME.value, "作品", "", updatedAt = clock.time))
                episodeDao.upsertAll(listOf(EpisodeEntity(101, SUBJECT, 0, "第一集", "", 1.0)))
                episodeDao.putStamp(FetchStampEntity(SUBJECT, EPISODES_STAMP, 0, clock.time))
                for (account in listOf(ACCOUNT, OTHER_ACCOUNT)) {
                    collectionDao.upsert(UserCollectionEntity(
                        subjectId = SUBJECT, subjectType = SubjectType.ANIME.value,
                        type = CollectionType.DOING.value, accountId = account, updatedAt = 1L,
                    ))
                }
            }
        }

        suspend fun signIn(account: Int) {
            tokens.completeSession(tokens.beginSession("fixture-$account"), account, "fixture_$account")
        }

        fun close() {
            allowRecord.complete(Unit)
            cancelAndAwait(listOf(scope))
            client.close()
            webClient.close()
            directory.deleteRecursively()
            resources.close()
        }
    }

    private fun fixture() = Fixture().also { fixtures += it }

    @Test
    fun `同一账号保存记录和进度均使用发起身份`() = runBlocking {
        val f = fixture()
        assertEquals(CollectionSaveResult.Saved, f.editing.save(f.work, f.edits))
        assertEquals(8, f.collectionDao.store[ACCOUNT to SUBJECT]?.rate)
        assertEquals(1, f.collectionDao.store[ACCOUNT to SUBJECT]?.epStatus)
        assertEquals(2, f.requests.count { it.method == HttpMethod.Patch })
        assertTrue(f.requests.all { it.headers["Authorization"] == "Bearer fixture-$ACCOUNT" })
        assertEquals(0, f.collectionDao.store[OTHER_ACCOUNT to SUBJECT]?.rate)
    }

    @Test
    fun `保存等待条目锁时换号 不给新账号保存记录或进度`() = runBlocking {
        val f = fixture()
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holding = launch(start = CoroutineStart.UNDISPATCHED) {
            f.orchestrator.withSubjectLock(SUBJECT) {
                locked.complete(Unit)
                release.await()
            }
        }
        locked.await()
        val saved = async(start = CoroutineStart.UNDISPATCHED) { f.editing.save(f.work, f.edits) }
        f.signIn(OTHER_ACCOUNT)
        release.complete(Unit)
        val result = withTimeout(WAIT_BUDGET_MS) { saved.await() }
        holding.join()
        assertEquals(CollectionSaveResult.Failed(CollectionSaveResult.Step.RECORD, Failure.SessionChanged), result)
        assertFalse(f.requests.isNotEmpty(), "旧保存不能改用新账号发出网络请求")
        assertEquals(0, f.collectionDao.store[OTHER_ACCOUNT to SUBJECT]?.rate)
        assertEquals(0, f.collectionDao.store[OTHER_ACCOUNT to SUBJECT]?.epStatus)
        assertTrue(f.watchDao.store.isEmpty())
    }

    @Test
    fun `记录保存完、进度那一步读令牌途中换号 后半步拒绝旧会话`() = runBlocking {
        val f = fixture()
        f.pauseAfterRecord = true
        val saved = async { f.editing.save(f.work, f.edits) }
        withTimeout(WAIT_BUDGET_MS) { f.recordReached.await() }
        val reading = CompletableDeferred<Unit>()
        val resumeRead = CompletableDeferred<Unit>()
        f.beforeTokenRead = {
            reading.complete(Unit)
            resumeRead.await()
        }
        f.allowRecord.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { reading.await() }
        f.beforeTokenRead = {}
        f.signIn(OTHER_ACCOUNT)
        resumeRead.complete(Unit)
        val result = withTimeout(WAIT_BUDGET_MS) { saved.await() }
        assertEquals(CollectionSaveResult.Failed(CollectionSaveResult.Step.PROGRESS, Failure.SessionChanged), result)
        assertTrue(f.requests.all { it.headers["Authorization"] == "Bearer fixture-$ACCOUNT" }, "保存后半步不能向新账号发出请求")
        assertEquals(0, f.collectionDao.store[OTHER_ACCOUNT to SUBJECT]?.rate)
        assertEquals(0, f.collectionDao.store[OTHER_ACCOUNT to SUBJECT]?.epStatus)
        assertTrue(f.watchDao.store.keys.none { it.first == OTHER_ACCOUNT })
    }

    private companion object {
        const val ACCOUNT = 7
        const val OTHER_ACCOUNT = 70
        const val SUBJECT = 8
    }
}
