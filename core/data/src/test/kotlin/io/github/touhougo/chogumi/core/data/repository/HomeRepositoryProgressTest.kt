package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeDao
import io.github.touhougo.chogumi.core.data.db.subject.EpisodeEntity
import io.github.touhougo.chogumi.core.data.util.CacheTtl
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.HomeProgressItemBo
import io.github.touhougo.chogumi.core.model.HomeSection
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** 还原 67..139 的首页窗口与 1..1155 的全量章节，断言每一拍可操作数据，而非只看最终结果。 */
class HomeRepositoryProgressTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val now = 1790938800000L

    @AfterTest
    fun tearDown() = runBlocking {
        cancelAndAwait(listOf(scope))

    }

    @Test
    fun `刷新不能把完整进度退化成窗口的第二项与139集`() = runBlocking {
        val harness = harness()
        harness.episodes.seed(1155)
        harness.states.upsertAll((1..1155).map { stateEntity(it) })
        assertAccurate(harness, harness.home.getHomeProgress(refresh = true))
    }

    @Test
    fun `首页进度用到注入的计算调度器`() = runBlocking {
        val computation = CountingDispatcher()
        val harness = harness(computationDispatcher = computation)
        assertTrue(harness.home.getHomeProgress().toList().any { it is Load.Ready })
        assertTrue(computation.dispatches > 0, "首页进度没有切到计算调度器")
    }

    @Test
    fun `一拍的条目快照、收藏、逐集状态与首页进度在同一个事务里写`() = runBlocking {
        val seen = mutableListOf<Pair<List<Boolean>, List<Boolean>>>()
        val harness = harness(transactions = { h ->
            object : TransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T {
                    val before = h.written()
                    return block().also { seen += before to h.written() }
                }
            }
        })

        harness.home.getHomeProgress(refresh = true).toList()

        assertEquals(listOf(listOf(false, false, false, false) to listOf(true, true, true, true)), seen)
    }

    @Test
    fun `首次加载也不能先发出截断窗口的可操作进度`() = runBlocking {
        val harness = harness()
        assertAccurate(harness, harness.home.getHomeProgress())
    }

    @Test
    fun `全量观看状态失败时不把未知状态当作未看发出按钮`() = runBlocking {
        val harness = harness(failStates = true)
        val results = harness.home.getHomeProgress().toList()
        assertTrue(results.none { it is Load.Ready }, "短窗口或缺少逐集状态都不能成为可操作的成功结果")
        assertTrue(results.any { it is Load.Failed }, "补全失败必须给出可重试的结果")
    }

    @Test
    fun `分页确认零章节时保留作品及其他正常进度`() = runBlocking {
        val harness = harness(emptySubjectTotal = 0)
        val last = harness.home.getHomeProgress().toList().last()
        assertTrue(last is Load.Ready, "完整零章节应为 Ready，实际 $last")
        val empty = last.value.single { it.subject.id == 976 }
        assertTrue(empty.eps.isEmpty())
        assertEquals(null, EpisodeTracking.nextEpisode(empty.eps, emptyMap()))
        assertEquals(EpButtonState.Empty, EpisodeTracking.epButtonState(empty.eps, emptyMap(), LocalDate.of(2026, 10, 2)))
        assertEquals(HomeSection.CONTINUE, EpisodeTracking.homeSection(empty.subject.type, empty.eps, emptyMap(), LocalDate.of(2026, 10, 2)))
        assertEquals(1155, last.value.single { it.subject.id == 975 }.eps.size)
        assertTrue(harness.requests.contains("/v0/users/-/collections/976/episodes" to 0))
    }

    @Test
    fun `未知总数的空响应不确认零章节`() = runBlocking {
        val harness = harness(emptySubjectTotal = -1)
        val last = harness.home.getHomeProgress().toList().last()
        assertEquals(Load.Failed(Failure.Unknown), last)
        assertTrue(harness.requests.contains("/v0/users/-/collections/976/episodes" to 0))
    }

    @Test
    fun `正总数的空分页不是零章节`() = runBlocking {
        val harness = harness(emptySubjectTotal = 120)
        val last = harness.home.getHomeProgress().toList().last()
        assertEquals(Load.Failed(Failure.Unknown), last)
        assertTrue(harness.requests.contains("/v0/users/-/collections/976/episodes" to 0))
        assertTrue(harness.requests.contains("/v0/users/-/collections/976/episodes" to 100))
    }

    @Test
    fun `零章节请求失败不能当成成功空列表`() = runBlocking {
        val harness = harness(emptySubjectTotal = 0, failEmpty = true)
        val results = harness.home.getHomeProgress().toList()
        assertTrue(results.none { it is Load.Ready })
        assertTrue(results.last() is Load.Failed)
        assertTrue(harness.requests.contains("/v0/users/-/collections/976/episodes" to 0))
    }

    @Test
    fun `零章节补全途中换号不会发出成功结果`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val harness = harness(emptySubjectTotal = 0, backfillGate = gate)
        val loads = async(Dispatchers.Default) { harness.home.getHomeProgress().toList() }
        awaitUntil("零章节请求已发出") { harness.requests.contains("/v0/users/-/collections/976/episodes" to 0) }
        harness.store.clear()
        harness.store.completeSession(harness.store.beginSession("other-token"), OTHER_ACCOUNT, "other")
        gate.complete(Unit)
        val results = withTimeout(WAIT_BUDGET_MS) { loads.await() }
        assertTrue(results.none { it is Load.Ready })
        assertEquals(Load.Failed(Failure.SessionChanged), results.last())
    }

    @Test
    fun `旧首页窗口缓存没有完整章节时也必须补全才能展示`() = runBlocking {
        val harness = harness()
        harness.home.getHomeProgress().toList()
        harness.episodes.deleteBySubject(975)
        assertAccurate(harness, harness.home.getHomeProgress())
    }

    private suspend fun assertAccurate(
        harness: Harness,
        results: Flow<Load<List<HomeProgressItemBo>>>,
    ) {
        var successes = 0
        results.collect { success ->
            if (success !is Load.Ready) return@collect
            successes++
            val states = harness.collections.observer.cachedEpisodeWatchStates(975, harness.store.session())
            val item = success.value.single()
            assertEquals(1155, EpisodeTracking.mainEpisodes(item.eps).size)
            assertEquals(1155.0, EpisodeTracking.lastAiredEpisode(item.eps, LocalDate.of(2026, 10, 2))?.sort)
            val button = EpisodeTracking.epButtonState(item.eps, states, LocalDate.of(2026, 10, 2))
            assertTrue(button is EpButtonState.Next)
            assertEquals(68, button.position)
            assertEquals(68.0, button.episode.sort)
            assertEquals(1088, EpisodeTracking.airedUnwatchedCount(item.eps, states, LocalDate.of(2026, 10, 2)))
        }
        assertTrue(successes > 0)
    }

    /** 本地起先与服务端对不上时，第一拍可能是本地的旧进度；补全之后的最后一拍必须准确。 */
    private suspend fun assertLatestAccurate(harness: Harness, results: Flow<Load<List<HomeProgressItemBo>>>) {
        val last = results.toList().filterIsInstance<Load.Ready<List<HomeProgressItemBo>>>().last()
        assertAccurate(harness, flowOf(last))
    }

    @Test
    fun `请求途中换了账号 上一个账号的收藏逐集状态与首页进度都不进新账号 这次以换号失败结束`() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = harness(onHome = { arrived.complete(Unit); release.await() })
        val loads = async(Dispatchers.Default) { harness.home.getHomeProgress(refresh = true).toList() }
        withTimeout(WAIT_BUDGET_MS) { arrived.await() }

        harness.store.clear()
        harness.store.completeSession(harness.store.beginSession("other-token"), OTHER_ACCOUNT, "other")
        release.complete(Unit)

        val last = withTimeout(WAIT_BUDGET_MS) { loads.await() }.last()
        assertEquals(Load.Failed(Failure.SessionChanged), last)
        assertTrue(harness.collectionDao.store.keys.none { it.first == OTHER_ACCOUNT }, "收藏：${harness.collectionDao.store.keys}")
        assertTrue(harness.states.store.values.none { it.accountId == OTHER_ACCOUNT }, "逐集状态写进了新账号")
        assertTrue(harness.homeProgress.getByAccount(OTHER_ACCOUNT).isEmpty(), "首页进度写进了新账号")
    }

    @Test
    fun `首页请求发出后本地标了进度 迟到的收藏不盖掉`() = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = harness(onHome = { arrived.complete(Unit); release.await() })
        harness.collectionDao.upsert(UserCollectionEntity(975, SubjectType.ANIME.value, 3, epStatus = 67, updatedAt = 1L, accountId = 7, detailsLoaded = true))
        val loads = async(Dispatchers.Default) { harness.home.getHomeProgress(refresh = true).toList() }
        withTimeout(WAIT_BUDGET_MS) { arrived.await() }

        harness.collections.watchStates.syncEpisodeProgress(975, harness.store.session(), epStatus = 70)
        release.complete(Unit)

        withTimeout(WAIT_BUDGET_MS) { loads.await() }
        assertEquals(70, harness.collectionDao.store[7 to 975]?.epStatus, "首页带回的是标之前的 67")
    }

    @Test
    fun `长篇补全的逐集记录请求发出后本地标了一集 迟到的记录不盖掉`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val harness = harness(backfillGate = gate)
        val loads = async(Dispatchers.Default) { harness.home.getHomeProgress(refresh = true).toList() }
        awaitUntil("补全的逐集记录请求已发出") { harness.historyPages().isNotEmpty() }

        harness.collections.watchStates.syncSingleEpisodeWatchState(975, 100, EpisodeStatus.DONE, harness.store.session())
        gate.complete(Unit)

        withTimeout(WAIT_BUDGET_MS) { loads.await() }
        assertEquals(EpisodeStatus.DONE.value, harness.states.store[7 to 100]?.status, "服务端记录里第 100 集还没看")
        // 占用按整部作品算：迟到的补全一集也不写，首页窗口外的第 50 集没有落库
        assertEquals(null, harness.states.store[7 to 50], "条目被本地写占着时，迟到的补全整部跳过")
    }

    @Test
    fun `已有全量缓存时首拍立即可靠 不等待后台重新分页`() = runBlocking {
        val harness = harness(backfillGate = CompletableDeferred())
        harness.episodes.seed(1155)
        harness.states.upsertAll((1..1155).map { stateEntity(it) })
        val first = withTimeout(WAIT_BUDGET_MS) {
            harness.home.getHomeProgress(refresh = true).first { it is Load.Ready }
        }
        assertEquals(1155, (first as Load.Ready).value.single().eps.size)
    }

    @Test
    fun `长篇补全只走一路用户逐集分页，章节与逐集状态一起落库`() = runBlocking {
        val harness = harness()
        assertAccurate(harness, harness.home.getHomeProgress())
        assertTrue(harness.requests.none { it.first == "/v0/episodes" }, "补全不该再另拉一遍 /v0/episodes：${harness.requests}")
        assertEquals(ALL_HISTORY_PAGES, harness.historyPages().sorted(), "1155 集按 100 一页，每页只取一次")
        assertEquals(1155, harness.episodes.getBySubject(975).size)
        assertEquals(1155, harness.collections.observer.cachedEpisodeWatchStates(975, harness.store.session()).size)
    }

    @Test
    fun `本地逐集记录与服务端对得上时，自动刷新不再为长篇重新分页`() = runBlocking {
        val harness = harness()
        harness.episodes.seed(1155)
        harness.states.upsertAll((1..1155).map { stateEntity(it) })
        assertAccurate(harness, harness.home.getHomeProgress())
        assertTrue(harness.requests.any { it.first == "/p1/home" }, "这一轮走了网络")
        assertEquals(emptyList(), harness.historyPages(), "看过的集数对得上、章节新鲜又齐全，不该再拉逐集记录")
    }

    @Test
    fun `下拉刷新时本地记录对得上也为长篇重新分页`() = runBlocking {
        val harness = harness()
        harness.episodes.seed(1155)
        // 看过的集数对得上，但窗口外的记录可能在网页上改过：本地判不出来，下拉刷新照样取全
        harness.states.upsertAll((1..1155).map { stateEntity(it) })
        assertAccurate(harness, harness.home.getHomeProgress(refresh = true))
        assertEquals(ALL_HISTORY_PAGES, harness.historyPages().sorted())
    }

    @Test
    fun `在别处标过集、看过的集数对不上时，自动刷新照常补全`() = runBlocking {
        val harness = harness()
        harness.episodes.seed(1155)
        // 本地只记到第 60 集，服务端的 epStatus 是 67
        harness.states.upsertAll((1..1155).map { stateEntity(it, watchedUpTo = 60) })
        assertLatestAccurate(harness, harness.home.getHomeProgress())
        assertEquals(ALL_HISTORY_PAGES, harness.historyPages().sorted())
    }

    @Test
    fun `章节过了新鲜期，自动刷新照常补全`() = runBlocking {
        val harness = harness()
        harness.episodes.seed(1155, at = now - CacheTtl.EPISODES)
        harness.states.upsertAll((1..1155).map { stateEntity(it) })
        assertAccurate(harness, harness.home.getHomeProgress())
        assertEquals(ALL_HISTORY_PAGES, harness.historyPages().sorted())
    }

    @Test
    fun `服务端的总集数多于本地正片（窗口外新出了集）时，自动刷新照常补全`() = runBlocking {
        val harness = harness()
        harness.episodes.seed(1154)
        harness.states.upsertAll((1..1154).map { stateEntity(it) })
        assertLatestAccurate(harness, harness.home.getHomeProgress())
        assertEquals(ALL_HISTORY_PAGES, harness.historyPages().sorted())
    }

    @Test
    fun `后台补全失败仍保留已有完整进度`() = runBlocking {
        val harness = harness(failStates = true)
        // 章节过了新鲜期：补全一定会发，且失败
        harness.episodes.seed(1155, at = now - CacheTtl.EPISODES)
        harness.states.upsertAll((1..1155).map { stateEntity(it) })
        assertAccurate(harness, harness.home.getHomeProgress(refresh = true))
    }

    @Test
    fun `首页补全长篇的所有页共用并发预算`() = runBlocking {
        val gate = ConcurrencyGate()
        val harness = harness(onHistory = { offset -> if (offset > 0) gate.enter() })
        val job = async(Dispatchers.Default) { harness.home.getHomeProgress(refresh = true).toList() }
        gate.releaseAfterBudget(4)
        assertTrue(withTimeout(WAIT_BUDGET_MS) { job.await() }.any { it is Load.Ready })
        assertEquals(ALL_HISTORY_PAGES, harness.historyPages().sorted())
        assertTrue(gate.peak <= 4, "首页补全分页峰值应不超过 4，实际为 ${gate.peak}")
    }

    @Test
    fun `首页多部长篇的首屏共用预算且全部补全`() = runBlocking {
        val gate = ConcurrencyGate()
        val ids = (975..979).toList()
        val base = homeJson()
        val item = base.substringAfter("{\"progress\":[").substringBefore("],\"timeline\"")
        val items = ids.joinToString(",") { id ->
            var row = item.replace("\"subjectID\":975", "\"subjectID\":$id")
                .replace("\"id\":975,", "\"id\":$id,")
            for (ep in 67..139) row = row.replace("\"id\":$ep,", "\"id\":${episodeKey(id, ep)},")
            row
        }
        val homeBody = base.replace(item, items)
        val computation = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
        val harness = harness(homeBody = homeBody, computationDispatcher = computation, onHistory = { offset ->
            if (offset == 0) gate.enter()
        })
        val job = async(Dispatchers.Default) { harness.home.getHomeProgress(refresh = true).toList() }
        gate.releaseAfterBudget(4)
        val results = withTimeout(WAIT_BUDGET_MS) { job.await() }
        assertTrue(results.any { it is Load.Ready }, "所有作品都应成功补全")
        assertEquals(5, gate.calls)
        assertTrue(gate.peak <= 4, "首页首屏峰值应不超过 4，实际为 ${gate.peak}")
        assertEquals(ids.toSet(), harness.requests.filter { it.first.endsWith("/episodes") }
            .map { it.first.removePrefix("/v0/users/-/collections/").removeSuffix("/episodes").toInt() }.toSet())
        } finally { computation.close() }
    }

    private suspend fun harness(
        failStates: Boolean = false,
        emptySubjectTotal: Int? = null,
        failEmpty: Boolean = false,
        backfillGate: CompletableDeferred<Unit>? = null,
        /** `/p1/home` 回响应之前先跑它：用例在这里卡住请求，期间切换账号。 */
        onHome: (suspend () -> Unit)? = null,
        onHistory: (suspend (Int) -> Unit)? = null,
        homeBody: String = homeJson(includeEmpty = emptySubjectTotal != null),
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
        transactions: (Harness) -> TransactionRunner = { PassthroughTransactions },
    ): Harness {
        val store = testTokenStore(scope = scope)
        store.completeSession(store.beginSession("test-token"), 7, "fixture_user")
        val requests = ConcurrentLinkedQueue<Pair<String, Int>>()
        val client = resources.apiClient(MockEngine { request ->
            val offset = request.url.parameters["offset"]?.toInt() ?: 0
            requests += request.url.encodedPath to offset
            val range = (offset + 1)..minOf(offset + 100, 1155)
            val headers = headersOf("Content-Type", ContentType.Application.Json.toString())
            if (request.url.encodedPath != "/p1/home") {
                backfillGate?.await()
                onHistory?.invoke(offset)
            } else onHome?.invoke()
            when (request.url.encodedPath) {
                "/p1/home" -> respond(homeBody, HttpStatusCode.OK, headers)
                "/v0/users/-/collections/976/episodes".takeIf { emptySubjectTotal != null || failEmpty } -> if (failEmpty) {
                    respond("{}", HttpStatusCode.ServiceUnavailable, headers)
                } else {
                    respond("""{"data":[],"total":$emptySubjectTotal,"limit":100,"offset":0}""", HttpStatusCode.OK, headers)
                }
                in (975..979).map { "/v0/users/-/collections/$it/episodes" } -> if (failStates) {
                    respond("{}", HttpStatusCode.ServiceUnavailable, headers)
                } else {
                    val subjectId = request.url.encodedPath.removePrefix("/v0/users/-/collections/").removeSuffix("/episodes").toInt()
                    respond("""{"data":[${range.joinToString(",") { """{"episode":${v0Episode(it, subjectId)},"type":${if (it <= 67) 2 else 0},"updated_at":${now / 1000}}""" }}],"total":1155,"limit":100,"offset":$offset}""", HttpStatusCode.OK, headers)
                }
                else -> respond("{}", HttpStatusCode.NotFound, headers)
            }
        })

        val subjects = FakeSubjectDao()
        val episodes = FakeEpisodeDao()
        val states = FakeEpisodeWatchStateDao()
        val collectionDao = FakeUserCollectionDao()
        val homeProgress = FakeHomeProgressDao()
        val collections = testCollections(resources, CollectionApi(client), subjects, collectionDao, store, states, episodes, clock = AppClock { now })
        lateinit var harness: Harness
        val home = testHomeRepository(
            NextApi(client), homeProgress, subjects, store, collections, episodes, AppClock { now }, computationDispatcher,
            transactions = TransactionRunnerProxy { transactions(harness) },
        )
        harness = Harness(home, episodes, states, collections, store, collectionDao, homeProgress, requests, subjects)
        return harness
    }

    private data class Harness(
        val home: HomeRepository,
        val episodes: FakeEpisodeDao,
        val states: FakeEpisodeWatchStateDao,
        val collections: TestCollections,
        val store: TokenStore,
        val collectionDao: FakeUserCollectionDao,
        val homeProgress: FakeHomeProgressDao,
        /** 发出的请求：路径与 offset。 */
        val requests: Collection<Pair<String, Int>>,
        val subjects: FakeSubjectDao,
    ) {
        /** 首页一拍写的四张表各有没有本账号的行：条目快照、收藏、逐集状态、首页进度。 */
        fun written() = listOf(
            subjects.store.isNotEmpty(),
            collectionDao.store.isNotEmpty(),
            states.store.isNotEmpty(),
            homeProgress.store.isNotEmpty(),
        )
        /** 拉过的 975 逐集记录各页的 offset。 */
        fun historyPages() = requests.filter { it.first == "/v0/users/-/collections/975/episodes" }.map { it.second }
    }

    /** 预置这部的 1..[count] 集，取回时刻是 [at]（像在那一刻取回的一样）。 */
    private suspend fun EpisodeDao.seed(count: Int, at: Long = now) =
        replaceAll(975, (1..count).map { episodeEntity(it, updatedAt = at) }, fetchedAt = at)

    private fun episodeEntity(i: Int, updatedAt: Long = now) = EpisodeEntity(i, 975, 0, "ep$i", "", i.toDouble(), airdate = "2020-01-01", updatedAt = updatedAt)
    private fun stateEntity(i: Int, watchedUpTo: Int = 67) = EpisodeWatchStateEntity(i, 975, 7, if (i <= watchedUpTo) 2 else 0, now)
    private fun episodeKey(subjectId: Int, i: Int) = (subjectId - 975) * 10_000 + i
    private fun v0Episode(i: Int, subjectId: Int = 975) = """{"id":${episodeKey(subjectId, i)},"subject_id":$subjectId,"type":0,"name":"ep$i","name_cn":"","sort":$i,"ep":$i,"airdate":"2020-01-01","comment":0,"duration":"","desc":"","disc":0}"""
    private companion object {
        /** 请求途中换到的另一个账号。 */
        const val OTHER_ACCOUNT = 8

        /** 1155 集按 100 一页的全部 offset。 */
        val ALL_HISTORY_PAGES = (0 until 1155 step 100).toList()
    }

    private fun homeJson(includeEmpty: Boolean = false): String {
        val eps = (67..139).joinToString(",") { i ->
            """{"id":$i,"subjectID":975,"type":0,"name":"ep$i","nameCN":"","sort":$i,"disc":0,"airdate":"2020-01-01","comment":0,"duration":"","desc":"","collection":${if (i == 67) """{"status":2,"updatedAt":${now / 1000}}""" else "null"}}"""
        }
        val empty = if (includeEmpty) """,{"subject":{"id":976,"name":"Empty","nameCN":"","type":${SubjectType.ANIME.value},"info":"","metaTags":[],"rating":{"rank":0,"count":[],"score":0.0,"total":0},"locked":false,"nsfw":false,"eps":0,"volumes":0,"series":false,"doing":0,"airDate":"","weekday":0},"interest":{"id":2,"rate":0,"type":3,"comment":"","tags":[],"epStatus":0,"volStatus":0,"private":false,"updatedAt":${now / 1000}},"percent":0.0,"todayOnAir":false,"lastUnwatchedEp":null,"eps":[]}""" else ""
        return """{"progress":[{"subject":{"id":975,"name":"ONE PIECE","nameCN":"航海王","type":${SubjectType.ANIME.value},"info":"","metaTags":[],"rating":{"rank":1,"count":[],"score":8.0,"total":10},"locked":false,"nsfw":false,"eps":1155,"volumes":0,"series":false,"doing":10,"airDate":"1999-10-20","weekday":7},"interest":{"id":1,"rate":0,"type":3,"comment":"","tags":[],"epStatus":67,"volStatus":0,"private":false,"updatedAt":${now / 1000}},"percent":0.05,"todayOnAir":false,"lastUnwatchedEp":{"id":68,"sort":68},"eps":[$eps]}$empty],"timeline":[],"groupTopics":[],"famousGroups":[],"hotSubjectTopics":[],"calendar":{}}"""
    }
}

/** 构造时还拿不到的事务实现：每次调用时再取（用例的事务要读 [HomeRepositoryProgressTest] 的 harness）。 */
private class TransactionRunnerProxy(private val target: () -> TransactionRunner) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = target().inTransaction(block)
}
