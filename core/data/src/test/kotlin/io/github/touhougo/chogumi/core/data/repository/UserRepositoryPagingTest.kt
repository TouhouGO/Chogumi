package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest

/**
 * 分页契约。
 *
 * 这些用例存在的原因是**数据量可以远超一页**：一个账号可以有许多关注者，
 * 而契约把 `limit` 上限钉死在 100（`maximum: 100`）。
 * 仓库层取一页就返回的话，界面会**静默只显示前 100 条**——没有错误、没有"还有更多"，
 * 用户以为就这么多。静默截断比崩溃更难发现。
 */
class UserRepositoryPagingTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private class FakeUserDao : UserDao {
        val store = mutableMapOf<Int, UserEntity>()
        override suspend fun get(id: Int): UserEntity? = store[id]
        override suspend fun getByUsername(username: String): UserEntity? =
            store.values.find { it.username == username }
        override suspend fun upsert(user: UserEntity) { store[user.id] = user }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterTest
    fun tearDown() { io.github.touhougo.chogumi.core.data.repository.cancelAndAwait(listOf(scope)) }

    private fun newStore(): TokenStore = testTokenStore(scope = scope)

    /**
     * 构造一个"总数 250 条"的假服务端，按 offset 切片返回。
     *
     * [missing] 模拟服务端跳过查不到的用户：该页会**回不满**，但 `total` 仍是
     * 结果集行数（`chiiFriends` 里的关系数），与 v0 收藏同构。
     */
    private fun pagedEngine(
        total: Int = 250,
        missing: Set<Int> = emptySet(),
    ): Pair<HttpClient, ConcurrentRequestRecorder> {
        val calls = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            val limit = request.url.parameters["limit"]?.toIntOrNull() ?: 20
            val offset = request.url.parameters["offset"]?.toIntOrNull() ?: 0
            calls.record(request)
            val slice = (offset until minOf(offset + limit, total))
                .filter { it !in missing }
                .map { index ->
                    """{"id":${index + 1},"username":"u$index","nickname":"用户$index",
                       "avatar":{"small":"s","medium":"m","large":"l"},
                       "group":10,"joinedAt":1600000000,"sign":"","isFriend":false}"""
                }
            respond(
                content = """{"data":[${slice.joinToString(",")}],"total":$total}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        return client to calls
    }

    private fun repo(client: HttpClient) = testUserRepository(resources, UserApi(client), newStore(), FakeUserDao())

    @Test
    fun `分页返回 total 而不是裸列表`() = runTest {
        val (client, _) = pagedEngine(total = 250)
        val result = repo(client).getFollowersPage("fixture_user")
        assertTrue(result is Outcome.Success)
        // total 是**服务端总数**，不是本页条数——没有它就无法判断还有没有下一页
        assertEquals(250, result.value.total)
        assertEquals(100, result.value.items.size)
    }

    @Test
    fun `请求的 limit 不超过契约上限 100`() = runTest {
        // 契约钉死 maximum: 100，超了服务端直接 400
        val (client, calls) = pagedEngine()
        repo(client).getFollowersPage("fixture_user")
        val limit = calls.single().url.parameters["limit"]?.toIntOrNull() ?: 20
        assertTrue(limit <= 100, "limit=$limit 超过契约上限，服务端会拒绝")
    }

    @Test
    fun `offset 透传到服务端`() = runTest {
        val (client, calls) = pagedEngine()
        val result = repo(client).getFollowersPage("fixture_user", offset = 100)
        // 第二页必须从第 100 条开始，且返回的是剩余那一段
        assertEquals(listOf(100 to 100), calls.map { (it.url.parameters["limit"]?.toIntOrNull() ?: 20) to (it.url.parameters["offset"]?.toIntOrNull() ?: 0) })
        assertTrue(result is Outcome.Success)
        assertEquals("u100", result.value.items.first().username)
    }

    @Test
    fun `按 offset 连续取三页覆盖不重不漏`() = runTest {
        val (client, calls) = pagedEngine(total = 250)
        val repository = repo(client)
        val seen = mutableListOf<String>()
        var offset: Int? = 0
        repeat(3) {
            val next = requireNotNull(offset) { "还没取完就没有下一页游标" }
            val page = repository.getFollowersPage("fixture_user", offset = next)
            assertTrue(page is Outcome.Success)
            seen += page.value.items.map { it.username }
            offset = page.value.nextOffset
        }
        assertEquals(null, offset)
        assertEquals(listOf(0, 100, 200), calls.map { it.url.parameters["offset"]!!.toInt() })
        assertEquals((0 until 250).map { "u$it" }, seen)
    }

    @Test
    fun `短页与中间空页仍按返回游标继续取到末页`() = runTest {
        val missing = setOf(0, 1) + (100 until 200)
        val (client, calls) = pagedEngine(total = 250, missing = missing)
        val repository = repo(client)
        val seen = mutableListOf<String>()
        val sizes = mutableListOf<Int>()
        var offset = 0
        repeat(3) { index ->
            val page = repository.getFollowersPage("fixture_user", offset = offset)
            assertTrue(page is Outcome.Success)
            sizes += page.value.items.size
            seen += page.value.items.map { it.username }
            if (index < 2) {
                offset = requireNotNull(page.value.nextOffset) { "短页或空页不能截断后续用户" }
            } else {
                assertEquals(null, page.value.nextOffset)
            }
        }
        assertEquals(listOf(98, 0, 50), sizes)
        assertEquals(listOf(0, 100, 200), calls.map { it.url.parameters["offset"]!!.toInt() })
        assertEquals(((2 until 100) + (200 until 250)).map { "u$it" }, seen)
    }

    @Test
    fun `hasMore 在取满后为假`() = runTest {
        val (client, _) = pagedEngine(total = 250)
        val repository = repo(client)
        val first = repository.getFollowersPage("fixture_user", offset = 0)
        assertTrue(first is Outcome.Success)
        assertTrue(first.value.hasMore, "已取 100 / 共 250，应该还有下一页")

        val last = repository.getFollowersPage("fixture_user", offset = 200)
        assertTrue(last is Outcome.Success)
        assertTrue(!last.value.hasMore, "取满后不应再请求")
    }

    @Test
    fun `好友与关注者各自独立给出总数`() = runTest {
        // 两个方向是同一张表的相反方向，total 也各算各的
        val (client, _) = pagedEngine(total = 42)
        val repository = repo(client)
        val following = repository.getFriendsPage("fixture_user")
        val followers = repository.getFollowersPage("fixture_user")
        assertTrue(following is Outcome.Success && followers is Outcome.Success)
        assertEquals(42, following.value.total)
        assertEquals(42, followers.value.total)
    }

    @Test
    fun `分页结果携带下一页游标而不是让界面自己数`() = runTest {
        // 界面拿到的列表会去重、会因过滤变短，长度不能反推分页位置。
        val (client, _) = pagedEngine(total = 250)
        val result = repo(client).getFollowersPage("fixture_user", offset = 100)
        assertTrue(result is Outcome.Success)
        assertEquals(200, result.value.nextOffset)
        assertEquals(100, result.value.items.size)
    }

    @Test
    fun `服务端跳过的用户不会让游标倒退`() = runTest {
        // 一页回不满（100 条里少了 2 个）时，下一次仍应从 100 开始，而不是 98。
        val (client, _) = pagedEngine(total = 250, missing = setOf(0, 1))
        val result = repo(client).getFollowersPage("fixture_user", offset = 0)
        assertTrue(result is Outcome.Success)
        assertEquals(98, result.value.items.size)
        assertEquals(100, result.value.nextOffset)
    }

    @Test
    fun `整页被跳过时仍给出后续游标`() = runTest {
        // 第 100~199 条全查不到：这一页为空，但 200 之后还有数据，不能就此结束。
        val (client, _) = pagedEngine(total = 250, missing = (100 until 200).toSet())
        val result = repo(client).getFollowersPage("fixture_user", offset = 100)
        assertTrue(result is Outcome.Success)
        assertTrue(result.value.items.isEmpty())
        assertEquals(200, result.value.nextOffset)
    }

    @Test
    fun `末页不再给出后续游标`() = runTest {
        val (client, _) = pagedEngine(total = 250)
        val result = repo(client).getFollowersPage("fixture_user", offset = 200)
        assertTrue(result is Outcome.Success)
        assertEquals(50, result.value.items.size)
        assertEquals(null, result.value.nextOffset)
    }

    @Test
    fun `创建的目录不带 user 时创建者取本地缓存的该用户`() = runTest {
        // 实测 /p1/users/{username}/indexes 每项没有 user；「收藏的」端点有
        val engine = MockEngine {
            respond(
                // 实测返回原样（fixture_user 的第一个目录）
                content = """{"data":[{"id":81881,"uid":1,"type":0,"title":"[Guide] 超合金组件开发指南","private":false,
                    "total":4,"stats":{"subject":{},"groupTopic":4},"createdAt":1757837540,"updatedAt":1757870022}],"total":6}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val dao = FakeUserDao().apply { upsert(UserEntity(id = 1, username = "fixture_user", nickname = "Fixture User", avatarMedium = "m", updatedAt = System.currentTimeMillis())) }
        val result = testUserRepository(resources, UserApi(client), newStore(), dao).getUserIndexesPage("fixture_user")
        assertTrue(result is Outcome.Success, "$result")
        val creator = result.value.items.single().creator
        assertEquals("Fixture User", creator?.nickname)
        assertEquals("fixture_user", creator?.username)
    }

    @Test
    fun `创建的目录读不出本地缓存时不补创建者_这一页照常`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"data":[{"id":81881,"uid":1,"type":0,"title":"t","private":false,
                    "total":4,"stats":{"subject":{},"groupTopic":4},"createdAt":1757837540,"updatedAt":1757870022}],"total":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val brokenCache = object : UserDao by FakeUserDao() {
            override suspend fun getByUsername(username: String): UserEntity? = throw IOException("本地用户表读不出")
        }
        val result = testUserRepository(resources, UserApi(resources.apiClient(engine)), newStore(), brokenCache).getUserIndexesPage("fixture_user")
        assertTrue(result is Outcome.Success, "$result")
        assertEquals(listOf(81881 to null), result.value.items.map { it.id to it.creator })
    }
}
