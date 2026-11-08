package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.FakeCommentRepository
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.StillUserModeration
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 目录详情：收藏按钮在途时再点不发第二个请求；留言区重取失败时已有的楼层留着。 */
class IndexDetailsViewModelTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val main = TestMain()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModels = mutableListOf<IndexDetailsViewModel>()

    /** 收藏写入被调用的次数。 */
    private val writes = AtomicInteger()

    /** 收藏写入停到放行为止，让第一次确定地还在途。 */
    private val writeGate = CompletableDeferred<Unit>()

    /** 为真时留言请求以 500 失败。 */
    @Volatile
    private var commentsFail = false

    /** 假服务端：详情、收录、留言照常回；收藏写入（PUT）停在 [writeGate] 上。 */
    private val repository = run {
        val engine = MockEngine { request ->
            val json = headersOf("Content-Type", ContentType.Application.Json.toString())
            when (request.url.encodedPath) {
                "/p1/indexes/1" -> respond(
                    """{"id":1,"uid":2,"type":0,"title":"目录","desc":"","private":false,"total":0,"replies":0,"collects":3,""" +
                        """"stats":{"subject":{}},"award":0,"createdAt":1700000000,"updatedAt":1700000000}""",
                    HttpStatusCode.OK,
                    json,
                )
                "/p1/indexes/1/related" -> respond("""{"data":[],"total":0}""", HttpStatusCode.OK, json)
                "/p1/indexes/1/comments" ->
                    if (commentsFail) respond("{}", HttpStatusCode.InternalServerError, json) else respond(COMMENTS_JSON, HttpStatusCode.OK, json)
                "/p1/collections/indexes/1" -> {
                    writes.incrementAndGet()
                    writeGate.await()
                    respond("{}", HttpStatusCode.OK, json)
                }
                else -> respond("{}", HttpStatusCode.NotFound)
            }
        }
        val client = resources.apiClient(engine)
        IndexRepository(IndexApi(client), UserApi(client), testTokenStore(resources, userId = 1))
    }

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(viewModels.map { it.viewModelScope } + scope)

    @Test
    fun `收藏在途时再点_不发第二个请求_乐观值留到成功`() = runBlocking {
        val vm = withContext(main.dispatcher) { IndexDetailsViewModel(1, repository, FakeCommentRepository(scope), StillUserModeration(), NoBlockedUsers()) }.also { viewModels += it }
        awaitUntil("目录头部到了") { vm.uiState.value.details !is Load.Pending }
        val viewModelJob = vm.viewModelScope.coroutineContext[Job]!!

        withContext(main.dispatcher) {
            val before = viewModelJob.children.toSet()
            vm.toggleCollection()
            vm.toggleCollection()
            assertEquals(1, viewModelJob.children.count { it !in before }, "在途时再点不起第二次写入")
        }
        awaitUntil("写入到了仓库") { writes.get() >= 1 }

        writeGate.complete(Unit)
        awaitUntil("写入落定") { !vm.uiState.value.isCollectionLoading }
        assertEquals(1, writes.get(), "只写一次")
        val details = vm.uiState.value.details.valueOrNull()!!
        assertTrue(details.isCollected, "第一次点的「收藏」成功后留着")
        assertEquals(4, details.collects)
    }

    /** 已有楼层时重取留言失败：楼层留在屏上（不变成整块的错误），失败另发一条提示。 */
    @Test
    fun `已有楼层时重取留言失败_楼层留着_失败另发提示`() = runBlocking {
        val vm = withContext(main.dispatcher) { IndexDetailsViewModel(1, repository, FakeCommentRepository(scope), StillUserModeration(), NoBlockedUsers()) }.also { viewModels += it }
        awaitUntil("留言到了") { vm.uiState.value.comments.floors is Load.Ready }
        val shown = vm.uiState.value.comments.floors

        commentsFail = true
        withContext(main.dispatcher) { vm.retryComments() }
        awaitUntil("重取失败的提示") { vm.failures.current.value != null }

        assertEquals(shown, vm.uiState.value.comments.floors)
        assertEquals(1, vm.uiState.value.comments.reading.threads.size)
    }
}

private val COMMENTS_JSON = """
    [{"id":10,"mainID":1,"creatorID":77,"relatedID":0,"createdAt":1700000100,"content":"留言","state":0,"replies":[],
      "user":{"id":77,"username":"other","nickname":"Other","avatar":{"small":"s","medium":"m","large":"l"},
              "group":10,"joinedAt":1600000000,"sign":"","isFriend":false}}]
""".trimIndent()
