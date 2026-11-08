package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.web.TestWebSite
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.testing.TestClock
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** 绝交名单：以服务端为准、按账号缓存，读不到时谁也不屏蔽；设置页的名单带上名字。 */
class BlocklistRepositoryTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private val requestRecorder = ConcurrentRequestRecorder()
    private val requests: List<String> get() = requestRecorder.map { it.url.encodedPath }
    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun server(blocklist: String?, users: Map<String, String> = emptyMap()) = resources.apiClient(MockEngine { request ->
        val path = request.url.encodedPath
        requestRecorder.record(request)
        when {
            path == "/p1/blocklist" && blocklist != null -> respond(blocklist, HttpStatusCode.OK, json)
            path.startsWith("/p1/users/") -> users[path.removePrefix("/p1/users/")]
                ?.let { respond(it, HttpStatusCode.OK, json) }
                ?: respond("", HttpStatusCode.NotFound)
            else -> respond("", HttpStatusCode.InternalServerError)
        }
    })

    private fun p1User(id: Int, username: String, nickname: String) =
        """{"id":$id,"username":"$username","nickname":"$nickname","avatar":{"large":"l","medium":"m","small":"s"},
            "group":10,"joinedAt":0,"sign":"","site":"","location":"","bio":"","networkServices":[],"homepage":{"left":[],"right":[]},
            "stats":{"subject":{},"mono":{"character":0,"person":0},"blog":0,"friend":0,"group":0,"index":{"create":0,"collect":0}},
            "isFriend":false}"""

    private suspend fun TestScope.repository(
        api: HttpClient,
        site: TestWebSite = TestWebSite(userId = 42),
    ): Pair<BlocklistRepository, TokenStore> {
        val tokenStore = testTokenStore(scope = backgroundScope)
        tokenStore.completeSession(tokenStore.beginSession("token"), userId = 42, username = "fixture_user")
        val dir = resources.directory("blocklist")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "p.preferences_pb") }
        return BlocklistRepository(dataStore, UserApi(api), tokenStore, testWebSessionRepository(tokenStore, site.client), TestClock(time = 1_000L), backgroundScope) to tokenStore
    }

    @Test
    fun `没有缓存时当场读名单并存下_之后按缓存给_不再请求`() = runTest {
        val (repository, tokenStore) = repository(server("""{"blocklist":[7,99]}"""))
        val session = tokenStore.session()

        assertEquals(BlockedUsers(setOf(7, 99)), repository.of(session))
        assertEquals(BlockedUsers(setOf(7, 99)), repository.of(session))
        assertEquals(listOf("/p1/blocklist"), requests)
    }

    @Test
    fun `读不到名单时谁也不屏蔽_不挡住内容`() = runTest {
        val (repository, tokenStore) = repository(server(blocklist = null))

        assertEquals(BlockedUsers.NONE, repository.of(tokenStore.session()))
    }

    @Test
    fun `绝交或取消绝交记下的名单立刻生效`() = runTest {
        val (repository, tokenStore) = repository(server("""{"blocklist":[7]}"""))
        val session = tokenStore.session()

        repository.record(session, listOf(7, 99))

        assertEquals(BlockedUsers(setOf(7, 99)), repository.of(session))
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `页面跟着的名单_先给读到的一份_绝交记下新名单后再出一拍`() = runTest {
        val (repository, tokenStore) = repository(server("""{"blocklist":[7]}"""))
        val emitted = Channel<BlockedUsers>(Channel.UNLIMITED)
        // 等第一份名单的时限按真实时间算：在测试调度器上，请求在别的线程跑时虚拟时钟会直接跳过时限
        backgroundScope.launch(Dispatchers.Default) { repository.current().collect { emitted.send(it) } }

        assertEquals(BlockedUsers(setOf(7)), emitted.receive())
        repository.record(tokenStore.session(), listOf(7, 99))

        assertEquals(BlockedUsers(setOf(7, 99)), emitted.receive())
        assertEquals(listOf("/p1/blocklist"), requests)
    }

    @Test
    fun `页面跟着的名单_当场读得太久先给谁也不屏蔽_读到存下后再出一拍`() = runTest {
        val readSent = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val (repository, _) = repository(resources.apiClient(MockEngine { request ->
            requestRecorder.record(request)
            readSent.complete(Unit)
            releaseRead.await()
            respond("""{"blocklist":[7]}""", HttpStatusCode.OK, json)
        }))
        val emitted = Channel<BlockedUsers>(Channel.UNLIMITED)
        backgroundScope.launch { repository.current().collect { emitted.send(it) } }

        readSent.await()
        assertEquals(BlockedUsers.NONE, emitted.receive(), "名单接口卡住：页面不一直等，先按谁也不屏蔽画")
        releaseRead.complete(Unit)

        assertEquals(BlockedUsers(setOf(7)), emitted.receive(), "超时后读取接着跑，读到后再出一拍")
        assertEquals(listOf("/p1/blocklist"), requests)
    }

    @Test
    fun `设置页名单_按id换成用户名再查资料_带昵称与头像_不需要网页会话`() = runTest {
        val site = TestWebSite(userId = 42)
        site.redirects["/user/7"] = "https://bgm.tv/user/carol"
        val api = server("""{"blocklist":[7]}""", users = mapOf("carol" to p1User(7, "carol", "Carol")))
        val (repository, _) = repository(api, site = site)

        val users = assertIs<Outcome.Success<List<UserBo>>>(repository.blockedUsers()).value

        assertEquals(listOf(7), users.map(UserBo::id))
        assertEquals(listOf("carol" to "Carol"), users.map { it.username to it.nickname })
        assertEquals(listOf("m"), users.map { it.avatarMedium })
    }

    @Test
    fun `设置页名单_用户名就是id的人没有跳转_换不到用户名的人照样列出只有id`() = runTest {
        val site = TestWebSite(userId = 42)
        site.pages["/user/123"] = "<html></html>"
        val api = server("""{"blocklist":[123,99]}""", users = mapOf("123" to p1User(123, "123", "无名")))
        val (repository, _) = repository(api, site = site)

        val users = assertIs<Outcome.Success<List<UserBo>>>(repository.blockedUsers()).value

        assertEquals(listOf(123, 99), users.map(UserBo::id))
        assertEquals(listOf("无名", ""), users.map { it.nickname })
    }

    @Test
    fun `设置页名单_p1增强资料失败时保留已解析用户名`() = runTest {
        val site = TestWebSite(userId = 42)
        site.redirects["/user/7"] = "https://bgm.tv/user/carol"
        val api = server("""{"blocklist":[7]}""")
        val (repository, _) = repository(api, site = site)

        val users = assertIs<Outcome.Success<List<UserBo>>>(repository.blockedUsers()).value

        assertEquals(listOf(7), users.map(UserBo::id))
        assertEquals(listOf("carol"), users.map(UserBo::username))
    }

    @Test
    fun `设置页名单_查资料期间换号返回SessionChanged`() = runTest {
        lateinit var currentTokenStore: TokenStore
        val site = TestWebSite(userId = 42)
        site.redirects["/user/7"] = "https://bgm.tv/user/carol"
        val api = resources.apiClient(MockEngine { request ->
            val path = request.url.encodedPath
            requestRecorder.record(request)
            when {
                path == "/p1/blocklist" -> respond("""{"blocklist":[7]}""", HttpStatusCode.OK, json)
                path == "/p1/users/carol" -> {
                    currentTokenStore.completeSession(currentTokenStore.beginSession("other"), userId = 8, username = "bob")
                    respond(p1User(7, "carol", "Carol"), HttpStatusCode.OK, json)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        })
        val (repository, tokenStore) = repository(api, site = site)
        currentTokenStore = tokenStore

        assertEquals(Outcome.Failed(Failure.SessionChanged), repository.blockedUsers())
    }

    @Test
    fun `没有缓存时读名单失败后一小段时间内不再读`() = runTest {
        val (repository, tokenStore) = repository(server(blocklist = null))
        val session = tokenStore.session()

        assertEquals(BlockedUsers.NONE, repository.of(session))
        assertEquals(BlockedUsers.NONE, repository.of(session))
        assertEquals(1, requests.count { it == "/p1/blocklist" })
    }

    @Test
    fun `绝交之前发出的名单读取晚到_作废重读_不盖掉绝交后记下的名单`() = runTest {
        val serverList = AtomicReference("[7]")
        val reads = AtomicInteger()
        val firstReadSent = CompletableDeferred<Unit>()
        val releaseFirstRead = CompletableDeferred<Unit>()
        val (repository, tokenStore) = repository(resources.apiClient(MockEngine { request ->
            requestRecorder.record(request)
            val list = serverList.get()
            if (reads.incrementAndGet() == 1) {
                firstReadSent.complete(Unit)
                releaseFirstRead.await()
            }
            respond("""{"blocklist":$list}""", HttpStatusCode.OK, json)
        }))
        val session = tokenStore.session()

        val early = async { repository.of(session) }
        firstReadSent.await()
        serverList.set("[7,99]")
        repository.record(session, listOf(7, 99))
        releaseFirstRead.complete(Unit)

        assertEquals(BlockedUsers(setOf(7, 99)), early.await(), "绝交前发出的读取作废，交出的是重读到的名单")
        assertEquals(2, reads.get(), "绝交之后重读了一次")
        assertEquals(BlockedUsers(setOf(7, 99)), repository.changes(session).first(), "缓存仍是绝交后记下的名单")
    }
}
