package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectField
import io.github.touhougo.chogumi.core.data.db.mapper.SubjectSnapshot
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** 收藏同步提速（并发上限、每类一个事务、新鲜度）的单测。 */
class CollectionSyncTest {
    @get:org.junit.Rule
    val resources = TestResources()


    private fun newStore(): io.github.touhougo.chogumi.core.data.token.TokenStore =
        testTokenStore(resources, userId = 7)

    @Test
    fun `本地统计用到注入的计算调度器`() = runTest {
        val dispatcher = CountingDispatcher()
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "n", nameCn = "中", updatedAt = System.currentTimeMillis()))
        val dao = FakeUserCollectionDao()
        dao.upsert(io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity(
            subjectId = 8, subjectType = 2, type = 3, accountId = 7,
            updatedAt = System.currentTimeMillis(),
        ))
        val repo = testCollections(
            resources,
            CollectionApi(resources.apiClient { respond("{}", HttpStatusCode.OK) }),
            subjectDao, dao, newStore(), FakeEpisodeWatchStateDao(),
            computationDispatcher = dispatcher,
        )

        val stats = repo.statistics.observeStatistics(currentYear = 2026).first().valueOrNull()!!
        assertEquals(1, stats.byType.getValue(SubjectType.ANIME).totalCount)
        assertTrue(dispatcher.dispatches > 0, "统计没有切到注入的计算调度器")
    }

    /** 阵容来源：算阵容的那一步停在 [gate] 上，[changes] 是缓存变化的拍子；[started] 数有多少轮算进了阵容这一步。 */
    private class GatedLineupSource(val changes: MutableSharedFlow<Unit>) : SubjectLineupSource by NoLineupSource {
        val gate = CompletableDeferred<Unit>()
        @Volatile var started = 0

        override suspend fun cachedLineups(subjects: Map<Int, SubjectType>): Map<Int, io.github.touhougo.chogumi.core.model.SubjectLineup> {
            started++
            gate.await()
            return emptyMap()
        }

        override fun observeLineupChanges(): Flow<Unit> = changes.onStart { emit(Unit) }
    }

    @Test
    fun `首次同步时阵容连续写入 正在算的一轮不被作废 期间的变化合成一次补算`() = runTest {
        // 订阅、合并、合并变化与计算都在这一个线程上（统计的 flowOn），所以往它上面排一个空任务就等于「此前排上的都跑过了」
        val computation = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val source = GatedLineupSource(MutableSharedFlow())
        val repo = testCollections(
            resources,
            CollectionApi(resources.apiClient { respond("{}", HttpStatusCode.OK) }),
            FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao(),
            computationDispatcher = computation, lineupSource = source,
        )
        val outputs = java.util.concurrent.atomic.AtomicInteger()
        val collecting = launch(Dispatchers.Default) { repo.statistics.observeStatistics(currentYear = 2026).collect { outputs.incrementAndGet() } }
        try {
            awaitUntil("第一轮算到了阵容这一步") { source.started >= 1 }
            repeat(5) { source.changes.emit(Unit) }
            repeat(10) { withContext(computation) { yield() } }

            source.gate.complete(Unit)

            awaitUntil("第一轮的结果出来，补算一轮的结果也出来") { outputs.get() >= 2 }
            assertEquals(2, source.started, "第一轮算完，期间的 5 次变化只补算一轮（不是每次变化都从头来）")
        } finally {
            collecting.cancelAndJoin()
            computation.close()
        }
    }

    @Test
    fun `本地统计按条目类型各算一份且五类恒在_再合起来算一份`() = runTest {
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "n", nameCn = "中", updatedAt = System.currentTimeMillis()))
        subjectDao.upsert(SubjectEntity(id = 9, type = 1, name = "b", nameCn = "书", updatedAt = System.currentTimeMillis()))
        val dao = FakeUserCollectionDao()
        dao.upsert(io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, accountId = 7, updatedAt = System.currentTimeMillis()))
        dao.upsert(io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity(subjectId = 9, subjectType = 1, type = 1, accountId = 7, updatedAt = System.currentTimeMillis()))
        val repo = testCollections(
            resources,
            CollectionApi(resources.apiClient { respond("{}", HttpStatusCode.OK) }),
            subjectDao, dao, newStore(), FakeEpisodeWatchStateDao(),
        )

        val stats = repo.statistics.observeStatistics(currentYear = 2026).first().valueOrNull()!!

        assertEquals(
            setOf(SubjectType.BOOK, SubjectType.ANIME, SubjectType.MUSIC, SubjectType.GAME, SubjectType.REAL),
            stats.byType.keys,
        )
        assertEquals(1, stats.byType.getValue(SubjectType.ANIME).totalCount, "动画那份只含动画")
        assertEquals(1, stats.byType.getValue(SubjectType.BOOK).totalCount, "书籍那份只含书籍")
        assertEquals(0, stats.byType.getValue(SubjectType.GAME).totalCount, "没有收藏的类型是全零快照")
        assertEquals(2, stats.overall.totalCount, "合起来的那份含全部类型")
    }

    @Test
    fun `一类一千部条目按块批量查旧行不逐条get`() = runTest {
        val ids = (1..1000).toList()
        // 这个 fake **不覆盖** mergeAll，因此跑的是 SubjectDao 生产默认实现
        // （分块 getByIds → mergeInto → upsertAll）——变异 prod 会被本用例抓到。
        val subjectDao = BareSubjectDao()
        val dao = FakeUserCollectionDao()
        val engine = MockEngine { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            val slice = ids.drop(offset).take(50)
            val items = slice.joinToString(",") { id ->
                """{"subject_id":$id,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false,"subject":{"id":$id,"type":2,"name":"n$id","name_cn":"中$id","short_summary":"","date":"2024-01-01","images":{"large":"","common":"","medium":"","small":"","grid":""},"volumes":0,"eps":12,"collection_total":0,"score":0,"rank":0,"tags":[]}}"""
            }
            respond(
                content = """{"data":[$items],"total":1000,"limit":50,"offset":$offset}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val repo = testCollections(resources, CollectionApi(client), subjectDao, dao, newStore(), FakeEpisodeWatchStateDao())

        repo.store.getUserCollections(SubjectType.ANIME, null, refresh = true).collect {}

        assertEquals(1000, dao.store.size)
        assertEquals(1000, subjectDao.store.size, "每部的条目都必须落表")
        // 1000 部按 900 分块 = 每趟 2 块。两趟都走批量：落库 mergeAll 一趟 + 回读 toBoList 一趟 = 4。
        // 要点：**单条 get 一次都不许有**（首版即逐条 get，N 部就 N 次读）。
        assertEquals(4, subjectDao.getByIdsCallCount, "1000 部应全部走分块批量查（2 + 2）")
        assertEquals(0, subjectDao.getCallCount, "批量落库与回读不得逐条 get")
    }

    @Test
    fun `同一批两条同id快照按顺序依次合并到同一行`() = runTest {
        // 逐条 merge 的既有语义：批内重复 id 时，后一条合并进前一条的结果（不是各自从库里旧行起算）。
        // 只用 BareSubjectDao（不覆盖 mergeAll），跑的是 SubjectDao 生产默认实现。
        val dao = BareSubjectDao()
        dao.upsert(SubjectEntity(id = 1, type = 2, name = "n", nameCn = "old", summary = "old", updatedAt = 100L))
        val detailSnap = SubjectSnapshot(
            value = SubjectEntity(id = 1, type = 2, name = "n", nameCn = "new", summary = "detail", updatedAt = 200L),
            detail = true,
        )
        // 摘要来源：带 detail=false 且旧行 updatedAt 非 0 时**不覆盖** summary——
        // 因此只有「fold 到 detail 结果上」才保得住 detail，各自从库旧行起算会退回 old。
        val slimSnap = SubjectSnapshot(
            value = SubjectEntity(id = 1, type = 2, name = "n", nameCn = "slim", summary = "slim", updatedAt = 0L),
            fields = setOf(SubjectField.NAME_CN, SubjectField.SUMMARY),
            detail = false,
        )

        dao.mergeAll(listOf(detailSnap, slimSnap))

        val row = dao.store.getValue(1)
        assertEquals("slim", row.nameCn, "后一条的 nameCn 生效")
        assertEquals("detail", row.summary, "fold 语义：detail 快照写入的 summary 不得被后一条退回")
        assertEquals(200L, row.updatedAt, "fold 语义：detail 的 updatedAt 保住")
    }

    /**
     * 只实现 [SubjectDao] 的抽象成员，**不覆盖**带默认实现的 `merge` / `mergeAll`。
     * 因此本 fake 上跑的是生产默认的批量合并路径，prod 的 `mergeAll` 被改回逐条 get 时用例会失败。
     */
    private class BareSubjectDao : io.github.touhougo.chogumi.core.data.db.subject.SubjectDao {
        private val rows = mutableMapOf<Int, SubjectEntity>()
        var getCallCount = 0
            private set
        var getByIdsCallCount = 0
            private set
        val store: Map<Int, SubjectEntity> get() = rows.toMap()

        override suspend fun get(id: Int): SubjectEntity? {
            getCallCount++
            return rows[id]
        }
        override suspend fun getByIds(ids: List<Int>): List<SubjectEntity> {
            getByIdsCallCount++
            return ids.mapNotNull { rows[it] }
        }
        override suspend fun upsert(subject: SubjectEntity) { rows[subject.id] = subject }
        override suspend fun upsertAll(subjects: List<SubjectEntity>) { subjects.forEach { rows[it.id] = it } }
        override fun observeByIds(ids: List<Int>): kotlinx.coroutines.flow.Flow<List<SubjectEntity>> =
            kotlinx.coroutines.flow.flowOf(ids.mapNotNull { rows[it] })
    }

    @Test
    fun `同进程第二次自动同步被跳过重启后按30分钟判据`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val store = InMemorySyncPreferenceStore()
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val engine = MockEngine { request ->
            requests.record(request)
            val subjectType = request.url.parameters["subject_type"]!!.toInt()
            respond(
                content = """{"data":[{"subject_id":${1000 + subjectType},"subject_type":$subjectType,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        // 本地收藏表跨「重开 App」保留：30 分钟判据只在本地有行时才跳过同步
        val collectionDao = FakeUserCollectionDao()
        fun openApp() = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), collectionDao, newStore(), FakeEpisodeWatchStateDao(),
            syncPreferenceStore = store, clock = { clock.get() },
        )
        val repo = openApp()

        // 第一次自动同步执行（本进程首次）。
        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = false))
        val afterFirst = requests.count()
        assertTrue(afterFirst > 0)

        // 把时间推到远超 30 分钟：同进程第二次自动同步仍须被跳过（进程内标记）。
        clock.set(60 * 60 * 1000L)
        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = false))
        assertEquals(afterFirst, requests.count(), "同一进程内第二次自动同步必须跳过")

        // 重开 App（进程内标记为空），距上次同步不到 30 分钟、本地有行：跳过。
        clock.set(10 * 60 * 1000L)
        assertIs<Outcome.Success<Unit>>(openApp().sync.refreshAllUserCollections(force = false))
        assertEquals(afterFirst, requests.count(), "重开 App 但不到 30 分钟不同步")

        // 再重开，已超 30 分钟：执行。
        clock.set(60 * 60 * 1000L)
        assertIs<Outcome.Success<Unit>>(openApp().sync.refreshAllUserCollections(force = false))
        assertTrue(requests.count() > afterFirst, "重开 App 且超 30 分钟须重新同步")
    }

    @Test
    fun `五类同步共用全局并发上限四`() = runTest {
        val gate = ConcurrencyGate()
        val engine = MockEngine { request ->
            val subjectType = request.url.parameters["subject_type"]!!.toInt()
            gate.enter()
            respond(
                content = """{"data":[{"subject_id":${1000 + subjectType},"subject_type":$subjectType,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao())

        val job = backgroundScope.launch { repo.sync.refreshAllUserCollections(force = true) }
        // runTest 的虚拟时间会让等待立刻超时：在真实调度器上等
        withContext(Dispatchers.Default) { gate.releaseAfterBudget(4) }
        job.join()
        assertEquals(4, gate.peak, "五类同步的全局并发上限必须是 4")
        assertEquals(5, gate.calls, "放开后第 5 类必须进入")
    }

    @Test
    fun `已有一次同步在跑时再请求不开第二路分页`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val engine = MockEngine { request ->
            val subjectType = request.url.parameters["subject_type"]!!.toInt()
            entered += subjectType
            gate.await()
            respond(
                content = """{"data":[{"subject_id":${1000 + subjectType},"subject_type":$subjectType,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao())

        val first = backgroundScope.launch { repo.sync.refreshAllUserCollections(force = true) }
        awaitUntil("第一次同步的四路首屏进入") { entered.size >= 4 }

        // 生涯卡与统计页各请求一次：第二次（哪怕是强制的）直接返回，不再发请求。
        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = true))
        assertEquals(4, entered.size, "在跑的那次之外不得再开分页")

        gate.complete(Unit)
        first.join()
        assertEquals(5, entered.size, "第一次同步照常取完五类")
    }

    @Test
    fun `每类收藏落库各包一个事务`() = runTest {
        val transactions = CountingTransactionRunner()
        val engine = MockEngine { request ->
            val subjectType = request.url.parameters["subject_type"]!!.toInt()
            respond(
                content = """{"data":[{"subject_id":${1000 + subjectType},"subject_type":$subjectType,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val repo = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao(),
            transactions = transactions,
        )

        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = true))
        assertEquals(5, transactions.count, "五种类型各一个事务")
    }

    @Test
    fun `新鲜期内自动同步被跳过强制刷新则执行`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val clock = 1_000_000L
        val store = InMemorySyncPreferenceStore(initial = mapOf(7 to clock))
        val engine = MockEngine { request ->
            requests.record(request)
            val subjectType = request.url.parameters["subject_type"]!!.toInt()
            respond(
                content = """{"data":[{"subject_id":${1000 + subjectType},"subject_type":$subjectType,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}],"total":1,"limit":50,"offset":0}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val collections = FakeUserCollectionDao().apply {
            upsert(io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, accountId = 7, updatedAt = 1L))
        }
        val repo = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), collections, newStore(), FakeEpisodeWatchStateDao(),
            syncPreferenceStore = store, transactions = PassthroughTransactions,
            clock = { clock },
        )

        // 距上次成功 0 分钟 < 30 分钟：跳过，不发请求。
        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = false))
        assertEquals(0, requests.count(), "新鲜期内不得发起任何请求")

        // 强制刷新绕过新鲜期。
        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = true))
        assertTrue(requests.count() > 0, "force 必须绕过新鲜期")
    }

    @Test
    fun `同步时刻新鲜但本地没有这个账号的收藏_比如换机恢复了偏好_照常同步`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val clock = 1_000_000L
        val store = InMemorySyncPreferenceStore(initial = mapOf(7 to clock))
        val client = resources.apiClient(MockEngine { request ->
            requests.record(request)
            respond("""{"data":[],"total":0,"limit":50,"offset":0}""", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val repo = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao(),
            syncPreferenceStore = store, transactions = PassthroughTransactions, clock = { clock },
        )

        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = false))

        assertTrue(requests.count() > 0, "只有同步时刻、没有数据，不能算新鲜")
    }

    @Test
    fun `同步阵容途中换号_不把这个账号记成已同步`() = runTest {
        val tokenStore = newStore()
        val store = InMemorySyncPreferenceStore()
        val client = resources.apiClient(MockEngine { request ->
            val content = if (request.url.parameters["subject_type"] == "2") {
                """{"data":[${collectionJson(8, epStatus = 0, type = 2)}],"total":1,"limit":50,"offset":0}"""
            } else {
                """{"data":[],"total":0,"limit":50,"offset":0}"""
            }
            respond(content, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        })
        val leavingSource = object : SubjectLineupSource by RecordingLineupSource() {
            override suspend fun missingLineups(subjectIds: List<Int>): List<Int> {
                tokenStore.clear()
                return subjectIds
            }
        }
        val repo = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), tokenStore, FakeEpisodeWatchStateDao(),
            syncPreferenceStore = store, lineupSource = leavingSource,
        )

        val result = repo.sync.refreshAllUserCollections(force = true)

        assertIs<Outcome.Failed>(result)
        assertEquals(null, store.lastSyncedAt(7), "途中换了号，同步没做完，不能记成已同步")
    }

    @Test
    fun `超过SQLite参数上限的收藏行按块批量回读不丢行`() = runTest {
        // 1000 条 > 900：回读必须分块。用会在单次调用超过 900 时抛错的 DAO 证明真的切了块。
        val ids = (1..1000).toList()
        val inner = FakeSubjectDao()
        inner.upsertAll(ids.map { SubjectEntity(id = it, type = 2, name = "n$it", nameCn = "中$it", updatedAt = System.currentTimeMillis()) })
        val subjectDao = MaxChunkGuardedSubjectDao(inner, limit = 900)
        val dao = FakeUserCollectionDao()
        val engine = MockEngine { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            val slice = ids.drop(offset).take(50)
            val items = slice.joinToString(",") { id ->
                """{"subject_id":$id,"subject_type":2,"type":3,"rate":0,"comment":"","tags":[],"ep_status":0,"vol_status":0,"updated_at":"2024-01-01T00:00:00Z","private":false}"""
            }
            respond(
                content = """{"data":[$items],"total":1000,"limit":50,"offset":$offset}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val repo = testCollections(resources, CollectionApi(client), subjectDao, dao, newStore(), FakeEpisodeWatchStateDao())

        val result = repo.store.getUserCollections(SubjectType.ANIME, null, refresh = true).last()

        // 必须是就绪：若回读未分块，DAO 抛错会被仓库吞成失败，store 仍满但整次读取失败。
        assertEquals(1000, assertIs<Load.Ready<List<io.github.touhougo.chogumi.core.model.UserCollectionBo>>>(result).value.size)
        assertEquals(1000, dao.store.size)
    }

    @Test
    fun `本地统计的节奏只取正片的看过时刻`() = runTest {
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "n", nameCn = "中", updatedAt = 1L))
        val dao = FakeUserCollectionDao()
        dao.upsert(io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity(subjectId = 8, subjectType = 2, type = 2, accountId = 7, updatedAt = 1L))
        val episodeDao = FakeEpisodeDao()
        episodeDao.upsertAll(
            listOf(
                io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity(id = 81, subjectId = 8, type = 0, name = "", nameCn = "", sort = 1.0, airdate = "2024-04-01"),
                io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity(id = 82, subjectId = 8, type = 1, name = "", nameCn = "", sort = 1.0),
            ),
        )
        val watchStates = FakeEpisodeWatchStateDao()
        watchStates.upsertAll(
            listOf(81, 82).map { id ->
                io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(episodeId = id, subjectId = 8, accountId = 7, status = 2, updatedAt = 1_712_000_000_000L)
            },
        )
        val repo = testCollections(
            resources,
            CollectionApi(resources.apiClient { respond("{}", HttpStatusCode.OK) }),
            subjectDao, dao, newStore(), watchStates, episodeDao,
        )

        val rhythm = repo.statistics.observeStatistics(currentYear = 2026).first().valueOrNull()!!
            .byType.getValue(SubjectType.ANIME).rhythm
        assertEquals(1, rhythm?.rings?.sumOf { it.episodes }, "特典不算")
    }

    @Test
    fun `逐集状态原样只新存了章节时统计也重算`() = runTest {
        val engine = MockEngine { request ->
            check(request.url.encodedPath == "/v0/users/-/collections/8/episodes") { "只该拉作品 8 的逐集记录：${request.url.encodedPath}" }
            respond(
                """{"data":[${episodeJson(81, 1, "2024-04-01", 1_712_000_000)},${episodeJson(82, 2, "2024-04-08", 1_712_600_000)}],"total":2,"limit":100,"offset":0}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = resources.apiClient(engine)
        val subjectDao = FakeSubjectDao()
        subjectDao.upsert(SubjectEntity(id = 8, type = 2, name = "n", nameCn = "中", updatedAt = 1L))
        val dao = FakeUserCollectionDao()
        dao.upsert(io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity(subjectId = 8, subjectType = 2, type = 3, epStatus = 2, accountId = 7, updatedAt = 1L))
        // 本地逐集状态与服务端那份一模一样（首页灌过），只是还没有章节
        val watchStates = FakeEpisodeWatchStateDao()
        watchStates.upsertAll(
            listOf(81 to 1_712_000_000_000L, 82 to 1_712_600_000_000L).map { (id, at) ->
                io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(episodeId = id, subjectId = 8, accountId = 7, status = 2, updatedAt = at)
            },
        )
        val repo = testCollections(resources, CollectionApi(client), subjectDao, dao, newStore(), watchStates, FakeEpisodeDao())
        val latest = kotlinx.coroutines.flow.MutableStateFlow<io.github.touhougo.chogumi.core.model.CareerStatistics?>(null)
        backgroundScope.launch { repo.statistics.observeStatistics(currentYear = 2026).collect { latest.value = it.valueOrNull() } }
        fun rhythmEpisodes() = latest.value?.byType?.getValue(SubjectType.ANIME)?.rhythm?.rings?.sumOf { it.episodes }
        awaitUntil("第一份统计") { latest.value != null }
        assertTrue(rhythmEpisodes() != 2, "没有章节时节奏还数不出正片")

        assertIs<Outcome.Success<*>>(repo.watchStates.refreshEpisodeHistory(8))

        awaitUntil("章节落库后统计按正片数出两集") { rhythmEpisodes() == 2 }
    }

    @Test
    fun `同步后只补拉逐集记录和看过集数对不上的作品_拉过一次就不再拉`() = runTest {
        val requests = ConcurrentRequestRecorder()
        val engine = MockEngine { request ->
            requests.record(request)
            val path = request.url.encodedPath
            val content = when {
                path == "/v0/users/-/collections/8/episodes" ->
                    """{"data":[${episodeJson(81, 1, "2024-04-01", 1_712_000_000)},${episodeJson(82, 2, "2024-04-08", 1_712_600_000)},${episodeJson(83, 3, "2024-04-15", 0, type = 0)}],"total":3,"limit":100,"offset":0}"""
                path.endsWith("/episodes") -> error("只该补拉作品 8：$path")
                request.url.parameters["subject_type"] == "2" ->
                    """{"data":[${collectionJson(8, epStatus = 2)},${collectionJson(9, epStatus = 1)},${collectionJson(10, epStatus = 0)}],"total":3,"limit":50,"offset":0}"""
                else -> """{"data":[],"total":0,"limit":50,"offset":0}"""
            }
            respond(content, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val episodeDao = FakeEpisodeDao()
        episodeDao.upsertAll(listOf(io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity(id = 91, subjectId = 9, type = 0, name = "", nameCn = "", sort = 1.0)))
        val watchStates = FakeEpisodeWatchStateDao()
        watchStates.upsertAll(listOf(io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity(episodeId = 91, subjectId = 9, accountId = 7, status = 2, updatedAt = 5_000L)))
        val repo = testCollections(resources, CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), watchStates, episodeDao)

        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = true))

        // 作品 8：本地没有逐集记录，补拉；9 已对上（看过 1 集、本地 1 行），10 一集没看，都不拉
        assertEquals(1, requests.count { it.url.encodedPath.endsWith("/episodes") })
        assertEquals(
            mapOf(81 to 1_712_000_000_000L, 82 to 1_712_600_000_000L, 83 to 0L),
            watchStates.store.values.filter { it.subjectId == 8 }.associate { it.episodeId to it.updatedAt },
        )
        assertEquals(listOf("2024-04-01", "2024-04-08", "2024-04-15"), episodeDao.getBySubject(8).map { it.airdate }, "章节随同一份响应落库，首播日从这里来")

        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = true))
        assertEquals(1, requests.count { it.url.encodedPath.endsWith("/episodes") }, "拉过一次两边就对上了，第二次同步不再拉")
    }

    @Test
    fun `补拉二十五部的逐集记录_落库合成两个事务`() = runTest {
        val transactions = CountingTransactionRunner()
        val ids = 1..25
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val content = when {
                path.endsWith("/episodes") -> {
                    val id = path.removePrefix("/v0/users/-/collections/").removeSuffix("/episodes").toInt()
                    """{"data":[${episodeJson(id * 10, 1, "2024-04-01", 1_712_000_000)}],"total":1,"limit":100,"offset":0}"""
                }
                request.url.parameters["subject_type"] == "2" ->
                    """{"data":[${ids.joinToString(",") { collectionJson(it, epStatus = 1) }}],"total":${ids.count()},"limit":50,"offset":0}"""
                else -> """{"data":[],"total":0,"limit":50,"offset":0}"""
            }
            respond(content, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val watchStates = FakeEpisodeWatchStateDao()
        val repo = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), watchStates, transactions = transactions,
        )

        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = true))

        assertEquals(ids.toSet(), watchStates.store.values.mapTo(HashSet()) { it.subjectId }, "每部都落库了")
        assertEquals(2, transactions.count - 5, "五类收藏各一个事务之外，逐集记录每 20 部一个事务")
    }

    @Test
    fun `同步后只给看过和在看的动画补拉阵容_拉过的不再拉_统计读到阵容`() = runTest {
        val engine = MockEngine { request ->
            val content = when (request.url.parameters["subject_type"]) {
                "2" -> """{"data":[${collectionJson(8, epStatus = 0, type = 2)},${collectionJson(9, epStatus = 0, type = 1)},${collectionJson(10, epStatus = 0, type = 3)}],"total":3,"limit":50,"offset":0}"""
                "1" -> """{"data":[${collectionJson(11, epStatus = 0, type = 2, subjectType = 1)}],"total":1,"limit":50,"offset":0}"""
                else -> """{"data":[],"total":0,"limit":50,"offset":0}"""
            }
            respond(content, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = resources.apiClient(engine)
        val lineups = RecordingLineupSource()
        val repo = testCollections(
            resources,
            CollectionApi(client), FakeSubjectDao(), FakeUserCollectionDao(), newStore(), FakeEpisodeWatchStateDao(),
            lineupSource = lineups,
        )

        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = true))
        // 8 看过、10 在看的动画补拉；9 想看、11 是书籍，不拉
        assertEquals(listOf(8, 10), lineups.fetched.sorted())

        assertIs<Outcome.Success<Unit>>(repo.sync.refreshAllUserCollections(force = true))
        assertEquals(listOf(8, 10), lineups.fetched.sorted(), "拉过的第二次同步不再拉")

        val lineup = repo.statistics.observeStatistics(currentYear = 2026).first().valueOrNull()!!
            .byType.getValue(SubjectType.ANIME).lineup
        assertEquals(listOf("花泽香菜" to 2), lineup?.voiceActors?.map { it.name.name to it.works })
    }

    /** 记下拉过哪些作品；拉过的就算已缓存，阵容是同一位声优。 */
    private class RecordingLineupSource : SubjectLineupSource {
        val fetched = java.util.concurrent.CopyOnWriteArrayList<Int>()
        private val voice = io.github.touhougo.chogumi.core.model.LineupName(1, "花泽香菜", null)

        override suspend fun cachedLineups(subjects: Map<Int, SubjectType>) =
            subjects.keys.filter { it in fetched }.associateWith { io.github.touhougo.chogumi.core.model.SubjectLineup(listOf(voice), emptyList()) }

        override suspend fun missingLineups(subjectIds: List<Int>) = subjectIds.filter { it !in fetched }

        override suspend fun fetchLineup(subjectId: Int, session: io.github.touhougo.chogumi.core.data.token.AccountSession, permits: kotlinx.coroutines.sync.Semaphore) {
            fetched += subjectId
        }

        override fun observeLineupChanges() = kotlinx.coroutines.flow.flowOf(Unit)
    }

    private fun collectionJson(id: Int, epStatus: Int, type: Int = 3, subjectType: Int = 2) =
        """{"subject_id":$id,"subject_type":$subjectType,"type":$type,"rate":0,"comment":"","tags":[],"ep_status":$epStatus,"vol_status":0,"updated_at":"2024-05-01T00:00:00Z","private":false}"""

    private fun episodeJson(id: Int, sort: Int, airdate: String, updatedAt: Long, type: Int = 2) =
        """{"episode":{"id":$id,"type":0,"name":"","name_cn":"","sort":$sort,"airdate":"$airdate","comment":0,"duration":"","desc":"","disc":0},"type":$type,"updated_at":$updatedAt}"""

    /** 单次 [getByIds] 超过 [limit] 即抛错——用于证明仓库确实按块查询。 */
    private class MaxChunkGuardedSubjectDao(
        private val inner: FakeSubjectDao,
        private val limit: Int,
    ) : io.github.touhougo.chogumi.core.data.db.subject.SubjectDao {
        override suspend fun get(id: Int) = inner.get(id)
        override suspend fun getByIds(ids: List<Int>): List<SubjectEntity> {
            require(ids.size <= limit) { "单次批量查询 ${ids.size} 超上限 $limit，未分块" }
            return inner.getByIds(ids)
        }
        override suspend fun upsert(subject: SubjectEntity) = inner.upsert(subject)
        override suspend fun upsertAll(subjects: List<SubjectEntity>) = inner.upsertAll(subjects)
        override fun observeByIds(ids: List<Int>) = inner.observeByIds(ids)
        override suspend fun merge(snapshot: io.github.touhougo.chogumi.core.data.db.mapper.SubjectSnapshot) = inner.merge(snapshot)
        override suspend fun mergeAll(snapshots: List<io.github.touhougo.chogumi.core.data.db.mapper.SubjectSnapshot>) = inner.mergeAll(snapshots)
    }

    private class CountingTransactionRunner : TransactionRunner {
        var count = 0
        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            count++
            return block()
        }
    }

}
