package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.repository.FakeCommentRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.StillUserModeration
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 日志详情的重试：作废上一轮，上一轮的正文不会晚到盖掉新的。 */
class BlogDetailsViewModelTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val main = TestMain()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val viewModels = mutableListOf<BlogDetailsViewModel>()

    /** 关联作品端点是否按服务端故障答复（500）。 */
    private val subjectsFailing = AtomicBoolean(false)

    /** 正文请求的次数；第一次停在 [firstGate] 上。 */
    private val detailRequests = AtomicInteger()
    private val firstStarted = CompletableDeferred<Unit>()
    private val firstGate = CompletableDeferred<Unit>()

    /** 第一次正文请求的结局：true = 等待时被取消，false = 放行后照常返回。 */
    private val firstCancelled = CompletableDeferred<Boolean>()

    private fun blogJson(title: String) = """
        {"id":7,"type":0,"uid":9,"title":"$title","icon":"","content":"正文","tags":[],"views":0,"replies":0,
         "createdAt":1700000000,"updatedAt":1700000000,"noreply":0,"related":0,"public":true,
         "user":{"id":9,"username":"fixture_user","nickname":"Fixture User","avatar":{"small":"s","medium":"m","large":"l"},
                 "group":10,"joinedAt":1600000000,"sign":"","isFriend":false}}
    """.trimIndent()

    private val subjectsJson = """[{"id":12,"name":"ちょびっツ","nameCN":"人形电脑天使心","type":2,"info":"","metaTags":[],
        "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false}]"""

    private fun makeViewModel(): BlogDetailsViewModel {
        val engine = MockEngine { request ->
            val json = headersOf("Content-Type", ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/p1/blogs/7" -> {
                    if (detailRequests.incrementAndGet() == 1) {
                        firstStarted.complete(Unit)
                        try {
                            firstGate.await()
                        } catch (e: CancellationException) {
                            firstCancelled.complete(true)
                            throw e
                        }
                        firstCancelled.complete(false)
                        respond(blogJson("第一轮"), HttpStatusCode.OK, json)
                    } else {
                        respond(blogJson("第二轮"), HttpStatusCode.OK, json)
                    }
                }
                "/p1/blogs/7/subjects" ->
                    if (subjectsFailing.get()) respond("", HttpStatusCode.InternalServerError) else respond(subjectsJson, HttpStatusCode.OK, json)
                "/p1/blogs/7/comments" -> respond("[]", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val client = resources.apiClient(engine)
        val userRepository = testUserRepository(resources, UserApi(client), testTokenStore(scope = scope), object : UserDao {
            override suspend fun get(id: Int) = null
            override suspend fun getByUsername(username: String) = null
            override suspend fun upsert(user: UserEntity) = Unit
        })
        return BlogDetailsViewModel(7, userRepository, FakeCommentRepository(scope), StillUserModeration(), NoBlockedUsers()).also { viewModels += it }
    }

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(viewModels.map { it.viewModelScope } + scope)

    @Test
    fun `正文还在路上时重试_上一轮作废_页面是新一轮的结果`() = runBlocking {
        val vm = withContext(main.dispatcher) { makeViewModel() }
        withTimeout(10.seconds) { firstStarted.await() }

        withContext(main.dispatcher) { vm.retry() }
        awaitUntil("第二轮落定") { vm.uiState.value.details is Load.Ready }

        firstGate.complete(Unit)
        assertTrue(withTimeout(10.seconds) { firstCancelled.await() }, "重试时上一轮的请求必须作废")
        assertEquals("第二轮", vm.uiState.value.details.valueOrNull()?.title)
        assertEquals(2, detailRequests.get())
    }

    @Test
    fun `关联作品取不到时正文照常到达_眉题区是失败_重试取到后到达`() = runBlocking {
        subjectsFailing.set(true)
        firstGate.complete(Unit)
        val vm = withContext(main.dispatcher) { makeViewModel() }
        awaitUntil("正文落定") { vm.uiState.value.details is Load.Ready }
        assertTrue(vm.uiState.value.subjects is Load.Failed, "关联作品取不到：区块是失败，不是空列表")

        subjectsFailing.set(false)
        withContext(main.dispatcher) { vm.retrySubjects() }
        awaitUntil("重试后关联作品到达") { vm.uiState.value.subjects is Load.Ready }
        assertEquals(listOf(12), vm.uiState.value.subjects.valueOrNull()?.map { it.id })
        assertEquals(1, detailRequests.get(), "重试关联作品不重取正文")
    }
}
