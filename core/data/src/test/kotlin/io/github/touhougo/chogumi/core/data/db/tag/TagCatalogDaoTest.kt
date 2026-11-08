package io.github.touhougo.chogumi.core.data.db.tag

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.data.db.ChogumiDatabase
import io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真实 Room/SQLite 下的 DAO 行为：**`like` 通配符转义**与排序/上限/整份替换。
 *
 * 必须走真实 SQLite —— 手写 FakeDao 里"转义"只是我自己写的一个字符串替换，
 * 拿它来测等于自证；只有真实 `like ... escape '\'` 才能证明 `%`/`_` 被当字面量。
 *
 * 传给 [TagCatalogDao.search] 的关键词已经过 `TagCatalogRepository.escapeLike` 转义；
 * 这里验证转义后的 `%`、`_` 与反斜杠在真实 SQLite 中按字面量匹配。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagCatalogDaoTest {

    private lateinit var db: ChogumiDatabase
    private val dao get() = db.tagCatalogDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChogumiDatabase::class.java,
        )
            // 仅供 [namesOf] 的原始 SQL 读表：Robolectric 下测试线程即主线程。
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(vararg rows: Triple<String, Int, Int>) = dao.upsertAll(
        rows.map { (name, count, rank) -> TagCatalogEntity(type = ANIME, name = name, count = count, rank = rank) },
    )

    @Test
    fun `查找命中子串且按使用次数倒序`() = runBlocking {
        seed(
            Triple("京阿尼", 100, 1),
            Triple("京都动画", 300, 2),
            Triple("京都アニメーション", 200, 3),
            Triple("MAPPA", 999, 4),
        )
        val result = dao.search(ANIME, escapeLike("京都"), limit = 10)
        assertEquals(listOf("京都动画", "京都アニメーション"), result.map { it.name })
    }

    @Test
    fun `种类不同不互相串`() = runBlocking {
        dao.upsertAll(
            listOf(
                TagCatalogEntity(BOOK, "京都", 10, 1),
                TagCatalogEntity(ANIME, "京都", 20, 1),
            ),
        )
        assertEquals(1, dao.search(ANIME, escapeLike("京都"), 10).size)
        assertEquals(BOOK, dao.search(BOOK, escapeLike("京都"), 10).single().type)
    }

    @Test
    fun `条数上限生效`() = runBlocking {
        seed(
            Triple("京都1", 10, 1), Triple("京都2", 20, 2),
            Triple("京都3", 30, 3), Triple("京都4", 40, 4),
        )
        assertEquals(2, dao.search(ANIME, escapeLike("京都"), limit = 2).size)
    }

    @Test
    fun `下划线被当字面量而非通配符`() = runBlocking {
        seed(Triple("abc", 10, 1), Triple("a_c", 20, 2), Triple("aXc", 30, 3))
        // 不转义时 `_` 匹配任意单字符，会把三条全捞出来。
        assertEquals(listOf("a_c"), dao.search(ANIME, escapeLike("a_c"), 10).map { it.name })
    }

    @Test
    fun `拉丁字母查找不分大小写`() = runBlocking {
        seed(Triple("KyoAni", 10, 1))
        assertEquals(listOf("KyoAni"), dao.search(ANIME, escapeLike("kyoani"), 10).map { it.name })
    }

    @Test
    fun `百分号被当字面量而非通配符`() = runBlocking {
        seed(Triple("100%神作", 10, 1), Triple("100分神作", 20, 2))
        assertEquals(listOf("100%神作"), dao.search(ANIME, escapeLike("100%"), 10).map { it.name })
    }

    @Test
    fun `反斜杠本身也被当字面量`() = runBlocking {
        seed(Triple("a\\b", 10, 1), Triple("aXb", 20, 2))
        assertEquals(listOf("a\\b"), dao.search(ANIME, escapeLike("a\\b"), 10).map { it.name })
    }

    @Test
    fun `整份替换后旧数据被清掉且刷新时间落库`() = runBlocking {
        seed(Triple("旧标签", 1, 1))
        dao.replaceForType(
            type = ANIME,
            rows = listOf(TagCatalogEntity(ANIME, "新标签", 5, 1)),
            refreshedAt = 1234L,
        )
        assertEquals(listOf("新标签"), namesOf(ANIME))
        assertEquals(1234L, dao.getMeta(ANIME)?.refreshedAt)
        assertTrue(namesOf(BOOK).isEmpty(), "替换不该影响别的类型")
    }

    private fun escapeLike(keyword: String) = TagCatalogRepository.escapeLike(keyword)

    /** 用原始 SQL 按名次读出该类型的全部标签名（生产 DAO 无此读取，不为测试占用接口）。 */
    private fun namesOf(type: Int): List<String> =
        db.query("select name from tag_catalog where type = ? order by rank", arrayOf(type)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private companion object {
        const val ANIME = 2
        const val BOOK = 1
    }
}
