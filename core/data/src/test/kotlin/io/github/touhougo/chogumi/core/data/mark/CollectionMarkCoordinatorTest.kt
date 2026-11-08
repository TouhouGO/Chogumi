package io.github.touhougo.chogumi.core.data.mark

import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionDao
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.progress.CommitGate
import io.github.touhougo.chogumi.core.data.repository.CollectionEditingImpl
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.COLLECTION_MARK_WINDOW_NANOS
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionMarkFailure
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionSaveResult
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.MarkOrigin
import io.github.touhougo.chogumi.core.model.PendingMark
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * [CollectionMarkCoordinator]：标了先进窗口、窗口走完只写一次，改选合并、标回原状态即撤销、撤销不写，
 * 标另一部时上一部立刻提交，提交途中海报已是新状态，失败回到原状态并只向发起页报错；
 * 与同一部的收藏面板保存（[CollectionEditingImpl.save]）共用条目锁，按先后写到服务端。
 */
class CollectionMarkCoordinatorTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    private val scope = resources.own(CoroutineScope(Dispatchers.Default + SupervisorJob()))

    @AfterTest
    fun tearDown() {
        io.github.touhougo.chogumi.core.data.repository.cancelAndAwait(listOf(scope))
    }

    private companion object {
        const val ACCOUNT_ID = 7
        const val OTHER_ACCOUNT_ID = 8
        const val FIRST = 31
        const val SECOND = 32
        const val CLOCK_NOW = 1_000L

        /**
         * 面板保存卡在服务端时，给后到的标记多久去抢先：保存不持条目锁时标记几毫秒就到，持锁时它不会在这段时间里到。
         * 只决定没持锁的写法能不能被抓到，不影响持锁时的结果。
         */
        const val OVERTAKE_WINDOW_MS = 500L
    }

    /** 模拟服务端：按到达顺序记下收藏 PATCH（条目 id 与请求体）。 */
    private class Server {
        val patches = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        @Volatile var failWrites = false
        @Volatile var loseResponse = false
        @Volatile var serverType = CollectionType.WISH.value
        var onPatch: (suspend () -> Unit)? = null
    }

    private fun engine(server: Server) = MockEngine { request ->
        val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())
        val path = request.url.encodedPath
        if (path.startsWith("/v0/users/fixture_user/collections/")) {
            val id = path.substringAfterLast('/').toInt()
            return@MockEngine respond(
                """{"subject_id":$id,"subject_type":2,"type":${server.serverType},"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val id = path.removePrefix("/v0/users/-/collections/").toIntOrNull()
        if (id == null) return@MockEngine respond("", HttpStatusCode.NoContent)
        server.onPatch?.invoke()
        if (server.failWrites) return@MockEngine respond("""{"title":"Internal"}""", HttpStatusCode.InternalServerError, jsonHeaders)
        server.patches += id to request.body.toByteArray().decodeToString()
        if (server.loseResponse) {
            server.serverType = Json.decodeFromString<Map<String, Int>>(server.patches.last().second).getValue("type")
            throw java.net.SocketTimeoutException("response lost")
        }
        respond("", HttpStatusCode.NoContent)
    }

    private inner class Fixture(
        val server: Server = Server(),
        /** 窗口：默认关着，用例看完窗口里的样子再打开。 */
        val gate: CommitGate = CommitGate(open = false),
    ) {
        val collectionDao = FakeUserCollectionDao()
        private val client = resources.apiClient(engine(server))
        val store = testTokenStore(scope = scope).also { runBlocking { it.completeSession(it.beginSession("token"), ACCOUNT_ID, "fixture_user") } }
        val collections = testCollections(resources, CollectionApi(client), FakeSubjectDao(), collectionDao, store, FakeEpisodeWatchStateDao())
        private val subjects = testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        private val orchestrator = collections.progressOrchestrator(subjects)
        val editing = collections.editing(subjects, orchestrator)
        val coordinator = collections.markCoordinator(orchestrator, scope, { CLOCK_NOW }, gate::await)
        val origin = MarkOrigin()

        init {
            runBlocking {
                listOf(FIRST, SECOND).forEach { id ->
                    collectionDao.upsert(
                        UserCollectionEntity(
                            subjectId = id, subjectType = SubjectType.ANIME.value, type = CollectionType.WISH.value,
                            accountId = ACCOUNT_ID, updatedAt = 1L,
                        ),
                    )
                }
                await("本地收藏表送到") { it.types[FIRST] == CollectionType.WISH && it.types[SECOND] == CollectionType.WISH }
            }
        }

        suspend fun await(what: String, condition: (CollectionMarks) -> Boolean): CollectionMarks =
            withTimeoutOrNull(WAIT_BUDGET_MS) { coordinator.marks.first(condition) } ?: fail("等待「$what」超时：${coordinator.marks.value}")

        /** 等到 [id] 在本地收藏表里写成 [type]：提交成功后才写，写完这一轮就结束了（之前海报上显示的是在途叠加）。 */
        suspend fun awaitStored(id: Int, type: CollectionType) =
            awaitUntil("$id 写成 $type") { collectionDao.store[ACCOUNT_ID to id]?.type == type.value }

        fun patchedTypes(): List<Pair<Int, Int>> = server.patches.map { (id, body) ->
            id to Json.decodeFromString<Map<String, Int>>(body).getValue("type")
        }
    }

    @Test
    fun `标了先进窗口_窗口里不写_走完只写一次并显示新状态`() = runBlocking {
        val f = Fixture()
        f.coordinator.mark(f.origin, FIRST, CollectionType.DONE)
        awaitUntil("协调器在等窗口走完") { f.gate.awaited.size == 1 }
        val inWindow = f.await("进了窗口") { it.pending != null }
        assertEquals(PendingMark(FIRST, CollectionType.DONE, CLOCK_NOW + COLLECTION_MARK_WINDOW_NANOS), inWindow.pending)
        assertEquals(CollectionType.WISH, inWindow.types[FIRST], "窗口里角标仍是原来的状态，胶囊写着新状态")
        assertEquals(emptyList(), f.patchedTypes())

        f.gate.open()
        f.awaitStored(FIRST, CollectionType.DONE)
        f.await("提交完、显示新状态") { it.pending == null && it.types[FIRST] == CollectionType.DONE }
        assertEquals(listOf(FIRST to CollectionType.DONE.value), f.patchedTypes())
    }

    @Test
    fun `窗口里改选同一部_重新计时_最终只写最后一次`() = runBlocking {
        val f = Fixture()
        f.coordinator.mark(f.origin, FIRST, CollectionType.DONE)
        f.await("第一次进窗口") { it.pending?.type == CollectionType.DONE }
        f.coordinator.mark(f.origin, FIRST, CollectionType.DROPPED)
        f.await("改选进窗口") { it.pending?.type == CollectionType.DROPPED }
        f.gate.open()
        f.awaitStored(FIRST, CollectionType.DROPPED)
        assertEquals(listOf(FIRST to CollectionType.DROPPED.value), f.patchedTypes())
    }

    @Test
    fun `窗口里标回原来的状态等于撤销_不写`() = runBlocking {
        val f = Fixture()
        f.coordinator.mark(f.origin, FIRST, CollectionType.DONE)
        f.await("进窗口") { it.pending != null }
        f.coordinator.mark(f.origin, FIRST, CollectionType.WISH)
        f.await("窗口撤掉") { it.pending == null }
        f.gate.open()
        // 再标另一部并等它写完：之前那一笔若还在会先于它写出
        f.coordinator.mark(f.origin, SECOND, CollectionType.DOING)
        f.awaitStored(SECOND, CollectionType.DOING)
        assertEquals(listOf(SECOND to CollectionType.DOING.value), f.patchedTypes())
        assertEquals(CollectionType.WISH, f.coordinator.marks.value.types[FIRST])
    }

    @Test
    fun `撤销后不写`() = runBlocking {
        val f = Fixture()
        f.coordinator.mark(f.origin, FIRST, CollectionType.ON_HOLD)
        f.await("进窗口") { it.pending != null }
        f.coordinator.undo(FIRST)
        f.await("撤掉") { it.pending == null }
        f.gate.open()
        f.coordinator.mark(f.origin, SECOND, CollectionType.DONE)
        f.awaitStored(SECOND, CollectionType.DONE)
        assertEquals(listOf(SECOND to CollectionType.DONE.value), f.patchedTypes())
        assertEquals(CollectionType.WISH, f.coordinator.marks.value.types[FIRST])
    }

    @Test
    fun `去标另一部_上一部不等窗口立刻提交`() = runBlocking {
        val f = Fixture()
        f.coordinator.mark(f.origin, FIRST, CollectionType.DONE)
        f.await("第一部进窗口") { it.pending?.subjectId == FIRST }
        f.coordinator.mark(f.origin, SECOND, CollectionType.DOING)
        f.awaitStored(FIRST, CollectionType.DONE)
        val marks = f.await("第二部在窗口里") { it.pending?.subjectId == SECOND }
        assertEquals(CollectionType.DOING, marks.pending?.type)
        assertEquals(listOf(FIRST to CollectionType.DONE.value), f.patchedTypes(), "闸门还关着：只有被顶掉的那一部写了")
    }

    @Test
    fun `提交途中海报已是新状态`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val server = Server().apply { onPatch = { reached.complete(Unit); release.await() } }
        val f = Fixture(server = server, gate = CommitGate(open = true))
        f.coordinator.mark(f.origin, FIRST, CollectionType.DONE)
        reached.await()
        f.await("在途时显示新状态") { it.pending == null && it.types[FIRST] == CollectionType.DONE }
        assertEquals(CollectionType.WISH.value, f.collectionDao.store[ACCOUNT_ID to FIRST]?.type, "本地表还没写")
        release.complete(Unit)
        f.awaitStored(FIRST, CollectionType.DONE)
    }

    @Test
    fun `标记响应丢失显示服务端状态并报告失败`() = runBlocking {
        val f = Fixture(server = Server().apply { loseResponse = true }, gate = CommitGate(open = true))
        val failure = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(WAIT_BUDGET_MS) { f.coordinator.failures.first() }
        }
        f.coordinator.mark(f.origin, FIRST, CollectionType.DONE)
        assertEquals(Failure.Timeout, failure.await().failure)
        f.awaitStored(FIRST, CollectionType.DONE)
        f.await("服务端状态显示") { it.pending == null && it.types[FIRST] == CollectionType.DONE }
        assertEquals(listOf(FIRST to CollectionType.DONE.value), f.patchedTypes())
    }

    @Test
    fun `面板响应丢失保留失败结果并更新服务端状态`() = runBlocking {
        val f = Fixture(server = Server().apply { loseResponse = true })
        val work = CollectionWork(
            subject = SubjectBo(id = FIRST, type = SubjectType.ANIME, name = "first", nameCn = ""),
            collection = UserCollectionBo(subjectId = FIRST, subjectType = SubjectType.ANIME, type = CollectionType.WISH),
            episodes = Load.Ready(emptyList()), watchStates = emptyMap(),
        )
        assertEquals(
            CollectionSaveResult.Failed(CollectionSaveResult.Step.RECORD, Failure.Timeout),
            f.editing.save(work, CollectionEdits(type = CollectionType.DONE, finishAll = false)),
        )
        f.awaitStored(FIRST, CollectionType.DONE)
        assertEquals(listOf(FIRST to CollectionType.DONE.value), f.patchedTypes())
    }

    @Test
    fun `提交失败回到原状态_向发起页报告`() = runBlocking {
        val server = Server().apply { failWrites = true }
        val f = Fixture(server = server, gate = CommitGate(open = true))
        val failure = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(WAIT_BUDGET_MS) { f.coordinator.failures.first() }
        }
        f.coordinator.mark(f.origin, FIRST, CollectionType.DROPPED)
        val reported: CollectionMarkFailure = failure.await()
        assertSame(f.origin, reported.origin)
        assertEquals(FIRST, reported.subjectId)
        f.await("回到原状态") { it.pending == null && it.types[FIRST] == CollectionType.WISH }
        assertEquals(CollectionType.WISH.value, f.collectionDao.store[ACCOUNT_ID to FIRST]?.type)
    }

    @Test
    fun `窗口里换了账号_窗口走完以会话已变结束_不写进新账号`() = runBlocking {
        val f = Fixture()
        val failure = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(WAIT_BUDGET_MS) { f.coordinator.failures.first() }
        }
        f.coordinator.mark(f.origin, FIRST, CollectionType.DONE)
        f.await("进窗口") { it.pending != null }
        f.store.completeSession(f.store.beginSession("other-token"), ACCOUNT_ID + 1, "other")

        f.gate.open()
        val reported: CollectionMarkFailure = failure.await()
        assertSame(f.origin, reported.origin)
        assertEquals(Failure.SessionChanged, reported.failure)
        f.await("回到原状态") { it.pending == null && it.types[FIRST] != CollectionType.DONE }
        assertEquals(emptyList(), f.patchedTypes(), "一笔都没写到服务端")
    }

    @Test
    fun `同一部先存收藏面板再长按标记_面板那一笔写完标记才写`() = runBlocking {
        val saveHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val markArrived = CompletableDeferred<Unit>()
        // 第一笔（面板）到了服务端就卡住，直到放行；之后到的（标记）直接写
        val server = Server().apply { onPatch = { if (saveHeld.complete(Unit)) release.await() else markArrived.complete(Unit) } }
        val f = Fixture(server = server, gate = CommitGate(open = true))
        val work = CollectionWork(
            subject = SubjectBo(id = FIRST, type = SubjectType.ANIME, name = "first", nameCn = ""),
            collection = UserCollectionBo(subjectId = FIRST, subjectType = SubjectType.ANIME, type = CollectionType.WISH),
            episodes = Load.Ready(emptyList()),
            watchStates = emptyMap(),
        )
        val saved = async { f.editing.save(work, CollectionEdits(type = CollectionType.DONE, finishAll = false)) }
        saveHeld.await()

        f.coordinator.mark(f.origin, FIRST, CollectionType.DROPPED)
        f.await("标记已开始提交") { it.pending == null && it.types[FIRST] == CollectionType.DROPPED }
        withTimeoutOrNull(OVERTAKE_WINDOW_MS) { markArrived.await() }
        release.complete(Unit)

        assertEquals(CollectionSaveResult.Saved, saved.await())
        awaitUntil("两笔都写到服务端") { server.patches.size == 2 }
        assertEquals(
            listOf(FIRST to CollectionType.DONE.value, FIRST to CollectionType.DROPPED.value), f.patchedTypes(),
            "先到的面板保存整笔写完，后到的标记才写：服务端最后是标记的状态",
        )
        f.awaitStored(FIRST, CollectionType.DROPPED)
    }

    @Test
    fun `读不出本机收藏表的账号_进程不崩_换号后海报照常`() = runBlocking {
        val escaped = Collections.synchronizedList(mutableListOf<Throwable>())
        val appScope = resources.own(CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped += e }))
        val rows = FakeUserCollectionDao()
        val thrown = CompletableDeferred<Unit>()
        val dao = object : UserCollectionDao by rows {
            override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> =
                if (accountId == ACCOUNT_ID) {
                    flow { thrown.complete(Unit); throw IllegalStateException("database disk image is malformed") }
                } else {
                    rows.observeCurrent(accountId)
                }
        }
        rows.upsert(
            UserCollectionEntity(
                subjectId = FIRST, subjectType = SubjectType.ANIME.value, type = CollectionType.DONE.value,
                accountId = OTHER_ACCOUNT_ID, updatedAt = 1L,
            ),
        )
        val client = resources.apiClient(engine(Server()))
        val store = testTokenStore(scope = appScope).also { it.completeSession(it.beginSession("token"), ACCOUNT_ID, "fixture_user") }
        val collections = testCollections(resources, CollectionApi(client), FakeSubjectDao(), dao, store, FakeEpisodeWatchStateDao())
        val subjects = testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources))
        val coordinator = collections.markCoordinator(collections.progressOrchestrator(subjects), appScope, { CLOCK_NOW }, CommitGate(open = false)::await)
        // 旧账号的收藏表确实被读过、抛过，再换号
        withTimeout(WAIT_BUDGET_MS) { thrown.await() }

        store.completeSession(store.beginSession("other-token"), OTHER_ACCOUNT_ID, "other")

        withTimeout(WAIT_BUDGET_MS) { coordinator.marks.first { it.types[FIRST] == CollectionType.DONE } }
        assertEquals(emptyList(), escaped.toList(), "收藏表读失败不得漏到应用级作用域")
    }
}
