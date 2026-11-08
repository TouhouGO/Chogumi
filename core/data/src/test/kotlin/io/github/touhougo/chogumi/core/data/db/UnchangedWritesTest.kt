package io.github.touhougo.chogumi.core.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 真实 Room 下，刷新时把同样的内容再写一遍不让表失效：收藏表与逐集状态表被首页、进度页、放送表、统计整表订阅，
 * 每次失效这些查询都要重跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnchangedWritesTest {

    private lateinit var db: ChogumiDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChogumiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun state(episodeId: Int, status: Int) =
        EpisodeWatchStateEntity(episodeId = episodeId, subjectId = 8, accountId = 7, status = status, updatedAt = 1_000)

    private fun collection(subjectId: Int, epStatus: Int) =
        UserCollectionEntity(subjectId = subjectId, subjectType = 2, type = 3, epStatus = epStatus, updatedAt = 1_000, accountId = 7)

    @Test
    fun `逐集状态原样再写一遍不让表失效，变了照常失效`() = runBlocking {
        val dao = db.episodeWatchStateDao()
        dao.replaceForSubject(8, 7, listOf(state(1, 2), state(2, 0)))
        val invalidations = TableInvalidations(db, "episode_watch_states")

        // 首页刷新的灌表、长篇补全的整条替换：服务端给回的是同样的状态（顺序可能不同）
        dao.upsertChanged(listOf(state(2, 0), state(1, 2)))
        dao.replaceForSubject(8, 7, listOf(state(2, 0), state(1, 2)))
        invalidations.fence()
        assertFalse("episode_watch_states" in invalidations, "内容没变的写入不该让逐集状态表失效")

        dao.upsertChanged(listOf(state(2, 2)))
        invalidations.awaitInvalidated("episode_watch_states")
    }

    @Test
    fun `收藏原样再写一遍不让表失效，变了照常失效`() = runBlocking {
        val dao = db.userCollectionDao()
        dao.replaceSnapshot(7, 2, 3, listOf(collection(8, 5), collection(9, 1)))
        val invalidations = TableInvalidations(db, "user_collections")

        // 首页刷新带回的在看状态、游戏 / 音乐在看列表的整份替换：内容没变
        dao.upsertChanged(listOf(collection(8, 5)))
        dao.replaceSnapshot(7, 2, 3, listOf(collection(9, 1), collection(8, 5)))
        invalidations.fence()
        assertFalse("user_collections" in invalidations, "内容没变的写入不该让收藏表失效")

        dao.replaceSnapshot(7, 2, 3, listOf(collection(8, 6), collection(9, 1)))
        invalidations.awaitInvalidated("user_collections")
    }

    private fun episode(id: Int, name: String = "第$id 集", updatedAt: Long = 1_000) =
        EpisodeEntity(id = id, subjectId = 8, type = 0, name = name, nameCn = "", sort = id.toDouble(), updatedAt = updatedAt)

    @Test
    fun `章节原样再取一遍不让表失效但记下新的取回时刻，变了照常失效`() = runBlocking {
        val dao = db.episodeDao()
        dao.replaceAll(8, listOf(episode(1), episode(2)), fetchedAt = 1_000)
        val invalidations = TableInvalidations(db, "episodes")

        // 重新取回的内容相同，只有写入时刻不同
        dao.replaceAll(8, listOf(episode(2, updatedAt = 9_000), episode(1, updatedAt = 9_000)), fetchedAt = 9_000)
        invalidations.fence()
        assertFalse("episodes" in invalidations, "内容没变的取回不该让章节表失效")
        assertEquals(9_000L, dao.fetchedAt(8), "没写正文，取回时刻也要往前走")
        assertEquals(1_000L, dao.getBySubject(8).first().updatedAt)

        dao.replaceAll(8, listOf(episode(1), episode(2, name = "改名")), fetchedAt = 20_000)
        invalidations.awaitInvalidated("episodes")
        assertEquals(20_000L, dao.fetchedAt(8))
    }

    @Test
    fun `子模块正文原样再取一遍不让表失效但记下新的取回时刻，变了照常失效`() = runBlocking {
        val dao = db.subjectSectionDao()
        fun section(json: String, updatedAt: Long) = SubjectSectionEntity(8, "CHARACTERS", 7, json, updatedAt)
        dao.write(section("[1]", updatedAt = 1_000), fetchedAt = 1_000)
        val invalidations = TableInvalidations(db, "subject_sections")

        dao.write(section("[1]", updatedAt = 9_000), fetchedAt = 9_000)
        invalidations.fence()
        assertFalse("subject_sections" in invalidations, "内容没变的取回不该让子模块表失效")
        assertEquals(9_000L, dao.fetchedAt(8, "CHARACTERS", 7), "没写正文，取回时刻也要往前走")
        assertEquals(1_000L, dao.get(8, "CHARACTERS", 7)?.updatedAt)

        dao.write(section("[1,2]", updatedAt = 20_000), fetchedAt = 20_000)
        invalidations.awaitInvalidated("subject_sections")
        assertEquals(20_000L, dao.get(8, "CHARACTERS", 7)?.updatedAt)
    }
}
