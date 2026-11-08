package io.github.touhougo.chogumi.core.data.db.subject

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.db.ChogumiDatabase
import io.github.touhougo.chogumi.core.data.db.TableInvalidations
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectField
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

/**
 * 真实 Room 下的条目合并：内容没变的合并不写库，因而不让 `subjects` 表失效
 * （订阅收藏的查询按表失效重跑，见 [SubjectDao.observeByIds]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubjectDaoMergeTest {

    private lateinit var db: ChogumiDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChogumiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun summary(nameCn: String, at: Long) = SubjectSnapshot(
        SubjectEntity(id = 8, type = 2, name = "Anime", nameCn = nameCn, updatedAt = at),
        setOf(SubjectField.NAME, SubjectField.NAME_CN),
    )

    @Test
    fun `同一天里再合并内容没变的条目不写库，subjects 表不失效`() = runBlocking {
        val dao = db.subjectDao()
        val dayStart = 100L * 24 * 60 * 60 * 1000
        dao.mergeAll(listOf(summary("动画", dayStart)))
        val invalidations = TableInvalidations(db, "subjects")

        // 探索页一个货架、放送表一轮刷新：同一部作品、同样的内容，几小时后又写到一次
        dao.mergeAll(listOf(summary("动画", dayStart + 3_600_000)))
        dao.merge(summary("动画", dayStart + 7_200_000))
        invalidations.fence()
        assertFalse("subjects" in invalidations, "内容没变的合并不该让 subjects 表失效")

        // 对照：内容变了照常写库、照常失效
        dao.mergeAll(listOf(summary("动画（新译名）", dayStart + 10_800_000)))
        invalidations.awaitInvalidated("subjects")
    }
}
