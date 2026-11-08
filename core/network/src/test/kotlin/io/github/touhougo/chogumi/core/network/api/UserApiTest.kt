package io.github.touhougo.chogumi.core.network.api

import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class UserApiTest {

    private val meJson = """
        {
          "id": 1,
          "username": "fixture_user",
          "nickname": "Fixture User🖖",
          "user_group": 10,
          "avatar": {
            "large": "https://lain.bgm.tv/pic/user/l/000/00/00/1.jpg",
            "medium": "https://lain.bgm.tv/pic/user/m/000/00/00/1.jpg",
            "small": "https://lain.bgm.tv/pic/user/s/000/00/00/1.jpg"
          },
          "sign": "Awesome!",
          "email": "fixture_user@example.com",
          "reg_time": "2017-12-03T08:51:16+08:00",
          "time_offset": 8
        }
    """.trimIndent()

    private fun makeApi(content: String): Pair<UserApi, MockEngine> {
        val engine = MockEngine {
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = testApiClient(engine)
        return UserApi(client) to engine
    }

    @Test
    fun `getMe 解析完整字段`() = runTest {
        val (api, _) = makeApi(meJson)
        val me = api.getMe("token")
        assertEquals(1, me.id)
        assertEquals("fixture_user", me.username)
        assertEquals("Fixture User🖖", me.nickname)
        assertEquals("https://lain.bgm.tv/pic/user/m/000/00/00/1.jpg", me.avatar.medium)
        assertEquals("fixture_user@example.com", me.email)
    }

    @Test
    fun `getUser 请求路径正确`() = runTest {
        val (api, engine) = makeApi(meJson)
        api.getUser("fixture_user", accessToken = null)
        val request = engine.requestHistory.single()
        assertEquals("api.bgm.tv", request.url.host)
        assertEquals("/v0/users/fixture_user", request.url.encodedPath)
    }

    @Test
    fun `分页端点的用户名经路径编码 不能越出 users 路径`() = runTest {
        val (api, engine) = makeApi("""{"data":[],"total":0}""")
        api.getFriends("../x", accessToken = null)
        assertEquals("/p1/users/..%2Fx/friends", engine.requestHistory.single().url.encodedPath)
    }

    @Test
    fun `缺省字段使用默认值`() = runTest {
        val (api, _) = makeApi("""{"id":2,"username":"a","nickname":"A","user_group":10,"avatar":{"large":"","medium":"","small":""},"sign":""}""")
        val user = api.getUser("a", accessToken = null)
        assertEquals("", user.sign)
        assertEquals("", user.avatar.large)
        assertEquals(null, user.email)
    }
}
