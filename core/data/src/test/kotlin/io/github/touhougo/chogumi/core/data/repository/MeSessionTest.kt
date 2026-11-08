package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule

/** 本人资料请求的迟到回包不能发布或落库到已离开的会话。 */
class MeSessionTest {
    @get:Rule
    val resources = TestResources()

    private suspend fun verify(logout: Boolean) = kotlinx.coroutines.coroutineScope {
        val tokens = testTokenStore(resources)
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = resources.apiClient(MockEngine { request ->
            arrived.complete(Unit)
            release.await()
            if (request.url.encodedPath == "/v0/me") {
                respond("""{"id":42,"username":"fixture_user","nickname":"Fixture User","user_group":10,"avatar":{"large":"","medium":"","small":""},"sign":""}""",
                    HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            } else respond("{}", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
        })
        val rows = mutableMapOf<Int, UserEntity>()
        val dao = object : UserDao {
            override suspend fun get(id: Int): UserEntity? = rows[id]
            override suspend fun getByUsername(username: String): UserEntity? = rows.values.firstOrNull { it.username == username }
            override suspend fun upsert(user: UserEntity) { rows[user.id] = user }
        }
        try {
            tokens.completeSession(tokens.beginSession("fixture"), 42, "fixture_user")
            val repository = testUserRepository(resources, UserApi(client), tokens, dao)
            val loads = async { repository.getMe(refresh = true).toList() }
            withTimeout(WAIT_BUDGET_MS) { arrived.await() }
            if (logout) tokens.clear() else tokens.completeSession(tokens.beginSession("other"), 70, "other")
            release.complete(Unit)
            val result = withTimeout(WAIT_BUDGET_MS) { loads.await() }
            assertEquals(Load.Failed(Failure.SessionChanged), result.last())
            assertTrue(result.none { it is Load.Ready }, "旧本人资料不得交给新会话")
            assertTrue(rows.isEmpty(), "旧请求不得缓存已离开会话的本人资料")
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `本人资料在途换号 丢弃回包与缓存写入`() = runBlocking { verify(logout = false) }

    @Test
    fun `本人资料在途登出 丢弃回包与缓存写入`() = runBlocking { verify(logout = true) }
}
