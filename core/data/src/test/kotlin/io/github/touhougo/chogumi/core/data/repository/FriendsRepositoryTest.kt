package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest

/** 自己的好友名单：以服务端为准、逐页读全、按账号缓存，缓存过期才在后台重读。 */
class FriendsRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()

    /** 后台重读跑在这里：用例等它的子协程全部结束，再断言发没发请求。 */
    private val background = resources.own(CoroutineScope(Dispatchers.IO + SupervisorJob()))
    private val clock = TestClock(time = 10_000_000L)
    private val pageRequests = AtomicInteger()

    /** fixture_user 关注了 [count] 个人（id 1..count），服务端每页最多 100 条。 */
    @Volatile
    private var count = 150

    private val api = UserApi(resources.apiClient { request ->
        if (request.url.encodedPath != "/p1/users/fixture_user/friends") return@apiClient respond("", HttpStatusCode.NotFound)
        pageRequests.incrementAndGet()
        val offset = request.url.parameters["offset"]!!.toInt()
        val limit = request.url.parameters["limit"]!!.toInt()
        val rows = (offset + 1..minOf(offset + limit, count)).joinToString(",") { id ->
            """{"id":$id,"username":"u$id","nickname":"","avatar":{"large":"","medium":"","small":""},"group":10,"joinedAt":0,"sign":"","isFriend":false}"""
        }
        respond("""{"data":[$rows],"total":$count}""", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
    })

    private fun repository(userId: Int? = 42) = testFriendsRepository(resources, api, testTokenStore(resources, userId), clock, background)

    private suspend fun backgroundSettled() = background.coroutineContext.job.children.toList().joinAll()

    @Test
    fun `没有缓存时订阅即在后台逐页读全名单`() = runTest {
        val friends = repository().changes().first { it != Friends.NONE }

        assertEquals((1..150).toSet(), friends.ids)
        assertEquals(2, pageRequests.get())
    }

    @Test
    fun `缓存未过期时订阅只给缓存_不再请求`() = runTest {
        val repository = repository()
        repository.changes().first { it != Friends.NONE }
        backgroundSettled()
        pageRequests.set(0)
        count = 3

        clock.advance(STALE_MINUS_ONE_MINUTE)
        val friends = repository.changes().first()
        backgroundSettled()

        assertEquals((1..150).toSet(), friends.ids)
        assertEquals(0, pageRequests.get())
    }

    @Test
    fun `缓存过期后订阅先给旧名单_后台重读后换成新名单`() = runTest {
        val repository = repository()
        repository.changes().first { it != Friends.NONE }
        backgroundSettled()
        count = 3

        clock.advance(STALE_PLUS_ONE_MINUTE)
        val updated = repository.changes().first { it.ids.size == 3 }

        assertEquals(setOf(1, 2, 3), updated.ids)
    }

    @Test
    fun `游客谁也没关注_不请求`() = runTest {
        val friends = repository(userId = null).changes().first()
        backgroundSettled()

        assertEquals(Friends.NONE, friends)
        assertEquals(0, pageRequests.get())
    }

    @Test
    fun `写信要好友本人_逐页读全给出资料_名单同时记下_订阅不再请求`() = runTest {
        val repository = repository()

        val users = (repository.users() as Outcome.Success).value

        assertEquals((1..150).toList(), users.map { it.id })
        assertEquals("u7", users[6].username)
        assertEquals(2, pageRequests.get())
        pageRequests.set(0)
        assertEquals((1..150).toSet(), repository.changes().first().ids)
        backgroundSettled()
        assertEquals(0, pageRequests.get(), "名单是刚才那次读取记下的，没有过期")
    }

    @Test
    fun `写之前发出的名单读取晚到_写后的回读不用它_也不落缓存`() = runTest {
        val stillFriend = AtomicBoolean(true)
        val reads = AtomicInteger()
        val firstReadSent = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        val api = UserApi(resources.apiClient { request ->
            val rows = if (stillFriend.get()) {
                """{"id":99,"username":"bob","nickname":"","avatar":{"large":"","medium":"","small":""},"group":10,"joinedAt":0,"sign":"","isFriend":false}"""
            } else {
                ""
            }
            if (reads.incrementAndGet() == 1) {
                firstReadSent.complete(Unit)
                releaseFirstRead.await()
            }
            respond(
                """{"data":[$rows],"total":${if (rows.isEmpty()) 0 else 1}}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        })
        val tokenStore = testTokenStore(resources, 42)
        val repository = testFriendsRepository(resources, api, tokenStore, clock, background)
        val session = tokenStore.session()

        val early = async { repository.users() }
        firstReadSent.await()
        stillFriend.set(false)
        // 不挂起地走到发请求那一步：取消好友已经做成，之后才放行写之前那次读取
        val confirmed = async(start = CoroutineStart.UNDISPATCHED) { repository.confirmWrite(session) }
        releaseFirstRead.complete(Unit)

        assertEquals(emptySet(), confirmed.await().ids, "回读给的是写之后的名单，取消好友不会误报做不到")
        assertEquals(emptyList(), (early.await() as Outcome.Success).value, "等着旧读取的一方拿到的也是重读的名单")
        assertEquals(emptySet(), repository.changes().first().ids, "旧名单没有落进缓存")
    }

    @Test
    fun `写之前已经读完的名单读取_写后的回读不接它_重新读`() = runTest {
        val stillFriend = AtomicBoolean(true)
        val api = UserApi(resources.apiClient { _ ->
            val rows = if (stillFriend.get()) {
                """{"id":99,"username":"bob","nickname":"","avatar":{"large":"","medium":"","small":""},"group":10,"joinedAt":0,"sign":"","isFriend":false}"""
            } else {
                ""
            }
            respond(
                """{"data":[$rows],"total":${if (rows.isEmpty()) 0 else 1}}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        })
        val stored = CompletableDeferred<Unit>()
        val disk = PreferenceDataStoreFactory.create(scope = resources.scope) { File(resources.directory("friends-stored"), "prefs.preferences_pb") }
        val dataStore = object : DataStore<Preferences> by disk {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                disk.updateData(transform).also { stored.complete(Unit) }
        }
        val tokenStore = testTokenStore(resources, 42)
        val repository = FriendsRepository(dataStore, api, tokenStore, clock, background)
        val session = tokenStore.session()

        // 写之前的读取：读到对方还在、名单已经记下（还没交出时，写后的回读也不能接它）
        val early = background.async { repository.users() }
        stored.await()
        stillFriend.set(false)
        val confirmed = repository.confirmWrite(session)

        assertEquals(emptySet(), confirmed.ids, "回读给的是写之后的名单，取消好友不会误报做不到")
        assertEquals(listOf(99), (early.await() as Outcome.Success).value.map { it.id }, "写之前的那次读取照常交给它自己的调用方")
        backgroundSettled()
        assertEquals(emptySet(), repository.changes().first().ids, "缓存是写之后读到的名单")
    }

    private companion object {
        const val STALE_MINUS_ONE_MINUTE = 59 * 60 * 1000L
        const val STALE_PLUS_ONE_MINUTE = 61 * 60 * 1000L
    }
}
