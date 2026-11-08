package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.db.ChogumiDatabase
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.WebPageApi
import io.github.touhougo.chogumi.core.network.testApiClient
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 逐集状态与收藏计数是一笔本地进度提交，第二张表失败不能留下半套结果。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalProgressTransactionTest {
    @get:Rule
    val resources = TestResources()

    private lateinit var db: ChogumiDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = Files.createTempDirectory("local-progress").toFile()
    private val client = testApiClient { error("本地提交不能发网络") }
    private val tokens = TokenStore(PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("tokens.preferences_pb") })
    private lateinit var collections: TestCollections
    private lateinit var orchestrator: ProgressOrchestrator
    @Volatile private var cancelLocalCommit = false
    private val episodes = (1..3).map { EpisodeBo(it, 8, 0, "正片", "", it.toDouble()) }

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChogumiDatabase::class.java)
            .allowMainThreadQueries().build()
        tokens.completeSession(tokens.beginSession("fixture"), 7, "fixture")
        val transactions = object : TransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction {
                val value = block()
                if (cancelLocalCommit) throw CancellationException("取消本地提交")
                value
            }
        }
        val clock = TestClock(1_000L)
        val preferences = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("prefs.preferences_pb") }
        collections = testCollections(
            resources,
            CollectionApi(client), db.subjectDao(), db.userCollectionDao(), tokens, db.episodeWatchStateDao(),
            episodeDao = db.episodeDao(), transactions = transactions, clock = clock, webClient = client,
        )
        val subjects = testSubjectRepository(
            resources,
            SubjectApi(client), NextApi(client), db.subjectDao(), db.episodeDao(), db.subjectSectionDao(),
            NsfwFilter(NsfwPreferenceRepository(preferences, PrivacyApi(client), tokens, testWebSessionRepository(tokens, client))),
            tokenStore = tokens, clock = clock, webPageApi = WebPageApi(client),
        )
        orchestrator = collections.progressOrchestrator(subjects)
        db.userCollectionDao().upsert(UserCollectionEntity(8, 2, 3, updatedAt = 1L, accountId = 7))
        db.episodeDao().upsertAll(episodes.map { EpisodeEntity(it.id, 8, it.type, it.name, "", it.sort) })
    }

    @After
    fun tearDown() {
        cancelAndAwait(listOf(scope))
        client.close()
        db.close()
        directory.deleteRecursively()
    }

    @Test
    fun `收藏写入被数据库拒绝时逐集状态回滚`() = runBlocking {
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER refuse_progress BEFORE UPDATE ON user_collections
            WHEN NEW.subjectId = 8 BEGIN SELECT RAISE(ABORT, '拒绝写入'); END
        """.trimIndent())
        val result = orchestrator.writeLocal(8, episodes, mapOf(1 to EpisodeStatus.DONE), tokens.session())
        assertIs<Outcome.Failed>(result)
        assertTrue(db.episodeWatchStateDao().getBySubject(8, 7).isEmpty(), "收藏写失败不能留下已提交的逐集行")
        assertEquals(0, db.userCollectionDao().getBySubjectId(8, 7)?.epStatus)
    }

    @Test
    fun `本地事务取消时两张表均回滚`() = runBlocking {
        cancelLocalCommit = true
        assertFailsWith<CancellationException> {
            orchestrator.writeLocal(8, episodes, mapOf(1 to EpisodeStatus.DONE), tokens.session())
        }
        assertTrue(db.episodeWatchStateDao().getBySubject(8, 7).isEmpty())
        assertEquals(0, db.userCollectionDao().getBySubjectId(8, 7)?.epStatus)
    }

    @Test
    fun `换号后旧本地写不会触碰任一账号表`() = runBlocking {
        val session = tokens.session()
        tokens.completeSession(tokens.beginSession("other"), 70, "other")
        assertEquals(Outcome.Failed(Failure.SessionChanged),
            orchestrator.writeLocal(8, episodes, mapOf(1 to EpisodeStatus.DONE), session))
        assertTrue(db.episodeWatchStateDao().getBySubject(8, 7).isEmpty())
        assertTrue(db.episodeWatchStateDao().getBySubject(8, 70).isEmpty())
        assertEquals(0, db.userCollectionDao().getBySubjectId(8, 7)?.epStatus)
    }

    @Test
    fun `正片想看抛弃保持原标记 特别篇已看计入唯一收藏计数`() = runBlocking {
        db.episodeWatchStateDao().upsertAll(listOf(
            EpisodeWatchStateEntity(1, 8, 7, EpisodeStatus.DONE.value, 1L),
            EpisodeWatchStateEntity(2, 8, 7, EpisodeStatus.WISH.value, 1L),
            EpisodeWatchStateEntity(3, 8, 7, EpisodeStatus.DROPPED.value, 1L),
            EpisodeWatchStateEntity(4, 8, 7, EpisodeStatus.DONE.value, 1L),
        ))
        val target = (
            orchestrator.targetForWatchedUpTo(
                episodes,
                mapOf(1 to EpisodeStatus.DONE, 2 to EpisodeStatus.WISH, 3 to EpisodeStatus.DROPPED),
                EpisodeProgressTarget.Episode(1, 1.0),
            ) as Outcome.Success
        ).value
        assertEquals(Outcome.Success(Unit), orchestrator.writeLocal(8, episodes, target, tokens.session()))
        assertEquals(2, db.userCollectionDao().getBySubjectId(8, 7)?.epStatus)
        assertTrue(8 in collections.watchStates.watchHistoryLedger(listOf(8), tokens.session()).current)
        assertEquals(EpisodeStatus.WISH.value, db.episodeWatchStateDao().getBySubject(8, 7).first { it.episodeId == 2 }.status)
        assertEquals(EpisodeStatus.DROPPED.value, db.episodeWatchStateDao().getBySubject(8, 7).first { it.episodeId == 3 }.status)
    }

    @Test
    fun `单集特别篇切换同步更新收藏已看记录数`() = runBlocking {
        db.episodeWatchStateDao().upsertAll(listOf(EpisodeWatchStateEntity(1, 8, 7, EpisodeStatus.DONE.value, 1L)))
        assertEquals(Outcome.Success(Unit), collections.watchStates.syncSingleEpisodeWatchState(8, 4, EpisodeStatus.DONE, tokens.session()))
        assertEquals(2, db.userCollectionDao().getBySubjectId(8, 7)?.epStatus)
        assertEquals(Outcome.Success(Unit), collections.watchStates.syncSingleEpisodeWatchState(8, 4, EpisodeStatus.NONE, tokens.session()))
        assertEquals(1, db.userCollectionDao().getBySubjectId(8, 7)?.epStatus)
    }
}
