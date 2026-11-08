package io.github.touhougo.chogumi.core.data.db.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.db.ChogumiDatabase
import io.github.touhougo.chogumi.core.data.db.calendar.CalendarEntity
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressEntity
import io.github.touhougo.chogumi.core.data.db.home.HomeProgressMetaEntity
import io.github.touhougo.chogumi.core.data.db.search.SearchFirstPageEntity
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSection
import io.github.touhougo.chogumi.core.data.db.subject.SubjectSectionEntity
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.repository.InMemorySyncPreferenceStore
import io.github.touhougo.chogumi.core.data.repository.PassthroughTransactions
import io.github.touhougo.chogumi.core.data.repository.seed
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 真实 Room/SQLite 下的启动清理：验证清理用的 SQL（引用保护的 union、按主键删除、级联删章节与子模块）
 * 与 [cacheEvictionVictims] 接在一起的效果。规则本身的边界见 [CacheEvictionTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalCacheJanitorTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private lateinit var db: ChogumiDatabase
    private val now = 100L * DAY

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChogumiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun janitor(
        currentAccount: Int? = null,
        synced: Map<Int, Long> = emptyMap(),
        scope: CoroutineScope = TestScope(),
        dao: CacheEvictionDao = db.cacheEvictionDao(),
    ) = LocalCacheJanitor(
        dao, PassthroughTransactions, { now },
        tokenStore = testTokenStore(resources, userId = currentAccount),
        syncPreferenceStore = InMemorySyncPreferenceStore(synced),
        scope = scope,
    )

    @Test
    fun `启动时的清理失败不向外抛`() {
        val errors = mutableListOf<Throwable>()
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Unconfined + job + CoroutineExceptionHandler { _, e -> errors += e })
        val broken = object : CacheEvictionDao by db.cacheEvictionDao() {
            override suspend fun accountsWithData(): List<Int> = throw IllegalStateException("库读不出来")
        }

        janitor(scope = scope, dao = broken)
        runBlocking { job.children.toList().joinAll() }

        assertEquals(emptyList(), errors)
    }

    private fun subject(id: Int, touchedAt: Long) = SubjectEntity(id = id, type = 2, name = "s$id", nameCn = "", touchedAt = touchedAt, updatedAt = System.currentTimeMillis())

    @Test
    fun `过期且无人引用的条目连同章节与子模块一起删除，收藏与放送引用的保留`() = runBlocking {
        val old = now - 31 * DAY
        db.subjectDao().upsertAll(listOf(subject(1, old), subject(2, old), subject(3, old), subject(4, now - DAY)))
        db.userCollectionDao().upsertAll(listOf(UserCollectionEntity(subjectId = 2, subjectType = 2, type = 3, accountId = 7, updatedAt = System.currentTimeMillis())))
        db.calendarDao().upsertAll(listOf(CalendarEntity(weekday = 1, subjectId = 3, name = "s3")))
        db.episodeDao().upsertAll(listOf(EpisodeEntity(id = 10, subjectId = 1, type = 0, name = "e", nameCn = "", sort = 1.0)))
        db.subjectSectionDao().seed(SubjectSectionEntity(1, SubjectSection.PERSONS.name, 0, "[]", updatedAt = now))
        db.subjectSectionDao().seed(SubjectSectionEntity(4, SubjectSection.PERSONS.name, 0, "[]", updatedAt = old))

        janitor().evict()

        assertNull(db.subjectDao().get(1), "过期且无人引用的条目应删除")
        assertNotNull(db.subjectDao().get(2), "收藏里的条目永远保留")
        assertNotNull(db.subjectDao().get(3), "放送表引用的条目保留")
        assertNotNull(db.subjectDao().get(4), "未过期的条目保留")
        assertEquals(emptyList(), db.episodeDao().getBySubject(1), "被删条目的章节一并删除")
        assertNull(db.subjectSectionDao().get(1, SubjectSection.PERSONS.name, 0), "被删条目的子模块一并删除")
        assertNull(db.subjectSectionDao().get(4, SubjectSection.PERSONS.name, 0), "过期的子模块删除")
    }

    @Test
    fun `过期的搜索第一页删除，新近取过的保留`() = runBlocking<Unit> {
        db.searchFirstPageDao().upsert(SearchFirstPageEntity("old", "[1]", total = 1, nextOffset = null, updatedAt = now - 8 * DAY))
        db.searchFirstPageDao().upsert(SearchFirstPageEntity("recent", "[1]", total = 1, nextOffset = null, updatedAt = now - DAY))

        janitor().evict()

        assertNull(db.searchFirstPageDao().get("old"), "7 天没再取过的第一页应删除")
        assertNotNull(db.searchFirstPageDao().get("recent"), "新近取过的第一页保留")
    }

    @Test
    fun `收藏作品在收藏它的账号视角下的阵容不按 30 天删，别的模块与没收藏的照删`() = runBlocking {
        val old = now - 31 * DAY
        db.subjectDao().upsertAll(listOf(subject(1, now), subject(2, now)))
        db.userCollectionDao().upsertAll(listOf(UserCollectionEntity(subjectId = 1, subjectType = 2, type = 2, accountId = 7, updatedAt = 1L)))
        listOf(
            SubjectSectionEntity(1, SubjectSection.CHARACTERS.name, 7, "[]", updatedAt = old),
            SubjectSectionEntity(1, SubjectSection.PERSONS.name, 7, "[]", updatedAt = old),
            SubjectSectionEntity(1, SubjectSection.RELATIONS.name, 7, "[]", updatedAt = old),
            SubjectSectionEntity(1, SubjectSection.CHARACTERS.name, 0, "[]", updatedAt = old),
            SubjectSectionEntity(2, SubjectSection.CHARACTERS.name, 7, "[]", updatedAt = old),
        ).forEach { db.subjectSectionDao().seed(it) }

        janitor(currentAccount = 7).evict()

        assertNotNull(db.subjectSectionDao().get(1, SubjectSection.CHARACTERS.name, 7), "收藏作品的角色留着")
        assertNotNull(db.subjectSectionDao().get(1, SubjectSection.PERSONS.name, 7), "收藏作品的制作人员留着")
        assertNull(db.subjectSectionDao().get(1, SubjectSection.RELATIONS.name, 7), "不算阵容的模块照删")
        assertNull(db.subjectSectionDao().get(1, SubjectSection.CHARACTERS.name, 0), "别的视角的阵容照删")
        assertNull(db.subjectSectionDao().get(2, SubjectSection.CHARACTERS.name, 7), "没收藏的作品照删")
    }

    @Test
    fun `子模块按多久没取回判断过期，正文多久没变不算`() = runBlocking {
        val old = now - 31 * DAY
        val dao = db.subjectSectionDao()
        db.subjectDao().upsertAll(listOf(subject(1, now)))
        // 正文是 31 天前写的，但昨天又取回过（内容没变）；另一个 31 天没取回过
        dao.write(SubjectSectionEntity(1, SubjectSection.RELATIONS.name, 0, "[]", updatedAt = old), fetchedAt = now - DAY)
        dao.write(SubjectSectionEntity(1, SubjectSection.RECS.name, 0, "[]", updatedAt = old), fetchedAt = old)

        janitor().evict()

        assertNotNull(dao.get(1, SubjectSection.RELATIONS.name, 0), "昨天取回过的模块不该因正文旧而被删")
        assertNull(dao.get(1, SubjectSection.RECS.name, 0), "31 天没取回过的模块删除")
        assertNull(dao.fetchedAt(1, SubjectSection.RECS, 0), "删掉模块时取回时刻一并删")
    }

    @Test
    fun `被删条目的章节取回时刻一并删`() = runBlocking {
        val old = now - 31 * DAY
        db.subjectDao().upsertAll(listOf(subject(1, old)))
        db.episodeDao().replaceAll(1, listOf(EpisodeEntity(id = 10, subjectId = 1, type = 0, name = "e", nameCn = "", sort = 1.0)), fetchedAt = old)

        janitor().evict()

        assertNull(db.episodeDao().fetchedAt(1), "条目删了，它的章节取回时刻不该留着")
    }

    @Test
    fun `30 天没再看过的用户资料删除`() = runBlocking<Unit> {
        db.userDao().upsert(UserEntity(id = 1, username = "old", nickname = "old", updatedAt = now - 31 * DAY))
        db.userDao().upsert(UserEntity(id = 2, username = "recent", nickname = "recent", updatedAt = now - DAY))

        janitor().evict()

        assertNull(db.userDao().get(1))
        assertNotNull(db.userDao().get(2))
    }

    @Test
    fun `90 天没在本机用过的旧账号清掉分份数据，当前账号、近来用过与不知道何时用过的留着`() = runBlocking<Unit> {
        // 8 是当前账号；9 上次同步在 91 天前；10 同步早但首页近来刷新过；11 不知道何时用过
        for (account in 8..11) {
            db.userCollectionDao().upsertAll(listOf(UserCollectionEntity(subjectId = account, subjectType = 2, type = 3, accountId = account, updatedAt = 1L)))
            db.episodeWatchStateDao().upsertAll(listOf(EpisodeWatchStateEntity(episodeId = account, subjectId = account, accountId = account, status = 2)))
            db.homeProgressDao().upsertAll(listOf(HomeProgressEntity(subjectId = account, accountId = account, subjectType = 2)))
        }
        db.homeProgressDao().upsertMeta(HomeProgressMetaEntity(accountId = 10, updatedAt = now - DAY))
        val longAgo = now - 91 * DAY

        janitor(currentAccount = 8, synced = mapOf(8 to longAgo, 9 to longAgo, 10 to longAgo)).evict()

        val left = (8..11).filter { db.userCollectionDao().getBySubjectId(it, it) != null }
        assertEquals(listOf(8, 10, 11), left)
        assertEquals(emptyList(), db.episodeWatchStateDao().getBySubject(9, 9))
        assertEquals(emptyList(), db.homeProgressDao().getByAccount(9))
        assertEquals(1, db.homeProgressDao().getByAccount(10).size)
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
