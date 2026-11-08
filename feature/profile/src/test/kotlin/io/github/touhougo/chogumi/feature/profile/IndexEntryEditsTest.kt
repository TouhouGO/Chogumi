package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.FakeCommentRepository
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.StillUserModeration
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.model.IndexEntrySort
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自己目录里改一条收录：评语、拖动排序。假服务端按 p1 的实现排收录：`(order, id)` 升序；改一条收录是
 * `PATCH /p1/indexes/1/related/{id}` `{order, comment}`。目录 1 是登录账号 1 创建的，作品收录默认三行、排序值都是 0。
 */
class IndexEntryEditsTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private val main = TestMain()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private val requests = ConcurrentRequestRecorder()

    /** 服务端的收录行：related id → (order, comment)。 */
    private val rows = ConcurrentHashMap<Int, Pair<Int, String>>()

    /** 目录的创建者 uid：1 是登录账号自己。 */
    @Volatile
    private var ownerUid = 1

    /** 第几次 PATCH 以 500 失败（从 1 数；0 表示都成功）。 */
    @Volatile
    private var failingPatch = 0
    private val patches = AtomicInteger()

    /** 不为 null 时，第一次 PATCH 停在它上面。 */
    @Volatile
    private var firstPatchGate: CompletableDeferred<Unit>? = null

    private val engine = MockEngine { request ->
        requests.record(request)
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val path = request.url.encodedPath
        when {
            request.method == HttpMethod.Patch && path.startsWith("/p1/indexes/1/related/") -> {
                val n = patches.incrementAndGet()
                if (n == 1) firstPatchGate?.await()
                if (n == failingPatch) {
                    respond("{}", HttpStatusCode.InternalServerError, json)
                } else {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    rows[path.substringAfterLast('/').toInt()] = body.getValue("order").jsonPrimitive.int to body.getValue("comment").jsonPrimitive.content
                    respond("{}", HttpStatusCode.OK, json)
                }
            }
            path == "/p1/indexes/1" -> respond(indexJson(), HttpStatusCode.OK, json)
            path == "/p1/indexes/1/related" -> respond(
                relatedPage(offset = request.url.parameters["offset"]?.toInt() ?: 0, limit = request.url.parameters["limit"]?.toInt() ?: 20),
                HttpStatusCode.OK,
                json,
            )
            path == "/p1/indexes/1/comments" -> respond("[]", HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private val client = resources.apiClient(engine)
    private val tokenStore = testTokenStore(resources, userId = 1)
    private val indexRepository = IndexRepository(IndexApi(client), UserApi(client), tokenStore)

    private fun indexJson() =
        """{"id":1,"uid":$ownerUid,"type":0,"title":"目录","desc":"","private":false,"total":${rows.size},"replies":0,"collects":0,""" +
            """"stats":{"subject":{"anime":${rows.size}}},"award":0,"createdAt":1700000000,"updatedAt":1700000000}"""

    /** 服务端顺序：`(order, id)` 升序。 */
    private fun serverOrder(): List<Int> = rows.entries.sortedWith(compareBy({ it.value.first }, { it.key })).map { it.key }

    private fun relatedPage(offset: Int, limit: Int): String {
        val all = serverOrder()
        val page = all.drop(offset).take(limit)
        return page.joinToString(",", prefix = """{"data":[""", postfix = """],"total":${all.size}}""") { id ->
            val (order, comment) = rows.getValue(id)
            """{"id":$id,"cat":0,"rid":1,"type":2,"sid":${id + 100},"order":$order,"comment":"$comment","award":"","createdAt":1,
                "subject":{"id":${id + 100},"name":"s$id","nameCN":"","type":2,"info":"","metaTags":[],
                "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false}}"""
        }
    }

    private fun seed(vararg ids: Int) = ids.forEach { rows[it] = 0 to "" }

    private fun viewModel() = IndexDetailsViewModel(1, indexRepository, FakeCommentRepository(scope), StillUserModeration(), NoBlockedUsers()).also { viewModels += it }

    private fun shownIds(vm: IndexDetailsViewModel) = vm.uiState.value.entries.items.map { it.id }

    private fun patchBodies() = requests.filter { it.method == HttpMethod.Patch }.map { it.url.encodedPath.substringAfterLast('/') + " " + (it.body as TextContent).text }

    private suspend fun loaded(vm: IndexDetailsViewModel) =
        awaitUntil("收录全部到了、可以拖") { vm.uiState.value.canReorder }

    @BeforeTest
    fun setUp() {
        main.install()
        seed(11, 12, 13)
    }

    @AfterTest
    fun tearDown() = main.uninstall(viewModels.map { it.viewModelScope } + scope)

    @Test
    fun `编辑评语_带上这一条现在的排序值一起写_成功后行里换成新评语_面板收起`() = runBlocking {
        rows[12] = 5 to "旧"
        val vm = withContext(main.dispatcher) { viewModel() }
        loaded(vm)
        val entry = vm.uiState.value.entries.items.first { it.id == 12 }

        withContext(main.dispatcher) {
            vm.openComment(entry)
            vm.changeComment("  值得一看 ")
            vm.saveComment()
        }
        awaitUntil("面板收起") { vm.uiState.value.entryComment?.saved == true }

        assertEquals(listOf("""12 {"order":5,"comment":"值得一看"}"""), patchBodies())
        assertEquals("值得一看", vm.uiState.value.entries.items.first { it.id == 12 }.comment)
    }

    @Test
    fun `评语写失败_面板与框里的字留着_失败提示`() = runBlocking {
        failingPatch = 1
        val vm = withContext(main.dispatcher) { viewModel() }
        loaded(vm)

        withContext(main.dispatcher) {
            vm.openComment(vm.uiState.value.entries.items.first())
            vm.changeComment("评语")
            vm.saveComment()
        }
        awaitUntil("失败提示") { vm.failures.current.value != null }

        val panel = assertNotNull(vm.uiState.value.entryComment)
        assertEquals("评语", panel.text)
        assertFalse(panel.saving)
        assertFalse(panel.saved)
        assertEquals(1, patches.get(), "确实发出了写评语的请求")
        assertEquals("", vm.uiState.value.entries.items.first().comment)
    }

    @Test
    fun `拖动排序_屏上当即换成新顺序_只写挪动的那一条_服务端排出来就是新顺序`() = runBlocking {
        val vm = withContext(main.dispatcher) { viewModel() }
        loaded(vm)

        val accepted = withContext(main.dispatcher) { vm.moveEntries(listOf(11, 13, 12)).also { assertEquals(listOf(11, 13, 12), shownIds(vm)) } }
        awaitUntil("写完") { !vm.uiState.value.isReordering }

        assertTrue(accepted)
        assertEquals(1, patches.get())
        assertEquals(listOf(11, 13, 12), serverOrder())
        assertEquals(listOf(11, 13, 12), shownIds(vm))
        assertNull(vm.failures.current.value)
    }

    @Test
    fun `拖动排序写到一半失败_写成的写回原值_屏上回到拖动前_提示_从服务端重取`() = runBlocking {
        val vm = withContext(main.dispatcher) { viewModel() }
        loaded(vm)
        val fetches = requests.count { it.method == HttpMethod.Get && it.url.encodedPath == "/p1/indexes/1/related" }
        // 13 挪到最前：排序值不能取负数，11、12 要排到它后面，两条都要写；第二条失败
        failingPatch = 2

        withContext(main.dispatcher) { vm.moveEntries(listOf(13, 11, 12)) }
        awaitUntil("失败提示") { vm.failures.current.value != null }
        awaitUntil("写入落定") { !vm.uiState.value.isReordering }

        assertEquals(
            listOf("""11 {"order":1024,"comment":""}""", """12 {"order":2048,"comment":""}""", """11 {"order":0,"comment":""}"""),
            patchBodies(),
            "写成的第一条按原值写回",
        )
        assertEquals(listOf(11, 12, 13), serverOrder())
        assertEquals(listOf(11, 12, 13), shownIds(vm))
        awaitUntil("从服务端重取了") { requests.count { it.method == HttpMethod.Get && it.url.encodedPath == "/p1/indexes/1/related" } > fetches }
    }

    @Test
    fun `要改的条数超过服务端5分钟的写入上限_不接受_不写_提示`() = runBlocking {
        rows.clear()
        seed(*(1..17).toList().toIntArray())
        val vm = withContext(main.dispatcher) { viewModel() }
        loaded(vm)

        // 最后一条挪到最前：前面 16 条都要往后排
        val accepted = withContext(main.dispatcher) { vm.moveEntries(listOf(17) + (1..16)) }

        assertFalse(accepted)
        assertNotNull(vm.failures.current.value)
        assertEquals(0, patches.get())
        assertEquals((1..17).toList(), shownIds(vm))
    }

    /** 排序那一笔失败、屏上回到原顺序：在它途中点的「保存评语」要带上回滚后的排序值，而不是拖动后屏上的那个。 */
    @Test
    fun `排序写入在途时存评语_等排序落定再写_带上落定后的排序值`() = runBlocking {
        val gate = CompletableDeferred<Unit>().also { firstPatchGate = it }
        failingPatch = 1
        val vm = withContext(main.dispatcher) { viewModel() }
        loaded(vm)

        withContext(main.dispatcher) { vm.moveEntries(listOf(11, 13, 12)) }
        awaitUntil("排序的写入发出了") { patches.get() == 1 }
        withContext(main.dispatcher) {
            vm.openComment(vm.uiState.value.entries.items.first { it.id == 12 })
            vm.changeComment("压轴")
            vm.saveComment()
        }
        gate.complete(Unit)
        awaitUntil("评语写完") { vm.uiState.value.entryComment?.saved == true }

        assertEquals(listOf("""12 {"order":1024,"comment":""}""", """12 {"order":0,"comment":"压轴"}"""), patchBodies())
        assertEquals(listOf(11, 12, 13), serverOrder())
        assertEquals(listOf(11, 12, 13), shownIds(vm))
    }

    @Test
    fun `自己的目录把这一类取全才能拖`() = runBlocking {
        rows.clear()
        seed(*(1..101).toList().toIntArray())
        val vm = withContext(main.dispatcher) { viewModel() }

        loaded(vm)

        assertEquals(101, vm.uiState.value.entries.items.size)
        assertEquals(2, requests.count { it.method == HttpMethod.Get && it.url.encodedPath == "/p1/indexes/1/related" })
    }

    @Test
    fun `别人的目录与按评分视图不能拖`() = runBlocking {
        val vm = withContext(main.dispatcher) { viewModel() }
        loaded(vm)

        withContext(main.dispatcher) { vm.selectSort(IndexEntrySort.RATING) }
        assertFalse(vm.uiState.value.canReorder)
        assertFalse(withContext(main.dispatcher) { vm.moveEntries(listOf(13, 12, 11)) })

        ownerUid = 2
        val others = withContext(main.dispatcher) { viewModel() }
        awaitUntil("别人的目录收录到了") { others.uiState.value.entries.items.size == 3 && !others.uiState.value.entries.isLoading }
        assertFalse(others.uiState.value.canReorder)
        assertFalse(withContext(main.dispatcher) { others.moveEntries(listOf(13, 12, 11)) })
        assertEquals(0, patches.get())
    }
}
