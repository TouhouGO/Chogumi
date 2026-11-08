package io.github.touhougo.chogumi.core.data.repository

import java.util.concurrent.atomic.AtomicInteger
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogEntity
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogDao
import io.github.touhougo.chogumi.core.data.db.tag.TagCatalogMetaEntity
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/**
 * 标签目录仓库：按需刷新（TTL / 失败保留旧数据 / 并发去重 / 跨页去重）与查找
 * （大小写 / 转义 / 排序 / 上限 / NSFW 过滤）。
 *
 * 真实 SQLite 语义（`like` 的 `escape` 行为、整份替换的事务性）由
 * `io.github.touhougo.chogumi.core.data.db.tag.TagCatalogDaoTest` 覆盖；
 * 这里用 [FakeTagCatalogDao] 关注仓库自己的编排逻辑，并断言**转义发生在仓库层**
 * （即进入 SQL 前关键词已被处理）。
 */
class TagCatalogRepositoryTest {
    private val engines = RecordedMockEngines()
    @get:org.junit.Rule
    val resources = TestResources()


    private val anime = SubjectType.ANIME

    /** 用给定引擎组装仓库；引擎之外的依赖都由调用方传入以便断言。 */
    private fun repoFor(
        engine: MockEngine,
        dao: TagCatalogDao,
        clock: AppClock,
        nsfwFilter: NsfwFilter = testNsfwFilter(resources),
    ): TagCatalogRepository {
        val client = resources.apiClient(engine)
        return testTagCatalogRepository(NextApi(client), dao, nsfwFilter, clock)
    }

    /** 一页标签的响应体。 */
    private fun pageJson(vararg pairs: Pair<String, Int>, total: Int = 5000): String {
        val items = pairs.joinToString(",") { (name, count) -> """{"name":"$name","count":$count}""" }
        return """{"data":[$items],"total":$total}"""
    }

    /** 恒返回同一页的引擎。 */
    private fun fixedEngine(content: String) = engines.create {
        respond(
            content = content,
            status = HttpStatusCode.OK,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }

    // ---------- 刷新 ----------

    @Test
    fun `读缓存元数据失败返回失败`() = runTest {
        val dao = object : TagCatalogDao by FakeTagCatalogDao() {
            override suspend fun getMeta(type: Int): TagCatalogMetaEntity? = throw IllegalStateException("database")
        }
        val repo = repoFor(fixedEngine(pageJson(total = 0)), dao, TestClock(1_000_000L))
        assertTrue(repo.refreshIfNeeded(anime) is Outcome.Failed)
    }

    @Test
    fun `目录请求取消继续传播`() = runTest {
        val repo = repoFor(engines.create { throw CancellationException("cancel") }, FakeTagCatalogDao(), TestClock(1_000_000L))
        assertFailsWith<CancellationException> { repo.refreshIfNeeded(anime) }
    }

    @Test
    fun `首次失败返回失败且没有写入缓存`() = runTest {
        val dao = FakeTagCatalogDao()
        val repo = repoFor(engines.create { respond("bad request", HttpStatusCode.BadRequest) }, dao, TestClock(1_000_000L))

        val result: Any = repo.refreshIfNeeded(anime)

        assertTrue(result is Outcome.Failed)
        assertNull(dao.getMeta(anime.value))
    }

    @Test
    fun `成功的空目录过期后失败仍是可用缓存`() = runTest {
        val dao = FakeTagCatalogDao()
        val clock = TestClock(1_000_000L)
        dao.upsertMeta(TagCatalogMetaEntity(anime.value, clock.time))
        val repo = repoFor(engines.create { respond("boom", HttpStatusCode.InternalServerError) }, dao, clock)
        clock.time += TagCatalogRepository.REFRESH_TTL_MILLIS

        val result: Any = repo.refreshIfNeeded(anime)

        assertTrue(result is Outcome.Success<*>)
        assertEquals(1_000_000L, dao.getMeta(anime.value)?.refreshedAt)
        assertTrue(dao.rowsOf(anime.value).isEmpty())
    }

    @Test
    fun `无缓存时拉全并按路径参数写入`() = runTest {
        val dao = FakeTagCatalogDao()
        val clock = TestClock(1_000_000L)
        val engine = fixedEngine(pageJson("TV" to 100, "漫画改" to 90, "百合" to 80, total = 3))
        val repo = repoFor(engine, dao, clock)

        repo.refreshIfNeeded(anime)

        assertEquals(3, dao.rowsOf(anime.value).size)
        assertEquals(clock.time, dao.getMeta(anime.value)?.refreshedAt)
        val request = engines.requests(engine).single()
        assertEquals("/p1/channels/2/tags", request.url.encodedPath)
        assertEquals("100", request.url.parameters["limit"])
        assertEquals("0", request.url.parameters["offset"])
    }

    @Test
    fun `未过期不重拉`() = runTest {
        val dao = FakeTagCatalogDao()
        val clock = TestClock(1_000_000L)
        dao.upsertAll(listOf(TagCatalogEntity(anime.value, "TV", 100, 1)))
        dao.upsertMeta(TagCatalogMetaEntity(anime.value, clock.time))
        val engine = fixedEngine(pageJson("TV" to 100))
        val repo = repoFor(engine, dao, clock)

        clock.time += TagCatalogRepository.REFRESH_TTL_MILLIS - 1
        repo.refreshIfNeeded(anime)
        assertEquals(0, engines.requests(engine).count(), "未过期不该发请求")
    }

    @Test
    fun `过期后重拉并替换旧数据`() = runTest {
        val dao = FakeTagCatalogDao()
        val clock = TestClock(1_000_000L)
        dao.upsertAll(listOf(TagCatalogEntity(anime.value, "旧标签", 100, 1)))
        dao.upsertMeta(TagCatalogMetaEntity(anime.value, clock.time))
        val engine = fixedEngine(pageJson("新标签" to 200, total = 1))
        val repo = repoFor(engine, dao, clock)

        clock.time += TagCatalogRepository.REFRESH_TTL_MILLIS
        repo.refreshIfNeeded(anime)

        assertEquals(listOf("新标签"), dao.rowsOf(anime.value).map { it.name })
        assertEquals(clock.time, dao.getMeta(anime.value)?.refreshedAt)
    }

    @Test
    fun `中途失败时旧数据与刷新时间原样保留`() = runTest {
        val dao = FakeTagCatalogDao()
        val clock = TestClock(1_000_000L)
        val oldRefreshAt = clock.time
        dao.upsertAll(listOf(TagCatalogEntity(anime.value, "旧标签", 100, 1)))
        dao.upsertMeta(TagCatalogMetaEntity(anime.value, oldRefreshAt))

        // 前两页成功、第 3 页失败：整份都不得写库。
        val calls = AtomicInteger()
        val engine = engines.create {
            val call = calls.incrementAndGet()
            if (call >= 3) {
                respond("boom", HttpStatusCode.InternalServerError)
            } else {
                respond(
                    content = pageJson("页$call" to call),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }
        val repo = repoFor(engine, dao, clock)

        clock.time += TagCatalogRepository.REFRESH_TTL_MILLIS + 1
        val result = repo.refreshIfNeeded(anime)

        assertEquals(listOf("旧标签"), dao.rowsOf(anime.value).map { it.name }, "旧数据必须原样保留")
        assertEquals(oldRefreshAt, dao.getMeta(anime.value)?.refreshedAt, "失败不得更新刷新时间")
        assertTrue(result is Outcome.Success)
    }

    @Test
    fun `并发两次刷新只发一轮请求`() = runTest {
        val dao = FakeTagCatalogDao()
        val clock = TestClock(1_000_000L)
        val engine = fixedEngine(pageJson("A" to 10, "B" to 9, total = 2))
        val repo = repoFor(engine, dao, clock)

        listOf(
            async { repo.refreshIfNeeded(anime) },
            async { repo.refreshIfNeeded(anime) },
        ).awaitAll()

        assertEquals(1, engines.requests(engine).count(), "持锁后二次检查让第二个调用直接跳过，只发一轮请求")
        assertEquals(2, dao.rowsOf(anime.value).size)
    }

    @Test
    fun `跨页重名只留一条且取首次出现的名次`() = runTest {
        val dao = FakeTagCatalogDao()
        val engine = engines.create { request ->
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            val content = if (offset == 0) {
                pageJson("重复" to 100, "甲" to 90, total = 101)
            } else {
                pageJson("重复" to 99, "乙" to 80, total = 101)
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val repo = repoFor(engine, dao, TestClock(1_000_000L))

        repo.refreshIfNeeded(anime)

        val rows = dao.rowsOf(anime.value)
        assertEquals(3, rows.size, "重名应去重")
        assertEquals(100, rows.single { it.name == "重复" }.count, "保留首次出现的次数")
        assertEquals(1, rows.single { it.name == "重复" }.rank, "名次取首次出现")
    }

    /** 按 offset 回一整页互不重名的标签（名字 = 行号），[failAt] 那一页回 500。 */
    private fun catalogEngine(total: Int, failAt: Int? = null) = engines.create { request ->
        val offset = request.url.parameters["offset"]?.toInt() ?: 0
        if (offset == failAt) {
            respond("boom", HttpStatusCode.InternalServerError)
        } else {
            val tags = (offset + 1..minOf(offset + TagCatalogRepository.PAGE_SIZE, total)).map { "标签$it" to total - it }
            respond(
                content = pageJson(*tags.toTypedArray(), total = total),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
    }

    @Test
    fun `只取前 5000 条，之后的页不请求`() = runTest {
        val dao = FakeTagCatalogDao()
        val engine = catalogEngine(total = 8000)
        val repo = repoFor(engine, dao, TestClock(1_000_000L))

        repo.refreshIfNeeded(anime)

        val offsets = engines.requests(engine).mapNotNull { it.url.parameters["offset"]?.toInt() }.sorted()
        assertEquals((0 until TagCatalogRepository.CATALOG_SIZE step TagCatalogRepository.PAGE_SIZE).toList(), offsets)
        val rows = dao.rowsOf(anime.value)
        assertEquals(TagCatalogRepository.CATALOG_SIZE, rows.size)
        assertEquals("标签5000", rows.single { it.rank == TagCatalogRepository.CATALOG_SIZE }.name, "名次按服务端顺序")
    }

    @Test
    fun `最后一页失败时整份不写库`() = runTest {
        val dao = FakeTagCatalogDao()
        val clock = TestClock(1_000_000L)
        val oldRefreshAt = clock.time
        dao.upsertAll(listOf(TagCatalogEntity(anime.value, "旧标签", 100, 1)))
        dao.upsertMeta(TagCatalogMetaEntity(anime.value, oldRefreshAt))
        val repo = repoFor(catalogEngine(total = 5000, failAt = 4900), dao, clock)

        clock.time += TagCatalogRepository.REFRESH_TTL_MILLIS + 1
        repo.refreshIfNeeded(anime)

        assertEquals(listOf("旧标签"), dao.rowsOf(anime.value).map { it.name }, "前 49 页成功也不得写进半份目录")
        assertEquals(oldRefreshAt, dao.getMeta(anime.value)?.refreshedAt, "失败不得更新刷新时间")
    }

    // ---------- 查找 ----------

    private fun entity(name: String, count: Int, rank: Int, type: Int = 2) =
        TagCatalogEntity(type = type, name = name, count = count, rank = rank)

    /** 只测查找的仓库：引擎恒失败，确保查找不打网。 */
    private suspend fun seededRepo(
        vararg rows: TagCatalogEntity,
        nsfwFilter: NsfwFilter = testNsfwFilter(resources),
    ): Pair<TagCatalogRepository, FakeTagCatalogDao> {
        val dao = FakeTagCatalogDao()
        dao.upsertAll(rows.toList())
        val engine = engines.create { error("查找不访问网络") }
        return repoFor(engine, dao, TestClock(1_000_000L), nsfwFilter) to dao
    }

    @Test
    fun `查找条数上限生效`() = runTest {
        val (repo, _) = seededRepo(
            entity("京都1", 10, 1), entity("京都2", 20, 2),
            entity("京都3", 30, 3), entity("京都4", 40, 4),
        )
        assertEquals(listOf("京都4", "京都3"), repo.search(anime, "京都", limit = 2).map { it.name })
    }

    @Test
    fun `空白关键词返回空且不查库`() = runTest {
        val (repo, dao) = seededRepo(entity("京都", 10, 1))
        assertTrue(repo.search(anime, "   ").isEmpty())
        assertNull(dao.lastSearchKeyword, "空关键词不该走到查库")
    }

    @Test
    fun `通配符在进 SQL 前被转义`() = runTest {
        val (repo, dao) = seededRepo(entity("100%神作", 10, 1))

        repo.search(anime, "100%")
        assertEquals("100\\%", dao.lastSearchKeyword, "百分号必须转义")

        repo.search(anime, "a_b")
        assertEquals("a\\_b", dao.lastSearchKeyword, "下划线必须转义")

        repo.search(anime, "a\\b")
        assertEquals("a\\\\b", dao.lastSearchKeyword, "反斜杠必须转义")
    }

    @Test
    fun `开关打开时成人向词留在结果里`() = runTest {
        val (repo, _) = seededRepo(
            entity("里番", 300, 1),
            entity("番剧", 200, 2),
            nsfwFilter = testNsfwFilter(resources, showNsfw = true),
        )
        assertEquals(listOf("里番", "番剧"), repo.search(anime, "番").map { it.name })
    }

    @Test
    fun `开关关闭时成人向词被剔除`() = runTest {
        val (repo, _) = seededRepo(
            entity("里番", 300, 1),
            entity("番剧", 200, 2),
            nsfwFilter = testNsfwFilter(resources, showNsfw = false),
        )
        assertEquals(listOf("番剧"), repo.search(anime, "番").map { it.name })
    }

    @Test
    fun `开关关闭时歧义标签保留`() = runTest {
        val (repo, _) = seededRepo(
            entity("猎奇", 300, 1),
            entity("鬼畜", 200, 2),
            nsfwFilter = testNsfwFilter(resources, showNsfw = false),
        )
        assertEquals(listOf("猎奇"), repo.search(anime, "猎奇").map { it.name })
        assertEquals(listOf("鬼畜"), repo.search(anime, "鬼畜").map { it.name })
    }
}
