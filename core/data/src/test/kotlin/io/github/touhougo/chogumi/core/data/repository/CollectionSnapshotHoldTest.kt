package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.progress.CommitGate
import io.github.touhougo.chogumi.core.data.progress.ProgressOrigin
import io.github.touhougo.chogumi.core.data.progress.ProgressSubject
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Rule

/** 服务器全量快照不能覆盖或删除仍由本地进度动作占用的收藏行。 */
class CollectionSnapshotHoldTest {
    @get:Rule
    val resources = TestResources()

    private suspend fun verify(fullSync: Boolean, empty: Boolean) {
        val tokens = testTokenStore(resources, userId = 7)
        val client = resources.apiClient(MockEngine { request ->
            val type = request.url.parameters["subject_type"]!!.toInt()
            val rows = if (type == SubjectType.BOOK.value && !empty) {
                """{"subject_id":9,"subject_type":1,"rate":0,"type":3,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}"""
            } else ""
            respond("""{"data":[$rows],"total":${if (rows.isEmpty()) 0 else 1},"limit":100,"offset":0}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val dao = FakeUserCollectionDao()
        val collections = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, tokens, FakeEpisodeWatchStateDao())
        dao.upsert(UserCollectionEntity(9, SubjectType.BOOK.value, CollectionType.DOING.value, epStatus = 1, updatedAt = 1L, accountId = 7))
        collections.holds.hold(9)
        if (fullSync) assertEquals(Outcome.Success(Unit), collections.sync.refreshAllUserCollections(force = true))
        else collections.store.getUserCollections(SubjectType.BOOK, CollectionType.DOING, refresh = true).last()
        assertEquals(1, dao.store[7 to 9]?.epStatus, "旧值或空快照不得覆盖正在提交的本地进度")
    }

    @Test
    fun `两次书籍加一之间旧快照不能丢掉首次进度`() = runBlocking {
        val tokens = testTokenStore(resources, userId = 7)
        val scope = resources.own(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val submitted = CompletableDeferred<Int>()
        val client = resources.apiClient(MockEngine { request ->
            if (request.method == HttpMethod.Patch) {
                submitted.complete(Json.decodeFromString<Map<String, Int>>(request.body.toByteArray().decodeToString()).getValue("ep_status"))
                respond("", HttpStatusCode.NoContent)
            } else respond("""{"data":[{"subject_id":9,"subject_type":1,"rate":0,"type":3,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":100,"offset":0}""",
                HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val subjectsDao = FakeSubjectDao()
        val dao = FakeUserCollectionDao()
        val collections = testCollections(resources, CollectionApi(client), subjectsDao, dao, tokens, FakeEpisodeWatchStateDao())
        val subjects = testSubjectRepository(resources, SubjectApi(client), NextApi(client), subjectsDao, FakeEpisodeDao(), FakeSubjectSectionDao(),
            testNsfwFilter(resources), tokenStore = tokens)
        val gate = CommitGate()
        val progress = collections.epTapCoordinator(collections.progressOrchestrator(subjects), scope, { 0L }, gate::await)
        dao.upsert(UserCollectionEntity(9, 1, 3, updatedAt = 1L, accountId = 7))
        val book = ProgressSubject(9, SubjectType.BOOK, emptyList(), totalEps = 10)
        progress.advance(ProgressOrigin.HOME, book, LocalDate.of(2026, 10, 24))
        awaitUntil("首个窗口已建立") { progress.pending.value[9]?.count == 1 }
        collections.store.getUserCollections(SubjectType.BOOK, CollectionType.DOING, refresh = true).last()
        progress.advance(ProgressOrigin.HOME, book, LocalDate.of(2026, 10, 24))
        awaitUntil("第二个窗口已合并") { progress.pending.value[9]?.count == 2 }
        gate.open()
        assertEquals(2, withTimeout(WAIT_BUDGET_MS) { submitted.await() })
        awaitUntil("合并提交收尾") { 9 !in progress.active.value }
        assertEquals(2, dao.store[7 to 9]?.epStatus)
    }

    @Test
    fun `分类旧快照保留占用进度`() = runBlocking { verify(fullSync = false, empty = false) }

    @Test
    fun `分类空快照保留占用收藏行`() = runBlocking { verify(fullSync = false, empty = true) }

    @Test
    fun `全量同步旧快照保留占用进度`() = runBlocking { verify(fullSync = true, empty = false) }

    @Test
    fun `全量同步空分区保留占用收藏行`() = runBlocking { verify(fullSync = true, empty = true) }
}
