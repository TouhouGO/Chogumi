package io.github.touhougo.chogumi.feature.explore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfDao
import io.github.touhougo.chogumi.core.data.db.explore.ExploreShelfEntity
import io.github.touhougo.chogumi.core.data.repository.ExploreLayoutRepository
import io.github.touhougo.chogumi.core.data.repository.FakeExploreShelfDao
import io.github.touhougo.chogumi.core.data.repository.FakeTagCatalogDao
import io.github.touhougo.chogumi.core.data.repository.NsfwFilter
import io.github.touhougo.chogumi.core.data.repository.NsfwPreferenceRepository
import io.github.touhougo.chogumi.core.data.repository.TagCatalogRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testWebClient
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.WorkShelfStyle
import io.github.touhougo.chogumi.core.model.defaultShelfLayout
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.github.touhougo.chogumi.core.ui.arrange.ArrangeSheet
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 真实布局仓库的本地持久化可失败或挂起，编辑草稿只能在成功后关闭。 */
class ShelfSaveTest {
    private val main = TestMain()
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scopes = mutableListOf(io)
    private val clients = mutableListOf<HttpClient>()
    private val engines = mutableListOf<HttpClientEngine>()
    private val directories = mutableListOf<File>()

    @BeforeTest
    fun setUp() = main.install()

    @AfterTest
    fun tearDown() {
        cancelAndAwait(scopes)
        clients.forEach { it.close() }
        engines.forEach { it.close() }
        cancelAndAwait(clients.map { CoroutineScope(it.coroutineContext) } + engines.map { CoroutineScope(it.coroutineContext) })
        main.uninstall(scopes)
        directories.forEach { it.deleteRecursively() }
    }

    private class FaultDao(private val base: FakeExploreShelfDao = FakeExploreShelfDao()) : ExploreShelfDao by base {
        val attempts = AtomicInteger()
        var fail = true
        var gate = CompletableDeferred<Unit>()
        override suspend fun replaceType(subjectType: Int, rows: List<ExploreShelfEntity>) {
            attempts.incrementAndGet()
            gate.await()
            if (fail) throw IOException("布局写入失败")
            base.replaceType(subjectType, rows)
        }
    }

    private class Harness(val arranger: ShelfArranger, val state: MutableStateFlow<ShelfArrangeState>, val failures: FailureNotices, val dao: FaultDao, val scope: CoroutineScope) {
        fun actionJobs(action: () -> Unit): List<Job> {
            val before = scope.coroutineContext.job.children.toSet()
            action()
            return scope.coroutineContext.job.children.filter { it !in before }.toList()
        }
    }

    private fun harness(): Harness {
        val dir = File(System.getProperty("java.io.tmpdir"), "shelf-save-${System.nanoTime()}").apply { mkdirs(); directories += this }
        fun store(name: String) = PreferenceDataStoreFactory.create(scope = io) { File(dir, "$name.preferences_pb") }
        val tokens = TokenStore(store("tokens"))
        val client = testApiClient { error("保存货架不请求网络") }.also { clients += it; engines += it.engine }
        val web = testWebClient { error("保存货架不请求网页") }.also { clients += it; engines += it.engine }
        val nsfw = NsfwPreferenceRepository(store("nsfw"), PrivacyApi(client), tokens, testWebSessionRepository(tokens, web))
        val state = MutableStateFlow(ShelfArrangeState())
        val scope = CoroutineScope(main.dispatcher + SupervisorJob()).also { scopes += it }
        val failures = FailureNotices()
        val dao = FaultDao()
        val arranger = ShelfArranger(
            scope, state, { state.value }, { change -> state.update(change) }, failures, ExploreLayoutRepository(dao),
            TagCatalogRepository(NextApi(client), FakeTagCatalogDao(), NsfwFilter(nsfw), AppClock { 0L }),
        )
        arranger.openEditor(SubjectType.ANIME, defaultShelfLayout(SubjectType.ANIME).single { it.id == "preset:奇幻" })
        arranger.setName("保留的草稿")
        arranger.setStyle(WorkShelfStyle.SPINE)
        return Harness(arranger, state, failures, dao, scope)
    }

    private suspend fun List<Job>.finished() = withTimeout(WAIT_BUDGET_MS) { forEach { it.join() } }

    @Test
    fun `保存失败不关闭或丢草稿_重试成功才关闭`() = runBlocking(main.dispatcher) {
        val h = harness()
        val draft = h.state.value.editor
        val jobs = h.actionJobs { h.arranger.save("保留的草稿") }
        h.dao.gate.complete(Unit)
        jobs.finished()
        assertEquals(draft, h.state.value.editor)
        assertTrue(h.state.value.closing.isEmpty(), "失败不得请求关闭编辑面板")
        assertNotNull(h.failures.current.value)
        h.dao.fail = false
        h.actionJobs { h.arranger.save("保留的草稿") }.finished()
        assertEquals(setOf(ArrangeSheet.EDITOR), h.state.value.closing, "只有成功后关闭")
        assertEquals(2, h.dao.attempts.get())
    }

    @Test
    fun `编辑移除失败不关闭草稿_成功才关闭`() = runBlocking(main.dispatcher) {
        val h = harness()
        val draft = h.state.value.editor
        val jobs = h.actionJobs { h.arranger.removeEdited() }
        h.dao.gate.complete(Unit)
        jobs.finished()
        assertEquals(draft, h.state.value.editor)
        assertTrue(h.state.value.closing.isEmpty(), "移除失败必须留在编辑面板")
        h.dao.fail = false
        h.actionJobs { h.arranger.removeEdited() }.finished()
        assertEquals(setOf(ArrangeSheet.EDITOR), h.state.value.closing)
    }

    @Test
    fun `保存挂起时重复保存不排第二次写`() = runBlocking(main.dispatcher) {
        val h = harness()
        h.dao.fail = false
        val first = h.actionJobs { h.arranger.save("草稿") }
        val second = h.actionJobs { h.arranger.save("另一次草稿") }
        h.dao.gate.complete(Unit)
        (first + second).finished()
        assertEquals(1, h.dao.attempts.get(), "在途保存只发起一次持久化")
    }
}
