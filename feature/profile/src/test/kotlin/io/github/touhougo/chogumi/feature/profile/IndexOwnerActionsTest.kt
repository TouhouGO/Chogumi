package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.db.user.UserDao
import io.github.touhougo.chogumi.core.data.db.user.UserEntity
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.FakeCommentRepository
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.StillUserModeration
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.repository.testUserRepository
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 自己的目录：新建（目录列表页）、收录链接、移出、编辑后重取头部（目录页）。
 * 假服务端按 p1 契约回：目录 1 是登录账号 1 创建的，收录区作品 2 条、角色 0 条。
 */
class IndexOwnerActionsTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private val main = TestMain()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()
    private val requests = ConcurrentRequestRecorder()

    /** 写请求（POST 新建、PUT 收录、DELETE 移出）以 500 失败。 */
    @Volatile
    private var writesFail = false

    @Volatile private var collectionGate: CompletableDeferred<Unit>? = null
    @Volatile private var collectionFails = false
    @Volatile private var indexTitle = "私密 test"
    @Volatile private var indexDescription = ""
    @Volatile private var indexPrivate = true

    /** 作品收录里还有哪几行（related id）：移出后服务端回的列表跟着少一行。 */
    @Volatile
    private var subjectRows = listOf(11, 12)

    /** 收录过角色后，角色这一类有一行。 */
    @Volatile
    private var characterAdded = false

    /** 目录头部的请求次数；[headGate] 不为 null 时第二次起停在它上面。 */
    private val headRequests = AtomicInteger()

    @Volatile
    private var headGate: CompletableDeferred<Unit>? = null

    private val engine = MockEngine { request ->
        requests.record(request)
        val json = headersOf("Content-Type", ContentType.Application.Json.toString())
        val path = request.url.encodedPath
        val method = request.method
        when {
            method != HttpMethod.Get && path != "/p1/collections/indexes/1" && writesFail ->
                respond("{}", HttpStatusCode.InternalServerError, json)
            method == HttpMethod.Post && path == "/p1/indexes" -> respond("""{"id":91}""", HttpStatusCode.OK, json)
            method == HttpMethod.Put && path == "/p1/indexes/1/related" -> {
                characterAdded = true
                respond("""{"id":21}""", HttpStatusCode.OK, json)
            }
            method == HttpMethod.Delete && path.startsWith("/p1/indexes/1/related/") -> {
                val row = path.substringAfterLast('/').toInt()
                subjectRows = subjectRows - row
                respond("{}", HttpStatusCode.OK, json)
            }
            path == "/p1/collections/indexes/1" -> {
                collectionGate?.await()
                respond("{}", if (collectionFails) HttpStatusCode.InternalServerError else HttpStatusCode.OK, json)
            }
            path == "/p1/indexes/1" && method == HttpMethod.Patch -> {
                val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                indexTitle = body.getValue("title").jsonPrimitive.content
                indexDescription = body.getValue("desc").jsonPrimitive.content
                indexPrivate = body.getValue("private").jsonPrimitive.content.toBooleanStrict()
                respond("{}", HttpStatusCode.OK, json)
            }
            path == "/p1/indexes/1" -> {
                if (headRequests.incrementAndGet() > 1) headGate?.await()
                respond(indexJson(), HttpStatusCode.OK, json)
            }
            path == "/p1/indexes/1/related" -> respond(
                if (request.url.parameters["cat"] == "1") characterPage() else subjectPage(),
                HttpStatusCode.OK,
                json,
            )
            path == "/p1/indexes/1/comments" -> respond("[]", HttpStatusCode.OK, json)
            path == "/p1/users/fixture_user/collections/indexes" -> respond(
                """{"data":[{"id":7,"uid":2,"type":0,"title":"别人的目录","private":false,"total":3,"stats":{"subject":{}},"createdAt":1,"updatedAt":1}],"total":7}""",
                HttpStatusCode.OK,
                json,
            )
            path == "/p1/users/fixture_user/indexes" -> respond(
                """{"data":[{"id":1,"uid":1,"type":0,"title":"私密 test","private":true,"total":2,"stats":{"subject":{}},"createdAt":1,"updatedAt":1}],"total":1}""",
                HttpStatusCode.OK,
                json,
            )
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private val client = resources.apiClient(engine)
    private val tokenStore = testTokenStore(resources, userId = 1)
    private val indexRepository = IndexRepository(IndexApi(client), UserApi(client), tokenStore)

    private fun indexJson() =
        """{"id":1,"uid":1,"type":0,"title":"$indexTitle","desc":"$indexDescription","private":$indexPrivate,"total":${subjectRows.size},"replies":0,"collects":0,""" +
            """"stats":{"subject":{"anime":${subjectRows.size}}${if (characterAdded) ""","character":1""" else ""}},"award":0,""" +
            """"createdAt":1700000000,"updatedAt":1700000000}"""

    private fun subjectPage() = subjectRows.joinToString(",", prefix = """{"data":[""", postfix = """],"total":${subjectRows.size}}""") { row ->
        """{"id":$row,"cat":0,"rid":0,"type":2,"sid":${row + 100},"order":0,"comment":"","award":"","createdAt":1,
            "subject":{"id":${row + 100},"name":"s$row","nameCN":"","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false}}"""
    }

    private fun characterPage() = if (!characterAdded) {
        """{"data":[],"total":0}"""
    } else {
        """{"data":[{"id":21,"cat":1,"rid":0,"type":0,"sid":5,"order":0,"comment":"","award":"","createdAt":1,
            "character":{"id":5,"name":"c5","nameCN":"","role":1,"info":"","comment":0,"nsfw":false,"lock":false}}],"total":1}"""
    }

    private fun detailsViewModel() = IndexDetailsViewModel(1, indexRepository, FakeCommentRepository(scope), StillUserModeration(), NoBlockedUsers()).also { viewModels += it }

    private fun listViewModel(counts: IndexListCounts = IndexListCounts(created = 1, collected = 0)) = IndexListViewModel(
        username = "fixture_user",
        counts = counts,
        userRepository = testUserRepository(resources, UserApi(client), tokenStore, object : UserDao {
            override suspend fun get(id: Int) = null
            override suspend fun getByUsername(username: String) = null
            override suspend fun upsert(user: UserEntity) = Unit
        }),
        indexRepository = indexRepository,
    ).also { viewModels += it }

    private fun requestsTo(method: HttpMethod, path: String) = requests.count { it.method == method && it.url.encodedPath == path }

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(viewModels.map { it.viewModelScope } + scope)

    @Test
    fun `目录页按取数时的账号认出是自己的目录`() = runBlocking {
        val vm = withContext(main.dispatcher) { detailsViewModel() }
        awaitUntil("目录头部到了") { vm.uiState.value.details is Load.Ready }

        val details = vm.uiState.value.details.valueOrNull()!!
        assertTrue(details.isOwn, "创建者 uid 1 就是登录账号 1")
        assertTrue(details.isPrivate)
    }

    @Test
    fun `打开目录页两页一起取_没点过的页签也显示列表的total`() = runBlocking {
        // 带过来的数是错的（创建的 0）：落在「收藏的」，「创建的」从没出现在屏幕上
        val vm = withContext(main.dispatcher) { listViewModel(IndexListCounts(created = 0, collected = 5)) }
        assertEquals(IndexListPage.COLLECTED, vm.uiState.value.page)
        awaitUntil("两页的第一页都到了") { IndexListPage.entries.none { vm.uiState.value.listFor(it).isLoading } }

        assertEquals(1, requestsTo(HttpMethod.Get, "/p1/users/fixture_user/indexes"), "没点过的「创建的」也取了第一页")
        assertEquals(1, requestsTo(HttpMethod.Get, "/p1/users/fixture_user/collections/indexes"))
        assertEquals(1, vm.uiState.value.countOf(IndexListPage.CREATED))
        assertEquals(7, vm.uiState.value.countOf(IndexListPage.COLLECTED))
    }

    @Test
    fun `新建目录成功_面板收起并交出新目录的id_已打开的列表从头重取`() = runBlocking {
        val vm = withContext(main.dispatcher) { listViewModel() }
        awaitUntil("「创建的」第一页到了") { !vm.uiState.value.listFor(IndexListPage.CREATED).isLoading }
        val listFetches = requestsTo(HttpMethod.Get, "/p1/users/fixture_user/indexes")

        val created = CompletableDeferred<Int>()
        withContext(main.dispatcher) {
            vm.editor.openNew()
            vm.editor.change(IndexDraft(title = "新目录", isPrivate = true))
            vm.editor.save(onSaved = { created.complete(it) })
        }
        awaitUntil("交出了新目录的 id") { created.isCompleted }

        assertEquals(91, created.await())
        assertTrue(vm.uiState.value.editor?.saved == true, "写成功才滑下去")
        awaitUntil("「创建的」从头重取了") { requestsTo(HttpMethod.Get, "/p1/users/fixture_user/indexes") > listFetches }
    }

    @Test
    fun `新建目录失败_面板和填的内容留着_失败提示`() = runBlocking {
        writesFail = true
        val vm = withContext(main.dispatcher) { listViewModel() }

        withContext(main.dispatcher) {
            vm.editor.openNew()
            vm.editor.change(IndexDraft(title = "新目录", description = "介绍"))
            vm.editor.save()
        }
        awaitUntil("失败提示") { vm.failures.current.value != null }

        val editor = assertNotNull(vm.uiState.value.editor)
        assertFalse(editor.saved)
        assertFalse(editor.saving)
        assertEquals(IndexDraft(title = "新目录", description = "介绍"), editor.draft)
        assertEquals(1, requestsTo(HttpMethod.Post, "/p1/indexes"), "确实发出了新建请求")
    }

    @Test
    fun `收录链接成功_面板收起_收录区切到那一类并重取_头部重取`() = runBlocking {
        val vm = withContext(main.dispatcher) { detailsViewModel() }
        awaitUntil("目录页到了") { vm.uiState.value.details is Load.Ready }
        assertEquals(IndexCategory.SUBJECT, vm.uiState.value.category)

        withContext(main.dispatcher) {
            vm.openLink("https://bgm.tv/character/5")
            vm.addLink(IndexTarget(IndexCategory.CHARACTER, 5))
        }
        awaitUntil("面板收起") { vm.uiState.value.link?.added == true }
        awaitUntil("角色这一类的收录到了") { vm.uiState.value.entries.items.map { it.id } == listOf(21) }

        assertEquals(IndexCategory.CHARACTER, vm.uiState.value.category)
        assertEquals(1, requestsTo(HttpMethod.Put, "/p1/indexes/1/related"))
        awaitUntil("头部带上了角色的数量") {
            vm.uiState.value.details.valueOrNull()?.categoryCounts?.any { it.first == IndexCategory.CHARACTER } == true
        }
    }

    @Test
    fun `收录链接失败_面板和链接留着_失败提示`() = runBlocking {
        val vm = withContext(main.dispatcher) { detailsViewModel() }
        awaitUntil("目录页到了") { vm.uiState.value.details is Load.Ready }
        writesFail = true

        withContext(main.dispatcher) {
            vm.openLink("https://bgm.tv/character/5")
            vm.addLink(IndexTarget(IndexCategory.CHARACTER, 5))
        }
        awaitUntil("失败提示") { vm.failures.current.value != null }

        assertEquals(IndexLinkState(text = "https://bgm.tv/character/5"), vm.uiState.value.link)
        assertEquals(IndexCategory.SUBJECT, vm.uiState.value.category, "失败不换类别")
        assertEquals(1, requestsTo(HttpMethod.Put, "/p1/indexes/1/related"), "确实发出了收录请求")
    }

    @Test
    fun `移出一行_行消失_收录从头重取`() = runBlocking {
        val vm = withContext(main.dispatcher) { detailsViewModel() }
        awaitUntil("作品收录到了") { vm.uiState.value.entries.items.size == 2 }
        val relatedFetches = requestsTo(HttpMethod.Get, "/p1/indexes/1/related")
        val entry = vm.uiState.value.entries.items.first { it.id == 11 }

        withContext(main.dispatcher) { vm.removeEntry(entry) }
        awaitUntil("这一行没了") { vm.uiState.value.entries.items.map { it.id } == listOf(12) }

        assertEquals(1, requestsTo(HttpMethod.Delete, "/p1/indexes/1/related/11"), "按收录行的 id 移出")
        awaitUntil("收录从头重取了") { requestsTo(HttpMethod.Get, "/p1/indexes/1/related") > relatedFetches }
        awaitUntil("重取落定") { !vm.uiState.value.entries.isLoading && vm.uiState.value.entries.total == 1 }
        assertEquals(listOf(12), vm.uiState.value.entries.items.map { it.id })
    }

    @Test
    fun `移出失败_行留着_失败提示`() = runBlocking {
        val vm = withContext(main.dispatcher) { detailsViewModel() }
        awaitUntil("作品收录到了") { vm.uiState.value.entries.items.size == 2 }
        writesFail = true

        withContext(main.dispatcher) { vm.removeEntry(vm.uiState.value.entries.items.first()) }
        awaitUntil("失败提示") { vm.failures.current.value != null }

        assertEquals(listOf(11, 12), vm.uiState.value.entries.items.map { it.id })
    }

    /** 写过之后重取头部还在路上时点了收藏：旧的回包（未收藏）不能把屏上的收藏改回去。 */
    @Test
    fun `重取头部在途时点了收藏_回包不覆盖收藏状态`() = runBlocking {
        val vm = withContext(main.dispatcher) { detailsViewModel() }
        awaitUntil("作品收录到了") { vm.uiState.value.entries.items.size == 2 }
        val gate = CompletableDeferred<Unit>().also { headGate = it }

        withContext(main.dispatcher) { vm.removeEntry(vm.uiState.value.entries.items.first()) }
        awaitUntil("头部重取发出了") { headRequests.get() >= 2 }
        withContext(main.dispatcher) { vm.toggleCollection() }
        awaitUntil("收藏写完") { !vm.uiState.value.isCollectionLoading }
        gate.complete(Unit)
        awaitUntil("头部重取落定，数量跟着少了一个") { vm.uiState.value.details.valueOrNull()?.total == 1 }

        val details = vm.uiState.value.details.valueOrNull()!!
        assertTrue(details.isCollected, "收藏状态只归收藏按钮管")
        assertEquals(1, details.collects)
    }

    @Test
    fun `收藏失败只回滚收藏字段 不覆盖期间编辑成功的目录资料`() = runBlocking {
        val vm = withContext(main.dispatcher) { detailsViewModel() }
        awaitUntil("目录资料到了") { vm.uiState.value.details.valueOrNull() != null }
        val gate = CompletableDeferred<Unit>().also { collectionGate = it }
        collectionFails = true
        withContext(main.dispatcher) { vm.toggleCollection() }
        awaitUntil("收藏请求已发出") { requestsTo(HttpMethod.Put, "/p1/collections/indexes/1") == 1 }
        withContext(main.dispatcher) {
            vm.editor.openEdit(requireNotNull(vm.uiState.value.details.valueOrNull()))
            vm.editor.change(IndexDraft(title = "更新后的目录", description = "更新后的简介", isPrivate = false))
            vm.editor.save()
        }
        awaitUntil("新资料已显示") {
            val details = vm.uiState.value.details.valueOrNull()
            details?.title == "更新后的目录" && details.description == "更新后的简介" && !details.isPrivate
        }
        gate.complete(Unit)
        awaitUntil("收藏失败已处理") { !vm.uiState.value.isCollectionLoading && vm.failures.current.value != null }

        val details = requireNotNull(vm.uiState.value.details.valueOrNull())
        assertEquals("更新后的目录", details.title)
        assertEquals("更新后的简介", details.description)
        assertFalse(details.isPrivate)
        assertFalse(details.isCollected)
        assertEquals(0, details.collects)
        assertEquals(1, requestsTo(HttpMethod.Patch, "/p1/indexes/1"))
    }

    @Test
    fun `没有并发编辑时收藏失败恢复原收藏状态与数量`() = runBlocking {
        val vm = withContext(main.dispatcher) { detailsViewModel() }
        awaitUntil("目录资料到了") { vm.uiState.value.details.valueOrNull() != null }
        collectionFails = true
        withContext(main.dispatcher) { vm.toggleCollection() }
        awaitUntil("收藏失败已处理") { !vm.uiState.value.isCollectionLoading && vm.failures.current.value != null }
        val details = requireNotNull(vm.uiState.value.details.valueOrNull())
        assertEquals("私密 test", details.title)
        assertFalse(details.isCollected)
        assertEquals(0, details.collects)
    }

    @Test
    fun `名称超过上限的输入不收`() = runBlocking {
        val vm = withContext(main.dispatcher) { listViewModel() }

        withContext(main.dispatcher) {
            vm.editor.openNew()
            vm.editor.change(IndexDraft(title = "字".repeat(80)))
            vm.editor.change(IndexDraft(title = "字".repeat(81)))
        }

        assertEquals("字".repeat(80), vm.uiState.value.editor?.draft?.title)
    }

    @Test
    fun `编辑面板_名称空白或太长或没改动不能保存`() {
        val original = IndexDraft(title = "目录", description = "介绍")
        val editing = IndexEditorState(indexId = 1, original = original)
        assertFalse(editing.canSave, "没改动")
        assertTrue(editing.copy(draft = original.copy(isPrivate = true)).canSave)
        assertFalse(editing.copy(draft = original.copy(title = "  ")).canSave, "名称空白")
        assertTrue(editing.copy(draft = original.copy(title = "字".repeat(80))).canSave, "正好 80 字")
        assertFalse(editing.copy(draft = original.copy(title = "字".repeat(81))).canSave, "超过 80 字")
        assertFalse(IndexEditorState(indexId = null, original = IndexDraft()).canSave, "新建还没写名称")
        assertFalse(editing.copy(draft = original.copy(isPrivate = true), saving = true).canSave, "在途")
    }
}
