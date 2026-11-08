package io.github.touhougo.chogumi.core.data.progress

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testNsfwFilter
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EP_COMMIT_DELAY_NANOS
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.model.v0.BatchEpisodeStatusDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * [EpTapCoordinator]：乐观更新、合并提交、失败回滚、离开页面不丢写、刷新不覆盖乐观状态。
 */
class EpTapCoordinatorTest {
    @get:org.junit.Rule
    val resources = io.github.touhougo.chogumi.core.data.repository.TestResources()


    /** 应用级作用域里没人接住的异常：生产的 appScope 没有处理器，落到这里就是进程崩溃。 */
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())

    private val scope = resources.own(CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught += e }))

    @AfterTest
    fun tearDown() {
        cancelAndAwait(listOf(scope))
    }

    private companion object {
        const val ACCOUNT_ID = 7
        /** 提交窗口里换到的另一个账号。 */
        const val OTHER_ACCOUNT_ID = 70
        const val ANIME_ID = 8
        const val BOOK_ID = 9

        val TODAY: LocalDate = LocalDate.of(2026, 9, 29)
        const val SINGLE_EPISODE_PATH = "/v0/users/-/collections/-/episodes/"
        const val SPECIAL_ID = 201
    }

    private val special = EpisodeBo(id = SPECIAL_ID, subjectId = ANIME_ID, type = 1, name = "sp1", nameCn = "", sort = 1.0)

    /** 模拟服务端：逐集状态、收到的批量写入、收藏 PATCH。 */
    private class Server {
        val watchStates = Collections.synchronizedMap(mutableMapOf<Int, Int>())
        val batches = Collections.synchronizedList(mutableListOf<BatchEpisodeStatusDto>())
        val patches = Collections.synchronizedList(mutableListOf<String>())
        /** 单集 PUT：集 id 到状态值，按到达顺序。 */
        val singlePuts = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        @Volatile var failWrites = false
        @Volatile var failGetWatchStates = false
        var onGetWatchStates: (suspend () -> Unit)? = null
        var onPatchCollection: (suspend () -> Unit)? = null
        @Volatile var bookServerProgress = 0
        @Volatile var failBookRead = false
        var onBookRead: (suspend () -> Unit)? = null
        var onBatchWrite: (suspend () -> Unit)? = null
        /** 动画条目的收藏写入（未收藏时替用户建的那一笔）：请求体里的收藏状态值，按到达顺序。 */
        val animeCollectionTypes = Collections.synchronizedList(mutableListOf<Int>())
        var onAnimeCollection: (suspend () -> Unit)? = null
    }

    private val episodesJson = """
        {"data":[${(1..5).joinToString(",") { """{"id":${100 + it},"subject_id":$ANIME_ID,"type":0,"name":"ep$it","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":$it.0,"airdate":""}""" }}],
         "total":5,"limit":100,"offset":0}
    """.trimIndent()

    private val episodes = (1..5).map {
        EpisodeBo(id = 100 + it, subjectId = ANIME_ID, type = 0, name = "ep$it", nameCn = "", sort = it.toDouble())
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun engine(server: Server) = MockEngine { request ->
        val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())
        val path = request.url.encodedPath
        if (path == "/v0/users/fixture_user/collections/$BOOK_ID" && request.method == HttpMethod.Get) {
            if (server.failBookRead) return@MockEngine respond("", HttpStatusCode.Forbidden)
            server.onBookRead?.invoke()
            return@MockEngine respond(
                """{"subject_id":$BOOK_ID,"subject_type":1,"type":3,"rate":0,"comment":"","tags":[],"ep_status":${server.bookServerProgress},"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        if (path.startsWith(SINGLE_EPISODE_PATH)) {
            return@MockEngine if (server.failWrites) {
                respond("""{"title":"Internal"}""", HttpStatusCode.InternalServerError, jsonHeaders)
            } else {
                val type = json.decodeFromString<Map<String, Int>>(request.body.toByteArray().decodeToString()).getValue("type")
                val episodeId = path.removePrefix(SINGLE_EPISODE_PATH).toInt()
                server.singlePuts += episodeId to type
                server.watchStates[episodeId] = type
                respond("", HttpStatusCode.NoContent)
            }
        }
        when (path) {
            "/v0/episodes" -> respond(episodesJson, HttpStatusCode.OK, jsonHeaders)
            "/v0/users/-/collections/$ANIME_ID/episodes" -> if (request.method == HttpMethod.Get) {
                if (server.failGetWatchStates) {
                    respond("""{"title":"Internal"}""", HttpStatusCode.InternalServerError, jsonHeaders)
                } else {
                    server.onGetWatchStates?.invoke()
                    val items = server.watchStates.entries.joinToString(",") { (id, type) ->
                        """{"episode":{"id":$id,"type":0,"sort":0.0,"name":"","name_cn":"","duration":"","airdate":"","comment":0,"desc":"","disc":0},"type":$type,"updated_at":1000}"""
                    }
                    respond("""{"total":${server.watchStates.size},"limit":100,"offset":0,"data":[$items]}""", HttpStatusCode.OK, jsonHeaders)
                }
            } else if (server.failWrites) {
                respond("""{"title":"Internal"}""", HttpStatusCode.InternalServerError, jsonHeaders)
            } else {
                val body = json.decodeFromString<BatchEpisodeStatusDto>(request.body.toByteArray().decodeToString())
                server.batches += body
                body.episodeIds.forEach { server.watchStates[it] = body.type }
                server.onBatchWrite?.invoke()
                respond("", HttpStatusCode.NoContent)
            }
            "/v0/users/-/collections/$ANIME_ID" -> {
                server.onAnimeCollection?.invoke()
                server.animeCollectionTypes += json.decodeFromString<Map<String, Int>>(request.body.toByteArray().decodeToString()).getValue("type")
                respond("", HttpStatusCode.NoContent)
            }
            "/v0/users/-/collections/$BOOK_ID" -> if (server.failWrites) {
                respond("""{"title":"Internal"}""", HttpStatusCode.InternalServerError, jsonHeaders)
            } else {
                server.onPatchCollection?.invoke()
                server.patches += request.body.toByteArray().decodeToString()
                respond("", HttpStatusCode.NoContent)
            }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    private inner class Fixture(
        val server: Server = Server(),
        val collectionDao: FakeUserCollectionDao = FakeUserCollectionDao(),
        /** 停手窗口：默认一点就结束；要断言「窗口里」的用例传关着的闸门，看完再打开。 */
        val gate: CommitGate = CommitGate(open = true),
        /** 给了就是冷启动：本地存着已登录的会话，内存里还没恢复，恢复要等它放行。 */
        restoreGate: CompletableDeferred<Unit>? = null,
    ) {
        /** 倒计时时钟：用例自己拨。 */
        val clock = AtomicLong(0L)
        val watchDao = FakeEpisodeWatchStateDao()
        private val client = resources.apiClient(engine(server))
        val store = restoreGate?.let(::coldTokenStore)
            ?: testTokenStore(scope = scope).also { runBlocking { it.completeSession(it.beginSession("token"), ACCOUNT_ID, "fixture_user") } }
        val collections = testCollections(resources, CollectionApi(client), FakeSubjectDao(), collectionDao, store, watchDao)
        private val orchestrator = collections.progressOrchestrator(
            testSubjectRepository(resources, SubjectApi(client), NextApi(client), FakeSubjectDao(), FakeEpisodeDao(), FakeSubjectSectionDao(), testNsfwFilter(resources)),
        )
        val coordinator = collections.epTapCoordinator(orchestrator, scope, clock::get, gate::await)

        init {
            runBlocking {
                collectionDao.upsert(UserCollectionEntity(subjectId = ANIME_ID, subjectType = SubjectType.ANIME.value, type = CollectionType.DOING.value, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
                collectionDao.upsert(UserCollectionEntity(subjectId = BOOK_ID, subjectType = SubjectType.BOOK.value, type = CollectionType.DOING.value, accountId = ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
            }
        }

        val anime = ProgressSubject(ANIME_ID, SubjectType.ANIME, episodes)
        val book = ProgressSubject(BOOK_ID, SubjectType.BOOK, emptyList(), totalEps = 10)

        fun localDone(): Set<Int> = watchDao.store.values
            .filter { it.subjectId == ANIME_ID && it.status == EpisodeStatus.DONE.value }
            .map { it.episodeId }.toSet()

        fun bookEpStatus(): Int? = collectionDao.store[ACCOUNT_ID to BOOK_ID]?.epStatus
    }

    /** 另一个令牌库写下已登录的会话；返回的令牌库共用这份存储、内存里还没有会话，读存储要等 [restoreGate]。 */
    private fun coldTokenStore(restoreGate: CompletableDeferred<Unit>): TokenStore {
        val dir = resources.directory("cold-token")
        val real = PreferenceDataStoreFactory.create(scope = resources.own(CoroutineScope(Dispatchers.IO + SupervisorJob()))) {
            File(dir, "tokens.preferences_pb")
        }
        runBlocking { TokenStore(real).let { it.completeSession(it.beginSession("token"), ACCOUNT_ID, "fixture_user") } }
        return TokenStore(object : DataStore<Preferences> by real {
            override val data: Flow<Preferences> = flow {
                restoreGate.await()
                emitAll(real.data)
            }
        })
    }

    private class SuspendingUserCollectionDao : FakeUserCollectionDao() {
        val written = CompletableDeferred<Unit>()
        val canProceed = CompletableDeferred<Unit>()
        var onUpsert: ((UserCollectionEntity) -> Unit)? = null

        /** 开启后，本地发布读收藏行时停住：发布值与乐观值相同、不再写行时，也能卡在提交收尾。 */
        @Volatile var readHookEnabled = false

        override suspend fun upsert(collection: UserCollectionEntity) {
            super.upsert(collection)
            onUpsert?.invoke(collection)
        }

        override suspend fun getBySubjectId(subjectId: Int, accountId: Int): UserCollectionEntity? {
            if (readHookEnabled) {
                written.complete(Unit)
                canProceed.await()
            }
            return super.getBySubjectId(subjectId, accountId)
        }
    }

    @Test
    fun `旧提交完成不能把新点击的乐观进度先退回再重放`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newerApplied = CompletableDeferred<Unit>()
        val server = Server().apply {
            onGetWatchStates = { started.complete(Unit); release.await() }
        }
        val dao = SuspendingUserCollectionDao()
        val f = Fixture(server = server, collectionDao = dao)
        val history = Collections.synchronizedList(mutableListOf<Int>())
        dao.onUpsert = { row ->
            if (row.subjectId == ANIME_ID) {
                history += f.localDone().size
                if (row.epStatus == 2) newerApplied.complete(Unit)
            }
        }
        f.coordinator.advance(ProgressOrigin.PROGRESS, f.anime, TODAY)
        started.await() // 第一轮已经提交，但服务端读取被确定性闸门阻塞。
        f.coordinator.advance(ProgressOrigin.PROGRESS, f.anime, TODAY)
        newerApplied.await()
        release.complete(Unit)
        awaitUntil("两轮写入均已完整结束") {
            server.batches.size == 2 && ANIME_ID !in f.coordinator.active.value
        }
        assertEquals(setOf(101, 102), f.localDone())
        assertTrue(history.zipWithNext().all { (a, b) -> b >= a }, "只有前进且服务端均成功，真实逐集数据却回退：$history")
    }

    @Test
    fun `旧提交失败时已有更新的点击 不回滚也不退回 由新意图自己收尾`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newerApplied = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        val server = Server().apply {
            failWrites = true // 第一轮的批量写入会失败
            onGetWatchStates = {
                if (reads.incrementAndGet() == 1) {
                    started.complete(Unit)
                    release.await()
                } else {
                    failWrites = false // 第二轮提交时服务端恢复
                }
            }
        }
        val dao = SuspendingUserCollectionDao()
        val f = Fixture(server = server, collectionDao = dao)
        val history = Collections.synchronizedList(mutableListOf<Int>())
        f.watchDao.onChange = { rows ->
            history += rows.count { it.subjectId == ANIME_ID && it.status == EpisodeStatus.DONE.value }
        }
        dao.onUpsert = { row -> if (row.subjectId == ANIME_ID && row.epStatus == 2) newerApplied.complete(Unit) }
        val error = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.errors.firstOrNullWithin() }

        f.coordinator.advance(ProgressOrigin.PROGRESS, f.anime, TODAY)
        started.await()
        f.coordinator.advance(ProgressOrigin.PROGRESS, f.anime, TODAY)
        newerApplied.await()
        release.complete(Unit)

        assertEquals(ANIME_ID, error.await()?.subjectId, "第一轮失败仍向发起页报错一次")
        awaitUntil("第二轮提交成功且整轮写入结束") {
            server.batches.size == 1 && ANIME_ID !in f.coordinator.active.value
        }
        assertEquals(listOf(101, 102), server.batches.single().episodeIds, "第二轮以服务端现状求差，补齐两集")
        assertEquals(setOf(101, 102), f.localDone())
        assertTrue(history.zipWithNext().all { (a, b) -> b >= a }, "旧一轮失败不得把新点击的进度回滚：$history")
    }

    @Test
    fun `书籍旧提交回读不能覆盖较新的点击`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newerApplied = CompletableDeferred<Unit>()
        val read = CompletableDeferred<Unit>()
        val patches = AtomicInteger()
        val server = Server().apply {
            onPatchCollection = {
                if (patches.incrementAndGet() == 1) {
                    bookServerProgress = 1
                    started.complete(Unit)
                    release.await()
                    throw java.net.SocketTimeoutException("response lost")
                }
            }
            onBookRead = { read.complete(Unit) }
        }
        val dao = SuspendingUserCollectionDao()
        val f = Fixture(server = server, collectionDao = dao)
        val history = Collections.synchronizedList(mutableListOf<Int>())
        dao.onUpsert = { row ->
            if (row.subjectId == BOOK_ID) {
                history += row.epStatus
                if (row.epStatus == 2) newerApplied.complete(Unit)
            }
        }

        f.coordinator.advance(ProgressOrigin.HOME, f.book, TODAY)
        started.await()
        f.coordinator.advance(ProgressOrigin.HOME, f.book, TODAY)
        newerApplied.await()
        release.complete(Unit)
        withTimeoutOrNull(WAIT_BUDGET_MS) { read.await() } ?: error("旧提交没有回读")
        awaitUntil("两轮写入均已完整结束") { patches.get() == 2 && BOOK_ID !in f.coordinator.active.value }
        assertEquals(2, f.bookEpStatus())
        assertTrue(history.zipWithNext().all { (a, b) -> b >= a }, "旧回读覆盖了较新点击：$history")
    }

    @Test
    fun `书籍旧提交完成不能把新点击的话数先退回再重放`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newerApplied = CompletableDeferred<Unit>()
        val patches = AtomicInteger()
        val server = Server().apply {
            onPatchCollection = {
                if (patches.incrementAndGet() == 1) {
                    started.complete(Unit)
                    release.await()
                }
            }
        }
        val dao = SuspendingUserCollectionDao()
        val f = Fixture(server = server, collectionDao = dao)
        val history = Collections.synchronizedList(mutableListOf<Int>())
        dao.onUpsert = { row ->
            if (row.subjectId == BOOK_ID) {
                history += row.epStatus
                if (row.epStatus == 2) newerApplied.complete(Unit)
            }
        }

        f.coordinator.advance(ProgressOrigin.HOME, f.book, TODAY)
        started.await() // 第一轮已提交，收藏 PATCH 被确定性闸门阻塞
        f.coordinator.advance(ProgressOrigin.HOME, f.book, TODAY)
        newerApplied.await()
        release.complete(Unit)

        awaitUntil("两轮写入均已完整结束") { server.patches.size == 2 && BOOK_ID !in f.coordinator.active.value }
        assertEquals(2, f.bookEpStatus())
        assertTrue(history.zipWithNext().all { (a, b) -> b >= a }, "话数只前进且服务端均成功，本地却回退：$history")
    }

    @Test
    fun `点击立刻乐观写本地 连点合并成一次写入`() = runBlocking {
        val dao = SuspendingUserCollectionDao()
        val server = Server().apply { onBatchWrite = { dao.readHookEnabled = true } }
        val f = Fixture(server = server, collectionDao = dao, gate = CommitGate())
        repeat(3) { f.coordinator.advance(ProgressOrigin.HOME, f.anime, TODAY) }

        awaitUntil("三次点击都已乐观写入本地并进入 pending") {
            f.localDone() == setOf(101, 102, 103) &&
                f.coordinator.pending.value[ANIME_ID]?.count == 3
        }
        assertTrue(f.server.batches.isEmpty(), "停手窗口内不得发出写入")

        f.gate.open()
        dao.written.await()
        val completion = async(start = CoroutineStart.UNDISPATCHED) {
            awaitUntil("停手后提交") { f.server.batches.size == 1 && ANIME_ID !in f.coordinator.active.value }
        }
        try {
            assertFalse(completion.isCompleted, "网络已响应，但本地发布仍在收尾，不能认作提交完成")
            assertTrue(ANIME_ID in f.coordinator.active.value)
        } finally {
            dao.canProceed.complete(Unit)
        }
        completion.await()
        assertEquals(listOf(101, 102, 103), f.server.batches.single().episodeIds)
        assertEquals(EpisodeStatus.DONE.value, f.server.batches.single().type)
        assertEquals(setOf(101, 102, 103), f.localDone())
    }

    @Test
    fun `跳着看过时连点加一 从看到这里往后走 停手后只发一次批量`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        // 服务端与本地一致：1 看过、2 想看、3 看过（看到这里是 3，2 是它之前的空洞）
        val seeded = mapOf(101 to EpisodeStatus.DONE, 102 to EpisodeStatus.WISH, 103 to EpisodeStatus.DONE)
        seeded.forEach { (id, status) -> f.server.watchStates[id] = status.value }
        f.watchDao.upsertAll(
            seeded.map { (id, status) -> EpisodeWatchStateEntity(episodeId = id, subjectId = ANIME_ID, accountId = ACCOUNT_ID, status = status.value) },
        )

        repeat(2) { f.coordinator.advance(ProgressOrigin.PROGRESS, f.anime, TODAY) }
        awaitUntil("两次点击都已乐观写入本地并进入 pending") { f.coordinator.pending.value[ANIME_ID]?.count == 2 }
        assertEquals(setOf(101, 102, 103, 104, 105), f.localDone(), "两次加一从第 3 集走到第 5 集，第 3 集的看过不能丢")
        assertTrue(f.server.batches.isEmpty(), "停手窗口内不得发出写入")

        f.gate.open()
        awaitUntil("停手后提交") { f.server.batches.size == 1 && ANIME_ID !in f.coordinator.active.value }
        assertEquals(listOf(102, 104, 105), f.server.batches.single().episodeIds, "看到第 5 集：前面的空洞一并标为看过，一次发完")
        assertEquals(EpisodeStatus.DONE.value, f.server.batches.single().type)
        assertEquals(setOf(101, 102, 103, 104, 105), f.localDone())
    }

    @Test
    fun `界面倒计时与真正提交是同一个时刻 每次连点都把它顺延`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        f.clock.set(1_000L)
        f.coordinator.advance(ProgressOrigin.HOME, f.anime, TODAY)
        awaitUntil("第一次点击进入 pending") { f.coordinator.pending.value[ANIME_ID]?.count == 1 }
        assertEquals(1_000L + EP_COMMIT_DELAY_NANOS, f.coordinator.pending.value.getValue(ANIME_ID).commitAtNanos)

        f.clock.set(5_000L)
        f.coordinator.advance(ProgressOrigin.PROGRESS, f.anime, TODAY)
        awaitUntil("第二次点击并入同一轮") { f.coordinator.pending.value[ANIME_ID]?.count == 2 }
        val shown = f.coordinator.pending.value.getValue(ANIME_ID).commitAtNanos
        assertEquals(5_000L + EP_COMMIT_DELAY_NANOS, shown, "连点把提交时刻顺延到最后一次点击之后")
        awaitUntil("提交按界面显示的那个时刻等") { f.gate.awaited.lastOrNull() == shown }

        f.gate.open()
        awaitUntil("到点提交") { f.server.batches.size == 1 && ANIME_ID !in f.coordinator.active.value }
    }

    @Test
    fun `网格连点以最后一次为准 只写一次`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        f.coordinator.watchUpTo(ProgressOrigin.DETAILS, f.anime, EpisodeProgressTarget.Episode(105, 5.0))
        f.coordinator.watchUpTo(ProgressOrigin.DETAILS, f.anime, EpisodeProgressTarget.Episode(102, 2.0))

        awaitUntil("本地是最后一次点选的结果") { f.localDone() == setOf(101, 102) }
        f.gate.open()
        awaitUntil("停手后提交") { f.server.batches.size == 1 && ANIME_ID !in f.coordinator.active.value }
        assertEquals(listOf(101, 102), f.server.batches.single().episodeIds)
    }

    @Test
    fun `提交失败 本地按服务端回滚并向发起页报错一次`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        f.server.failWrites = true
        val error = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.errors.firstOrNullWithin() }

        f.coordinator.advance(ProgressOrigin.PROGRESS, f.anime, TODAY)
        awaitUntil("乐观写入") { 101 in f.localDone() }
        f.gate.open()

        val received = error.await()
        assertEquals(ProgressOrigin.PROGRESS, received?.origin)
        assertEquals(ANIME_ID, received?.subjectId)
        // 服务端仍是全未看：本地跟着回到全未看
        awaitUntil("失败写入完整结束") { ANIME_ID !in f.coordinator.active.value }
        assertTrue(f.localDone().isEmpty(), "服务端仍是全未看，本地已回滚")
    }

    @Test
    fun `提交时服务端章节里已没有点的那一集 这一轮以失败结束 回滚并报错 不写也不崩`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        val vanished = EpisodeBo(id = 106, subjectId = ANIME_ID, type = 0, name = "ep6", nameCn = "", sort = 6.0)
        val error = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.errors.firstOrNullWithin() }

        f.coordinator.watchUpTo(ProgressOrigin.DETAILS, f.anime.copy(episodes = episodes + vanished), EpisodeProgressTarget.Episode(106, 6.0))
        awaitUntil("乐观写入") { 106 in f.localDone() }
        f.gate.open()

        assertEquals(Failure.BadResponse, error.await()?.failure)
        awaitUntil("这一轮完整结束、解除占用") { ANIME_ID !in f.coordinator.active.value }
        assertTrue(f.server.batches.isEmpty(), "目标集找不到时不得发出批量写入")
        assertTrue(f.localDone().isEmpty(), "服务端仍是全未看，本地已回滚")
    }

    @Test
    fun `待提交期间服务端刷新不覆盖乐观状态`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        f.coordinator.advance(ProgressOrigin.HOME, f.anime, TODAY)
        awaitUntil("乐观写入") { f.localDone() == setOf(101) }

        // 服务端仍是全未看：刷新若落库会把界面打回旧进度
        f.collections.watchStates.refreshEpisodeWatchStates(ANIME_ID)
        assertEquals(setOf(101), f.localDone())

        f.gate.open()
        awaitUntil("提交完成") { f.server.batches.size == 1 && ANIME_ID !in f.coordinator.active.value }
        // 提交结束后服务端出现新进度，刷新必须实际替换逐集表，不能仍因占用而跳过。
        f.server.watchStates[102] = EpisodeStatus.DONE.value
        f.collections.watchStates.refreshEpisodeWatchStates(ANIME_ID)
        assertEquals(setOf(101, 102), f.localDone())
    }

    @Test
    fun `回滚到快照写本地时重新占用条目`() = runBlocking {
        val server = Server()
        val dao = SuspendingUserCollectionDao()
        val f = Fixture(server = server, collectionDao = dao)

        // 1. 本地先有一集看过并已提交
        f.coordinator.watchUpTo(ProgressOrigin.DETAILS, f.anime, EpisodeProgressTarget.Episode(101, 1.0))
        // 必须等整轮写入结束（active 移除）才算「已提交」：只看 localDone + pending 会提前放行——
        // 乐观写内部「逐集表已写、pending 未设」的缝隙里两条件同时暂真，此时第 3 步的点击会以
        // 未提交的窗口为前窗，快照错链成空集，第 4 步的等待就永远等不到。
        awaitUntil("第一集看过已提交") {
            f.localDone() == setOf(101) &&
                f.coordinator.pending.value.isEmpty() &&
                ANIME_ID !in f.coordinator.active.value
        }

        // 2. 配置服务端：提交写入失败，且回滚时拉取服务端状态也失败，迫使回滚走退回快照的分支
        server.failWrites = true
        server.failGetWatchStates = true

        // DAO 的写在别的线程上，awaitUntil 在测试线程上读
        val heldDuringWriteSnapshot = AtomicReference<Boolean?>(null)
        dao.onUpsert = { entity ->
            // 只在回滚写回快照阶段（epStatus 回退为 1）捕获条目占用状态，排除乐观写阶段（epStatus 为 2）
            if (entity.epStatus == 1) {
                heldDuringWriteSnapshot.set(f.collections.holds.isHeld(ANIME_ID))
            }
        }

        // 3. 用户点击第二集：触发乐观写入，窗口关着
        f.gate.close()
        f.coordinator.advance(ProgressOrigin.HOME, f.anime, TODAY)
        awaitUntil("第二集乐观写入") {
            f.localDone() == setOf(101, 102) && f.coordinator.pending.value.containsKey(ANIME_ID)
        }
        f.gate.open()

        // 4. 等待提交失败并回滚完毕，恢复到快照 [101]。
        // 条件必须包含「快照写入已发生」：回滚的 writeLocal 先写逐集表（localDone 立即恢复 [101]）、
        // 后写收藏行（onUpsert 才捕获 heldDuringWriteSnapshot），只看前一半会在两半之间放行，断言读到 null。
        awaitUntil("回滚到快照") {
            f.localDone() == setOf(101) &&
                ANIME_ID !in f.coordinator.active.value &&
                heldDuringWriteSnapshot.get() != null
        }

        // 5. 断言：在回滚写快照期间，条目必须已被重新占用（若未重新占用则为 false）
        assertEquals(true, heldDuringWriteSnapshot.get(), "回滚写快照期间必须重新占用")
    }

    @Test
    fun `书籍连点乐观加话数 合并成一次收藏写入`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        repeat(2) { f.coordinator.advance(ProgressOrigin.HOME, f.book, TODAY) }

        awaitUntil("话数乐观加到 2 且 pending 计数为 2") {
            f.bookEpStatus() == 2 && f.coordinator.pending.value[BOOK_ID]?.count == 2
        }
        assertTrue(f.server.patches.isEmpty())

        f.gate.open()
        awaitUntil("停手后提交") { f.server.patches.size == 1 && BOOK_ID !in f.coordinator.active.value }
        assertTrue(""""ep_status":2""" in f.server.patches.single(), f.server.patches.single())
    }

    @Test
    fun `书籍写入响应丢失后保留服务端回读而非窗口快照`() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val server = Server().apply {
            onPatchCollection = {
                bookServerProgress = 2
                reached.complete(Unit)
                release.await()
                throw java.net.SocketTimeoutException("response lost")
            }
        }
        val f = Fixture(server = server, gate = CommitGate(open = true))
        f.coordinator.advance(ProgressOrigin.HOME, f.book, TODAY)
        withTimeoutOrNull(WAIT_BUDGET_MS) { reached.await() } ?: error("写入没有到达服务端")
        assertEquals(1, f.bookEpStatus())
        release.complete(Unit)
        awaitUntil("回读完成") { BOOK_ID !in f.coordinator.active.value }
        assertEquals(2, f.bookEpStatus())
    }

    @Test
    fun `书籍回读失败时回滚窗口快照`() = runBlocking {
        val server = Server().apply {
            failBookRead = true
            onPatchCollection = { throw java.net.SocketTimeoutException("response lost") }
        }
        val f = Fixture(server = server, gate = CommitGate(open = false))
        f.coordinator.advance(ProgressOrigin.HOME, f.book, TODAY)
        awaitUntil("乐观写入") { f.bookEpStatus() == 1 }
        f.gate.open()
        awaitUntil("回滚完成") { BOOK_ID !in f.coordinator.active.value }
        assertEquals(0, f.bookEpStatus())
    }

    @Test
    fun `书籍提交失败 话数回滚到窗口前`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        f.server.failWrites = true
        f.coordinator.advance(ProgressOrigin.HOME, f.book, TODAY)
        awaitUntil("乐观写入") { f.bookEpStatus() == 1 }
        f.gate.open()
        awaitUntil("失败提交与回滚完整结束") { f.bookEpStatus() == 0 && BOOK_ID !in f.coordinator.active.value }
    }

    @Test
    fun `特别篇一点即乐观写这一集并立刻提交 不动正片`() = runBlocking {
        val f = Fixture()
        f.coordinator.toggleSpecial(ProgressOrigin.DETAILS, f.anime, special)

        awaitUntil("乐观写入这一集") { f.localDone() == setOf(SPECIAL_ID) }
        awaitUntil("单集提交且这轮写入结束") {
            f.server.singlePuts.toList() == listOf(SPECIAL_ID to EpisodeStatus.DONE.value) &&
                ANIME_ID !in f.coordinator.active.value
        }
        assertTrue(f.server.batches.isEmpty(), "特别篇不走正片批量写入")
        assertTrue(f.coordinator.pending.value.isEmpty(), "特别篇不进连点合并")
    }

    @Test
    fun `特别篇再点一次取消看过 提交顺序与点击一致`() = runBlocking {
        val f = Fixture()
        f.coordinator.toggleSpecial(ProgressOrigin.DETAILS, f.anime, special)
        f.coordinator.toggleSpecial(ProgressOrigin.DETAILS, f.anime, special)

        awaitUntil("两次都已提交") { f.server.singlePuts.size == 2 && ANIME_ID !in f.coordinator.active.value }
        assertEquals(
            listOf(SPECIAL_ID to EpisodeStatus.DONE.value, SPECIAL_ID to EpisodeStatus.NONE.value),
            f.server.singlePuts.toList(),
        )
        assertTrue(f.localDone().isEmpty(), "本地终态与服务端一致：无记录")
    }

    @Test
    fun `特别篇提交失败 回到点之前并向发起页报错`() = runBlocking {
        val f = Fixture()
        f.server.failWrites = true
        val error = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.errors.firstOrNullWithin() }

        f.coordinator.toggleSpecial(ProgressOrigin.DETAILS, f.anime, special)

        val received = error.await()
        assertEquals(ProgressOrigin.DETAILS, received?.origin)
        awaitUntil("回滚到点之前") { f.localDone().isEmpty() && ANIME_ID !in f.coordinator.active.value }
    }

    @Test
    fun `正片与特别篇先后写完 正片写穿不删特别篇 解除占用后刷新照常落库`() = runBlocking {
        val f = Fixture()
        f.coordinator.advance(ProgressOrigin.DETAILS, f.anime, TODAY)
        f.coordinator.toggleSpecial(ProgressOrigin.DETAILS, f.anime, special)

        awaitUntil("两路写入都完成") {
            f.server.batches.size == 1 && f.server.singlePuts.size == 1 && ANIME_ID !in f.coordinator.active.value
        }
        assertEquals(setOf(101, SPECIAL_ID), f.localDone(), "正片写穿不删特别篇的行")
        // 占用已解除：服务端来源的刷新恢复落库
        f.collections.watchStates.refreshEpisodeWatchStates(ANIME_ID)
        assertEquals(setOf(101, SPECIAL_ID), f.localDone())
    }

    @Test
    fun `没收藏时点一集 先建在看收藏再写进度 建收藏的这一笔从发出到这一轮结束都算在建`() = runBlocking {
        val f = Fixture()
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.server.onAnimeCollection = { arrived.complete(Unit); release.await() }

        f.coordinator.watchUpTo(ProgressOrigin.DETAILS, f.anime.copy(hasCollection = false), EpisodeProgressTarget.Episode(102, 2.0))

        withTimeoutOrNull(WAIT_BUDGET_MS) { arrived.await() } ?: error("建收藏的请求没有发出")
        // creatingCollection 是在 scope 上派生的 StateFlow，比内部状态晚一拍到；请求发出后它一直含着这条，直到这一轮结束
        awaitUntil("建收藏在途：收藏按钮要据此显示保存中") { f.coordinator.creatingCollection.value == setOf(ANIME_ID) }
        release.complete(Unit)

        // creatingCollection 比内部状态晚一拍：等它跟上，不在 active 清空的那一刻读
        awaitUntil("这一轮写入结束、在建标记清掉") { ANIME_ID !in f.coordinator.active.value && f.coordinator.creatingCollection.value.isEmpty() }
        assertEquals(listOf(CollectionType.DOING.value), f.server.animeCollectionTypes.toList(), "开始看了就是在看")
        assertEquals(listOf(101, 102), f.server.batches.single().episodeIds)
    }

    @Test
    fun `没收藏时切特别篇 也先建在看收藏`() = runBlocking {
        val f = Fixture()
        f.coordinator.toggleSpecial(ProgressOrigin.DETAILS, f.anime.copy(hasCollection = false), special)

        awaitUntil("单集提交、这轮写入结束、在建标记清掉") {
            f.server.singlePuts.size == 1 && ANIME_ID !in f.coordinator.active.value && f.coordinator.creatingCollection.value.isEmpty()
        }
        assertEquals(listOf(CollectionType.DOING.value), f.server.animeCollectionTypes.toList())
    }

    @Test
    fun `提交窗口里登出 这一轮报错一次后结束 不崩也不发写入`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        val error = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.errors.firstOrNullWithin() }
        f.coordinator.advance(ProgressOrigin.HOME, f.anime, TODAY)
        awaitUntil("乐观写入并进入窗口") { f.coordinator.pending.value[ANIME_ID]?.count == 1 }

        f.store.clear()
        f.gate.open()

        assertEquals(ANIME_ID, error.await()?.subjectId, "登出后这一轮失败，向发起页报错一次")
        awaitUntil("这一轮写入结束") { ANIME_ID !in f.coordinator.active.value }
        assertTrue(f.server.batches.isEmpty(), "登出后不再发写入")
        assertTrue(uncaught.isEmpty(), "异常漏到了应用级作用域：$uncaught")
    }

    @Test
    fun `提交窗口里换了账号 不用新账号的令牌写 也不写进新账号的本地分区`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        val error = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.errors.firstOrNullWithin() }
        f.coordinator.advance(ProgressOrigin.HOME, f.anime, TODAY)
        awaitUntil("乐观写入并进入窗口") { f.coordinator.pending.value[ANIME_ID]?.count == 1 }

        f.store.clear()
        f.store.completeSession(f.store.beginSession("other-token"), OTHER_ACCOUNT_ID, "other")
        f.gate.open()

        assertEquals(Failure.SessionChanged, error.await()?.failure)
        awaitUntil("这一轮写入结束") { ANIME_ID !in f.coordinator.active.value }
        assertTrue(f.server.batches.isEmpty(), "旧账号的进度不能用新账号的令牌写到服务端：${f.server.batches}")
        assertTrue(f.watchDao.store.values.none { it.accountId == OTHER_ACCOUNT_ID }, "逐集状态写进了新账号")
        assertTrue(f.collectionDao.store.keys.none { it.first == OTHER_ACCOUNT_ID }, "收藏写进了新账号")
        assertTrue(uncaught.isEmpty(), "异常漏到了应用级作用域：$uncaught")
    }

    @Test
    fun `先读后点的入口 读的期间换了账号 这次点击作废 不写进新账号`() = runBlocking {
        val f = Fixture()
        // 新账号也在看这部：按作品 id 找得到，只有点击那一刻的账号能拦住这次写入
        f.collectionDao.upsert(UserCollectionEntity(subjectId = ANIME_ID, subjectType = SubjectType.ANIME.value, type = CollectionType.DOING.value, accountId = OTHER_ACCOUNT_ID, updatedAt = System.currentTimeMillis()))
        val error = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.errors.firstOrNullWithin() }

        val queued = f.coordinator.advanceResolved(ProgressOrigin.WIDGET) {
            f.store.clear()
            f.store.completeSession(f.store.beginSession("other-token"), OTHER_ACCOUNT_ID, "other")
            AdvanceTarget(f.anime, TODAY)
        }

        assertTrue(queued, "读出了要前进的那一部")
        assertEquals(Failure.SessionChanged, error.await()?.failure)
        assertTrue(f.watchDao.store.values.none { it.accountId == OTHER_ACCOUNT_ID }, "点击写进了读的期间换上的账号")
        assertTrue(f.server.batches.isEmpty(), "旧账号的点击不能用新账号的令牌写到服务端：${f.server.batches}")
        assertTrue(uncaught.isEmpty(), "异常漏到了应用级作用域：$uncaught")
    }

    @Test
    fun `先读后点的入口 点击刚把进程拉起 本地会话还没恢复 照常写进存着的账号`() = runBlocking {
        val restored = CompletableDeferred<Unit>()
        val f = Fixture(restoreGate = restored)

        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            f.coordinator.advanceResolved(ProgressOrigin.WIDGET) { AdvanceTarget(f.anime, TODAY) }
        }
        restored.complete(Unit)

        assertTrue(queued.await())
        awaitUntil("乐观写入第一集并提交") { f.server.batches.size == 1 && ANIME_ID !in f.coordinator.active.value }
        assertEquals(setOf(101), f.localDone())
    }

    @Test
    fun `先读后点的入口 读的期间没换账号 照常乐观写入并提交`() = runBlocking {
        val f = Fixture()

        assertTrue(f.coordinator.advanceResolved(ProgressOrigin.WIDGET) { AdvanceTarget(f.anime, TODAY) })

        awaitUntil("乐观写入第一集并提交") { f.server.batches.size == 1 && ANIME_ID !in f.coordinator.active.value }
        assertEquals(listOf(101), f.server.batches.single().episodeIds)
        assertEquals(setOf(101), f.localDone())
    }

    @Test
    fun `没登录时点一下只报错 之后登录再点照常处理`() = runBlocking {
        val f = Fixture(gate = CommitGate())
        f.store.clear()
        val error = async(start = CoroutineStart.UNDISPATCHED) { f.coordinator.errors.firstOrNullWithin() }
        f.coordinator.advance(ProgressOrigin.HOME, f.anime, TODAY)
        assertEquals(Failure.NotLoggedIn, error.await()?.failure)

        f.store.completeSession(f.store.beginSession("other-token"), OTHER_ACCOUNT_ID, "other")
        f.coordinator.advance(ProgressOrigin.HOME, f.anime, TODAY)
        awaitUntil("登录后的点击照常乐观写入新账号") {
            f.watchDao.store.values.any { it.accountId == OTHER_ACCOUNT_ID && it.episodeId == 101 && it.status == EpisodeStatus.DONE.value } &&
                f.coordinator.pending.value[ANIME_ID]?.count == 1
        }
        assertTrue(uncaught.isEmpty(), "异常漏到了应用级作用域：$uncaught")
    }

    @Test
    fun `提交失败回滚去取服务端状态时 别的条目的点击不等它`() = runBlocking {
        val reads = AtomicInteger()
        val rollbackReading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val server = Server().apply {
            failWrites = true
            // 第一次读是提交时求差，第二次是失败后的回滚：卡住回滚那一次
            onGetWatchStates = { if (reads.incrementAndGet() == 2) { rollbackReading.complete(Unit); release.await() } }
        }
        val f = Fixture(server = server, gate = CommitGate())
        f.coordinator.advance(ProgressOrigin.PROGRESS, f.anime, TODAY)
        awaitUntil("动画进入窗口") { f.coordinator.pending.value[ANIME_ID]?.count == 1 }
        f.gate.open()
        withTimeoutOrNull(WAIT_BUDGET_MS) { rollbackReading.await() } ?: error("提交失败后没有去取服务端状态")

        f.gate.close()
        f.coordinator.advance(ProgressOrigin.HOME, f.book, TODAY)
        try {
            awaitUntil("书的点击立刻乐观写入") { f.bookEpStatus() == 1 && f.coordinator.pending.value[BOOK_ID]?.count == 1 }
        } finally {
            release.complete(Unit)
        }
        f.gate.open()
        awaitUntil("两部都结束") { f.coordinator.active.value.isEmpty() }
    }

    /**
     * [errors] 不重放：订阅必须先于触发写入的点击。调用方用 `async(start = CoroutineStart.UNDISPATCHED)`，
     * 让订阅在 `async` 返回前就挂上；普通 `async` 要等 runBlocking 线程让出才开始，
     * Default 上的协调器可能先发出错误，这条错误就丢了。
     */
    private suspend fun SharedFlow<ProgressWriteError>.firstOrNullWithin(): ProgressWriteError? =
        withTimeoutOrNull(WAIT_BUDGET_MS) { first() }
}
