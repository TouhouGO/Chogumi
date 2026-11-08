package io.github.touhougo.chogumi.feature.profile

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.IndexRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.network.api.IndexApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule

/**
 * 收录面板：打开时已收录的目录预先勾上；保存只提交改动过的目录（新勾的收录、取消勾的移出），全部成功才收起；
 * 失败的勾选留着、面板不收，再保存只重交失败的；就地新建的目录排在最前并勾上。
 */
class IndexPickerViewModelTest {
    @get:Rule
    val resources = TestResources()

    private val main = TestMain()
    private val viewModels = mutableListOf<IndexPickerViewModel>()
    private val requests = ConcurrentRequestRecorder()

    /** 收录到这些目录时服务端回 500。 */
    private val failingAdds: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 为真时新建目录回 500。 */
    @Volatile
    private var createFails = false

    private val creates = AtomicInteger()

    /** 假服务端：我有目录 1（已收录作品 265，收录行 9001）与目录 2（没收）。 */
    private val repository = run {
        val engine = MockEngine { request ->
            requests.record(request)
            val json = headersOf("Content-Type", ContentType.Application.Json.toString())
            val path = request.url.encodedPath
            when {
                request.method == HttpMethod.Get && path == "/p1/users/fixture_user/indexes" ->
                    respond("""{"data":[${slimIndex(1, "公开 test")},${slimIndex(2, "私密 test")}],"total":2}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Get && path == "/p1/indexes/1/related" ->
                    respond("""{"data":[${relatedRow(9001, 265)}],"total":1}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Get && path.matches(Regex("/p1/indexes/\\d+/related")) ->
                    respond("""{"data":[],"total":0}""", HttpStatusCode.OK, json)
                request.method == HttpMethod.Put && path.matches(Regex("/p1/indexes/\\d+/related")) -> {
                    val indexId = path.split("/")[3].toInt()
                    if (indexId in failingAdds) respond("{}", HttpStatusCode.InternalServerError, json)
                    else respond("""{"id":${5000 + indexId}}""", HttpStatusCode.OK, json)
                }
                request.method == HttpMethod.Delete && path == "/p1/indexes/1/related/9001" -> respond("{}", HttpStatusCode.OK, json)
                request.method == HttpMethod.Post && path == "/p1/indexes" -> {
                    creates.incrementAndGet()
                    if (createFails) respond("{}", HttpStatusCode.InternalServerError, json) else respond("""{"id":77}""", HttpStatusCode.OK, json)
                }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        val client = resources.apiClient(engine)
        IndexRepository(IndexApi(client), UserApi(client), testTokenStore(resources, userId = 1))
    }

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() = main.uninstall(viewModels.map { it.viewModelScope })

    private suspend fun open(): IndexPickerViewModel {
        val vm = withContext(main.dispatcher) { IndexPickerViewModel(TARGET, repository) }.also { viewModels += it }
        awaitUntil("勾选初值到了") { vm.uiState.value.indexes !is Load.Pending }
        requests.clear()
        return vm
    }

    private fun IndexPickerViewModel.picked(): Map<Int, Boolean> {
        val state = uiState.value
        return state.indexes.valueOrNull().orEmpty().associate { it.index.id to isPicked(it, state.toggled) }
    }

    /** 写请求（收录、移出、新建），按「方法 路径」记；逐项提交，但断言不看顺序。 */
    private fun writes(): Set<String> =
        requests.filter { it.method != HttpMethod.Get }.map { "${it.method.value} ${it.url.encodedPath}" }.toSet()

    @Test
    fun `打开时已收录的目录预先勾上`() = runBlocking<Unit> {
        val vm = open()

        assertEquals(mapOf(1 to true, 2 to false), vm.picked())
        assertFalse(vm.uiState.value.saved)
    }

    @Test
    fun `保存只提交改动过的目录_新勾的收录_取消勾的移出_全部成功后收起`() = runBlocking<Unit> {
        val vm = open()
        withContext(main.dispatcher) {
            vm.toggle(1)
            vm.toggle(2)
            vm.save()
        }
        awaitUntil("提交落定") { !vm.uiState.value.saving }

        assertEquals(setOf("DELETE /p1/indexes/1/related/9001", "PUT /p1/indexes/2/related"), writes())
        val state = vm.uiState.value
        assertTrue(state.saved, "全部成功才收起")
        assertEquals(emptySet(), state.toggled)
        assertEquals(mapOf(1 to null, 2 to 5002), state.indexes.valueOrNull()!!.associate { it.index.id to it.relatedId })
        assertEquals(mapOf(1 to false, 2 to true), vm.picked())
    }

    @Test
    fun `点两次回到原样的目录不提交`() = runBlocking<Unit> {
        val vm = open()
        withContext(main.dispatcher) {
            vm.toggle(1)
            vm.toggle(1)
            vm.toggle(2)
            vm.save()
        }
        awaitUntil("提交落定") { !vm.uiState.value.saving }

        assertEquals(setOf("PUT /p1/indexes/2/related"), writes())
    }

    @Test
    fun `一项失败_勾选留着面板不收_再保存只重交失败的那项`() = runBlocking<Unit> {
        val vm = open()
        failingAdds += 2
        withContext(main.dispatcher) {
            vm.toggle(1)
            vm.toggle(2)
            vm.save()
        }
        // 失败提示在提交落定之后才发：两个都到了才算这一轮结束
        awaitUntil("提交落定且失败已提示") { !vm.uiState.value.saving && vm.failures.current.value != null }

        val failed = vm.uiState.value
        assertFalse(failed.saved, "有失败时面板不收")
        assertEquals(setOf(2), failed.toggled, "成功的移出已落下，失败的收录还在待提交里")
        assertEquals(mapOf(1 to false, 2 to true), vm.picked(), "用户的勾选不丢")
        assertNotNull(vm.failures.current.value, "失败在面板里提示")

        failingAdds.clear()
        requests.clear()
        withContext(main.dispatcher) { vm.save() }
        awaitUntil("重交落定") { !vm.uiState.value.saving }

        assertEquals(setOf("PUT /p1/indexes/2/related"), writes(), "已成功的移出不再发")
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `就地新建的目录排在最前并勾上_保存时收进去`() = runBlocking<Unit> {
        val vm = open()
        val created = AtomicInteger()
        withContext(main.dispatcher) { vm.create("  新目录 ", isPrivate = true) { created.incrementAndGet() } }
        awaitUntil("新建落定") { created.get() > 0 }

        val state = vm.uiState.value
        assertEquals(listOf(77, 1, 2), state.indexes.valueOrNull()!!.map { it.index.id })
        assertEquals("新目录", state.indexes.valueOrNull()!!.first().index.title)
        assertEquals(mapOf(77 to true, 1 to true, 2 to false), vm.picked())
        val body = (requests.single { it.method == HttpMethod.Post }.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(""""private":true""" in body && """"title":"新目录"""" in body, body)

        requests.clear()
        withContext(main.dispatcher) { vm.save() }
        awaitUntil("提交落定") { !vm.uiState.value.saving }
        assertEquals(setOf("PUT /p1/indexes/77/related"), writes())
    }

    @Test
    fun `新建在途时不能保存_建好后一起交出去_新目录不会只勾不交`() = runBlocking<Unit> {
        val vm = open()
        val created = AtomicInteger()
        val refused = withContext(main.dispatcher) {
            vm.toggle(1)
            vm.create("新目录", isPrivate = false) { created.incrementAndGet() }
            vm.save()
            vm.uiState.value
        }
        assertTrue(refused.creating)
        assertFalse(refused.saving, "新建在途时保存不开始")
        assertFalse(canSaveIndex(refused), "按钮同样不能按")

        awaitUntil("新建落定") { created.get() > 0 }
        assertTrue(canSaveIndex(vm.uiState.value))
        withContext(main.dispatcher) { vm.save() }
        awaitUntil("提交落定") { vm.uiState.value.saved }
        assertEquals(setOf("POST /p1/indexes", "DELETE /p1/indexes/1/related/9001", "PUT /p1/indexes/77/related"), writes())
    }

    @Test
    fun `新建失败_不回调_不加行_提示原因`() = runBlocking<Unit> {
        val vm = open()
        createFails = true
        val created = AtomicInteger()
        withContext(main.dispatcher) { vm.create("新目录", isPrivate = false) { created.incrementAndGet() } }
        awaitUntil("新建落定且失败已提示") { creates.get() == 1 && !vm.uiState.value.creating && vm.failures.current.value != null }

        assertEquals(0, created.get(), "失败时输入框不收、名称留着")
        assertEquals(listOf(1, 2), vm.uiState.value.indexes.valueOrNull()!!.map { it.index.id })
        assertNotNull(vm.failures.current.value)
    }

    private fun slimIndex(id: Int, title: String) =
        """{"id":$id,"uid":1,"type":0,"title":"$title","private":false,"total":1,"stats":{"subject":{}},"createdAt":1,"updatedAt":1}"""

    private fun relatedRow(rowId: Int, sid: Int) =
        """{"id":$rowId,"cat":0,"rid":1,"type":2,"sid":$sid,"order":0,"comment":"","award":"","createdAt":1,
            "subject":{"id":$sid,"name":"s$sid","nameCN":"","type":2,"info":"","metaTags":[],
            "rating":{"rank":0,"count":[],"score":0,"total":0},"locked":false,"nsfw":false}}"""

    private companion object {
        val TARGET = IndexTarget(IndexCategory.SUBJECT, 265)
    }
}
