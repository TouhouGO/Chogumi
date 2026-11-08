package io.github.touhougo.chogumi.core.data.db.search

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.db.ChogumiDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** 真实 Room/SQLite 下的最近搜索：记一个词时只留最近几个。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentSearchDaoTest {

    private lateinit var db: ChogumiDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChogumiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `记下新词时只留最近的几个_再搜一次的旧词算最近`() = runBlocking {
        val dao = db.recentSearchDao()
        for (i in 1..4) dao.record(RecentSearchEntity("词$i", searchedAt = i.toLong()), keep = 3)
        dao.record(RecentSearchEntity("词2", searchedAt = 5L), keep = 3)

        assertEquals(listOf("词2", "词4", "词3"), dao.latest(10).first())
    }
}
