package io.github.touhougo.chogumi.core.data.progress

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.repository.ConcurrentRequestRecorder
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeDao
import io.github.touhougo.chogumi.core.data.repository.FakeEpisodeWatchStateDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectDao
import io.github.touhougo.chogumi.core.data.repository.FakeSubjectSectionDao
import io.github.touhougo.chogumi.core.data.repository.FakeUserCollectionDao
import io.github.touhougo.chogumi.core.data.repository.NsfwFilter
import io.github.touhougo.chogumi.core.data.repository.NsfwPreferenceRepository
import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.testCollections
import io.github.touhougo.chogumi.core.data.repository.testSubjectRepository
import io.github.touhougo.chogumi.core.data.repository.testWebSessionRepository
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.CollectionMarkFailure
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.MarkOrigin
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import io.github.touhougo.chogumi.core.network.api.NextApi
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.api.SubjectApi
import io.github.touhougo.chogumi.core.network.api.WebPageApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

/** 动作归属在发起时确定，排队、旧窗口与在途回包不能把操作移到另一账号。 */
class ActionSessionTest {
    private val fixtures = mutableListOf<Fixture>()

    @AfterTest
    fun tearDown() {
        fixtures.forEach { it.close() }
    }

    private class Fixture {
        private val resources = TestResources()
        val directory = Files.createTempDirectory("action-session").toFile()
        private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val dataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val actionScope = CoroutineScope(SupervisorJob() + dispatcher)
        private val pauses = mutableListOf<CountDownLatch>()
        val requests = ConcurrentRequestRecorder()
        val requestArrived = CompletableDeferred<Unit>()
        var beforeResponse: suspend () -> Unit = {}
        val specialRequests = AtomicInteger()
        val firstSpecialReached = CompletableDeferred<Unit>()
        val releaseFirstSpecial = CompletableDeferred<Unit>()
        var specialResults: List<Boolean>? = null
        /** 用户逐集端点（失败回滚时的只读回读）给什么；null 时照旧回空响应，回读失败。 */
        var serverEpisodes: String? = null
        private val client = testApiClient(MockEngine { request ->
            requests.record(request)
            requestArrived.complete(Unit)
            beforeResponse()
            if (request.url.encodedPath.endsWith("/episodes/102") && specialResults != null) {
                val index = specialRequests.getAndIncrement()
                if (index == 0) {
                    firstSpecialReached.complete(Unit)
                    releaseFirstSpecial.await()
                }
                if (specialResults!![index]) respond("", HttpStatusCode.NoContent)
                else respond("{}", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))
            } else if (request.url.encodedPath.endsWith("/collections/$ANIME/episodes") && serverEpisodes != null) {
                respond(serverEpisodes!!, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            } else respond("", HttpStatusCode.NoContent)
        })
        private val webClient = testApiClient { error("此动作不访问网页") }
        val tokens = TokenStore(PreferenceDataStoreFactory.create(scope = dataScope) {
            directory.resolve("tokens.preferences_pb")
        })
        val gate = CommitGate()
        val optimisticWrite = CompletableDeferred<Unit>()
        var watchWrites = false
        var pauseOtherAccount = false
        val otherAccountRead = CompletableDeferred<Unit>()
        val allowOtherAccount = CompletableDeferred<Unit>()
        val collectionsDao = object : FakeUserCollectionDao() {
            override fun observeCurrent(accountId: Int) = if (pauseOtherAccount && accountId == OTHER_ACCOUNT) {
                flow {
                    otherAccountRead.complete(Unit)
                    allowOtherAccount.await()
                    emitAll(super.observeCurrent(accountId))
                }
            } else super.observeCurrent(accountId)

            override suspend fun upsert(collection: UserCollectionEntity) {
                super.upsert(collection)
                if (watchWrites && collection.epStatus > 0) optimisticWrite.complete(Unit)
            }
        }
        val watchDao = FakeEpisodeWatchStateDao().apply {
            onChange = { rows -> if (watchWrites && rows.isNotEmpty()) optimisticWrite.complete(Unit) }
        }
        private val subjectsDao = FakeSubjectDao()
        private val collections = testCollections(
            resources,
            CollectionApi(client), subjectsDao, collectionsDao, tokens, watchDao, webClient = webClient,
        )
        private val preferences = PreferenceDataStoreFactory.create(scope = dataScope) {
            directory.resolve("prefs.preferences_pb")
        }
        private val subjects = testSubjectRepository(
            resources,
            SubjectApi(client), NextApi(client), subjectsDao, FakeEpisodeDao(), FakeSubjectSectionDao(),
            NsfwFilter(NsfwPreferenceRepository(
                preferences, PrivacyApi(client), tokens, testWebSessionRepository(tokens, webClient),
            )),
            tokenStore = tokens,
            webPageApi = WebPageApi(webClient),
        )
        private val orchestrator = collections.progressOrchestrator(subjects)
        val progress = collections.epTapCoordinator(orchestrator, actionScope, { 0L }, gate::await)
        val marker = collections.markCoordinator(orchestrator, actionScope, { 0L }, gate::await)
        val progressFailure = CompletableDeferred<ProgressWriteError>()
        val markFailure = CompletableDeferred<CollectionMarkFailure>()
        val markPending = CompletableDeferred<Unit>()
        val markOrigin = MarkOrigin()
        val episode = EpisodeBo(101, ANIME, 0, "第一集", "", 1.0)
        val special = EpisodeBo(102, ANIME, 1, "特别篇", "", 1.0)
        val anime = ProgressSubject(ANIME, SubjectType.ANIME, listOf(episode, special))
        val book = ProgressSubject(BOOK, SubjectType.BOOK, emptyList(), totalEps = 10)

        suspend fun initialize() {
            dataScope.launch(start = CoroutineStart.UNDISPATCHED) {
                progress.errors.collect { progressFailure.complete(it) }
            }
            dataScope.launch(start = CoroutineStart.UNDISPATCHED) {
                marker.failures.collect { markFailure.complete(it) }
            }
            dataScope.launch {
                marker.marks.collect { if (it.pending != null) markPending.complete(Unit) }
            }
            signIn(ACCOUNT)
            for (account in listOf(ACCOUNT, OTHER_ACCOUNT)) {
                for (id in listOf(ANIME, BOOK, SENTINEL)) {
                    collectionsDao.upsert(UserCollectionEntity(
                        subjectId = id,
                        subjectType = if (id == ANIME) SubjectType.ANIME.value else SubjectType.BOOK.value,
                        type = if (account == ACCOUNT) CollectionType.WISH.value else CollectionType.DOING.value,
                        accountId = account,
                        updatedAt = 1L,
                    ))
                }
            }
            withTimeout(WAIT_BUDGET_MS) {
                marker.marks.first { it.types[SENTINEL] == CollectionType.WISH }
            }
        }

        suspend fun signIn(account: Int) {
            tokens.completeSession(tokens.beginSession("fixture-$account"), account, "fixture_$account")
        }

        suspend fun pause(): CountDownLatch {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1).also { pauses += it }
            actionScope.launch {
                entered.complete(Unit)
                release.await()
            }
            withTimeout(WAIT_BUDGET_MS) { entered.await() }
            return release
        }

        fun close() {
            pauses.forEach { it.countDown() }
            cancelAndAwait(listOf(actionScope, dataScope))
            client.close()
            webClient.close()
            dispatcher.close()
            directory.deleteRecursively()
            resources.close()
        }
    }

    private suspend fun fixture(configure: (Fixture) -> Unit = {}): Fixture = Fixture().also {
        fixtures += it
        configure(it)
        try {
            it.initialize()
        } catch (failure: Exception) {
            it.close()
            fixtures.remove(it)
            throw failure
        }
    }

    private suspend fun assertQueuedProgress(action: (Fixture) -> Unit) {
        val f = fixture()
        val paused = f.pause()
        f.watchWrites = true
        action(f)
        f.signIn(OTHER_ACCOUNT)
        paused.countDown()
        val failure = withTimeout(WAIT_BUDGET_MS) {
            select {
                f.progressFailure.onAwait { it.failure }
                f.optimisticWrite.onAwait { null }
            }
        }
        assertEquals(0, f.collectionsDao.store[OTHER_ACCOUNT to BOOK]?.epStatus, "旧点击不能增加新账号书籍进度")
        assertEquals(0, f.collectionsDao.store[OTHER_ACCOUNT to ANIME]?.epStatus, "旧点击不能增加新账号正片进度")
        assertTrue(f.watchDao.store.isEmpty(), "旧点击不能给新账号写逐集状态")
        assertEquals(Failure.SessionChanged, failure)
        assertTrue(f.progress.active.value.isEmpty())
        assertTrue(f.progress.pending.value.isEmpty())
        assertFalse(f.requests.isNotEmpty())
    }

    @Test
    fun `前进一步入队后换号 旧动作失败且新账号不写`() = runBlocking {
        assertQueuedProgress { it.progress.advance(ProgressOrigin.HOME, it.book, TODAY) }
    }

    @Test
    fun `看到某集入队后换号 旧动作失败且新账号不写`() = runBlocking {
        assertQueuedProgress { it.progress.watchUpTo(ProgressOrigin.DETAILS, it.anime, io.github.touhougo.chogumi.core.model.EpisodeProgressTarget.Episode(101, 1.0)) }
    }

    private suspend fun assertSpecialResults(results: List<Boolean>, expected: EpisodeStatus, serverEpisodes: String? = null) {
        val f = fixture()
        f.specialResults = results
        f.serverEpisodes = serverEpisodes
        f.progress.toggleSpecial(ProgressOrigin.DETAILS, f.anime, f.special)
        withTimeout(WAIT_BUDGET_MS) { f.firstSpecialReached.await() }
        f.progress.toggleSpecial(ProgressOrigin.DETAILS, f.anime, f.special)
        // 第一笔卡在服务端期间，第二次切换的乐观写一旦落下就一直在
        awaitUntil("第二次切换已乐观写入") { f.watchDao.store[ACCOUNT to f.special.id]?.status == EpisodeStatus.NONE.value }
        f.releaseFirstSpecial.complete(Unit)
        awaitUntil("两次特别篇写入完整收尾") { f.specialRequests.get() == 2 && ANIME !in f.progress.active.value }
        assertEquals(expected.value, f.watchDao.store[ACCOUNT to f.special.id]?.status ?: EpisodeStatus.NONE.value)
        assertEquals(if (expected == EpisodeStatus.DONE) 1 else 0, f.collectionsDao.store[ACCOUNT to ANIME]?.epStatus)
    }

    @Test
    fun `特别篇响应途中断开而服务端已改 失败后按服务端回读校准`() = runBlocking {
        val f = fixture()
        f.specialResults = listOf(false)
        // 服务端其实已把这一集记成看过（响应在途中断，客户端只拿到失败）
        f.serverEpisodes = SERVER_SPECIAL_DONE
        f.progress.toggleSpecial(ProgressOrigin.DETAILS, f.anime, f.special)
        withTimeout(WAIT_BUDGET_MS) { f.firstSpecialReached.await() }
        f.releaseFirstSpecial.complete(Unit)
        awaitUntil("特别篇写入完整收尾") { f.specialRequests.get() == 1 && ANIME !in f.progress.active.value }
        assertEquals(EpisodeStatus.DONE.value, f.watchDao.store[ACCOUNT to f.special.id]?.status, "本地跟服务端一致，不退回点之前")
        assertEquals(1, f.collectionsDao.store[ACCOUNT to ANIME]?.epStatus)
    }

    @Test
    fun `两次特别篇乐观切换均失败 回到未受污染的初始状态`() = runBlocking {
        assertSpecialResults(listOf(false, false), EpisodeStatus.NONE)
    }

    @Test
    fun `首个特别篇切换成功后第二个失败 回到成功确认值`() = runBlocking {
        assertSpecialResults(listOf(true, false), EpisodeStatus.DONE)
    }

    @Test
    fun `首个特别篇切换失败后第二个成功 保持末次成功值`() = runBlocking {
        // 回读会读到「看过」：旧的那笔失败若仍去回读、回滚，本地就被改成看过，盖掉第二笔的成功值
        assertSpecialResults(listOf(false, true), EpisodeStatus.NONE, serverEpisodes = SERVER_SPECIAL_DONE)
    }

    @Test
    fun `特别篇切换入队后换号 旧动作失败且新账号不写`() = runBlocking {
        assertQueuedProgress { it.progress.toggleSpecial(ProgressOrigin.DETAILS, it.anime, it.special) }
    }

    @Test
    fun `收藏标记入队后换号 不替新账号建立提交窗口`() = runBlocking {
        val f = fixture()
        val paused = f.pause()
        f.marker.mark(f.markOrigin, BOOK, CollectionType.DONE)
        f.signIn(OTHER_ACCOUNT)
        paused.countDown()
        val failure = withTimeout(WAIT_BUDGET_MS) {
            select {
                f.markFailure.onAwait { it.failure }
                f.markPending.onAwait { null }
            }
        }
        assertNull(f.marker.marks.value.pending, "旧点击不能成为新账号的待提交标记")
        assertEquals(Failure.SessionChanged, failure)
    }

    @Test
    fun `换号后同作品新标记不复用旧窗口会话`() = runBlocking {
        val f = fixture()
        f.marker.mark(f.markOrigin, BOOK, CollectionType.DONE)
        withTimeout(WAIT_BUDGET_MS) { f.markPending.await() }
        f.signIn(OTHER_ACCOUNT)
        withTimeout(WAIT_BUDGET_MS) {
            f.marker.marks.first { it.types[SENTINEL] == CollectionType.DOING }
        }
        f.marker.mark(f.markOrigin, BOOK, CollectionType.ON_HOLD)
        withTimeout(WAIT_BUDGET_MS) {
            f.marker.marks.first { it.pending?.type == CollectionType.ON_HOLD }
        }
        f.gate.open()
        withTimeout(WAIT_BUDGET_MS) {
            select {
                f.requestArrived.onAwait { }
                f.markFailure.onAwait { }
            }
        }
        assertTrue(f.requests.isNotEmpty(), "新标记应以新账号发出，而不是因复用旧会话失败")
        awaitUntil("新账号标记落库") {
            f.collectionsDao.store[OTHER_ACCOUNT to BOOK]?.type == CollectionType.ON_HOLD.value
        }
        assertEquals(CollectionType.WISH.value, f.collectionsDao.store[ACCOUNT to BOOK]?.type)
        assertEquals("Bearer fixture-$OTHER_ACCOUNT", f.requests.single().headers["Authorization"])
    }

    @Test
    fun `旧账号待提交进度不出现在新账号页面上`() = runBlocking {
        val f = fixture()
        f.watchWrites = true
        f.progress.advance(ProgressOrigin.HOME, f.book, TODAY)
        withTimeout(WAIT_BUDGET_MS) { f.progress.pending.first { BOOK in it } }
        f.signIn(OTHER_ACCOUNT)
        val crossed = withTimeout(WAIT_BUDGET_MS) {
            f.marker.marks.first { it.types[SENTINEL] == CollectionType.DOING }
        }
        assertEquals(CollectionType.DOING, crossed.types[BOOK])
        assertTrue(f.progress.pending.value.isEmpty(), "新账号不能显示旧账号的进度倒计时")
    }

    @Test
    fun `旧账号正在建收藏不占用新账号收藏按钮`() = runBlocking {
        val f = fixture()
        val release = CompletableDeferred<Unit>()
        f.beforeResponse = { release.await() }
        f.gate.open()
        f.progress.toggleSpecial(ProgressOrigin.DETAILS, f.anime.copy(hasCollection = false), f.special)
        withTimeout(WAIT_BUDGET_MS) { f.requestArrived.await() }
        f.signIn(OTHER_ACCOUNT)
        withTimeout(WAIT_BUDGET_MS) {
            f.marker.marks.first { it.types[SENTINEL] == CollectionType.DOING }
        }
        assertTrue(f.progress.creatingCollection.value.isEmpty(), "新账号收藏按钮不能被旧账号建收藏锁住")
        release.complete(Unit)
        assertEquals(Failure.SessionChanged, withTimeout(WAIT_BUDGET_MS) { f.progressFailure.await() }.failure)
    }

    @Test
    fun `新账号同作品进度窗口不合并旧账号计数`() = runBlocking {
        val f = fixture()
        f.progress.advance(ProgressOrigin.HOME, f.book, TODAY)
        withTimeout(WAIT_BUDGET_MS) { f.progress.pending.first { BOOK in it } }
        f.signIn(OTHER_ACCOUNT)
        f.progress.advance(ProgressOrigin.HOME, f.book, TODAY)
        awaitUntil("新账号的进度已乐观写入") {
            f.collectionsDao.store[OTHER_ACCOUNT to BOOK]?.epStatus == 1
        }
        val pending = withTimeout(WAIT_BUDGET_MS) { f.progress.pending.first { BOOK in it } }
        assertEquals(1, pending[BOOK]?.count)
        f.gate.open()
        withTimeout(WAIT_BUDGET_MS) { f.requestArrived.await() }
        awaitUntil("新账号的进度提交完整结束") { BOOK !in f.progress.active.value }
        assertEquals(1, f.collectionsDao.store[OTHER_ACCOUNT to BOOK]?.epStatus)
        assertEquals("Bearer fixture-$OTHER_ACCOUNT", f.requests.single().headers["Authorization"])
    }

    @Test
    fun `旧提交收尾不能清掉新账号同作品的倒计时`() = runBlocking {
        val f = fixture()
        val release = CompletableDeferred<Unit>()
        f.beforeResponse = { release.await() }
        f.gate.open()
        f.progress.advance(ProgressOrigin.HOME, f.book, TODAY)
        withTimeout(WAIT_BUDGET_MS) { f.requestArrived.await() }
        f.gate.close()
        f.signIn(OTHER_ACCOUNT)
        f.progress.advance(ProgressOrigin.HOME, f.book, TODAY)
        awaitUntil("新账号窗口的本地写入完成") { f.collectionsDao.store[OTHER_ACCOUNT to BOOK]?.epStatus == 1 }
        val pending = withTimeout(WAIT_BUDGET_MS) { f.progress.pending.first { BOOK in it } }
        release.complete(Unit)
        assertEquals(Failure.SessionChanged, withTimeout(WAIT_BUDGET_MS) { f.progressFailure.await() }.failure)
        assertEquals(pending[BOOK], f.progress.pending.value[BOOK], "旧账号提交收尾不得清掉新窗口的倒计时")
        f.gate.open()
        awaitUntil("新旧提交均收尾") { BOOK !in f.progress.active.value }
        assertEquals(1, f.collectionsDao.store[OTHER_ACCOUNT to BOOK]?.epStatus)
    }

    @Test
    fun `新账号收藏首拍未到时 旧账号待提交标记已不可见`() = runBlocking {
        val f = fixture()
        f.marker.mark(f.markOrigin, BOOK, CollectionType.DONE)
        withTimeout(WAIT_BUDGET_MS) { f.markPending.await() }
        val paused = f.pause()
        f.pauseOtherAccount = true
        f.signIn(OTHER_ACCOUNT)
        paused.countDown()
        withTimeout(WAIT_BUDGET_MS) { f.otherAccountRead.await() }
        val marks = withTimeout(WAIT_BUDGET_MS) { f.marker.marks.first { it.types.isEmpty() } }
        assertNull(marks.pending, "当前账号查询首拍未到不能继续显示旧标记")
        assertTrue(marks.types.isEmpty(), "当前账号查询首拍未到不能继续显示旧收藏表")
        f.allowOtherAccount.complete(Unit)
        assertEquals(CollectionType.DOING, withTimeout(WAIT_BUDGET_MS) {
            f.marker.marks.first { it.types[SENTINEL] == CollectionType.DOING }
        }.types[BOOK])
    }

    @Test
    fun `新账号收藏首拍未到时 旧账号在途叠加已不可见`() = runBlocking {
        val f = fixture()
        val release = CompletableDeferred<Unit>()
        f.beforeResponse = { release.await() }
        f.gate.open()
        f.marker.mark(f.markOrigin, BOOK, CollectionType.DONE)
        withTimeout(WAIT_BUDGET_MS) { f.requestArrived.await() }
        val paused = f.pause()
        f.pauseOtherAccount = true
        f.signIn(OTHER_ACCOUNT)
        paused.countDown()
        withTimeout(WAIT_BUDGET_MS) { f.otherAccountRead.await() }
        val marks = withTimeout(WAIT_BUDGET_MS) { f.marker.marks.first { it.types.isEmpty() } }
        assertTrue(marks.types.isEmpty(), "旧在途叠加不能等待新账号本地首拍才消失")
        f.allowOtherAccount.complete(Unit)
        release.complete(Unit)
        assertEquals(Failure.SessionChanged, withTimeout(WAIT_BUDGET_MS) { f.markFailure.await() }.failure)
    }

    @Test
    fun `旧账号待提交标记不出现在新账号海报上`() = runBlocking {
        val f = fixture()
        f.marker.mark(f.markOrigin, BOOK, CollectionType.DONE)
        withTimeout(WAIT_BUDGET_MS) { f.markPending.await() }
        f.signIn(OTHER_ACCOUNT)
        val marks = withTimeout(WAIT_BUDGET_MS) {
            f.marker.marks.first { it.types[SENTINEL] == CollectionType.DOING }
        }
        assertNull(marks.pending)
        assertEquals(CollectionType.DOING, marks.types[BOOK])
    }

    @Test
    fun `旧账号在途叠加不覆盖新账号海报状态`() = runBlocking {
        val f = fixture()
        val release = CompletableDeferred<Unit>()
        f.beforeResponse = { release.await() }
        f.gate.open()
        f.marker.mark(f.markOrigin, BOOK, CollectionType.DONE)
        withTimeout(WAIT_BUDGET_MS) { f.requestArrived.await() }
        f.signIn(OTHER_ACCOUNT)
        val marks = withTimeout(WAIT_BUDGET_MS) {
            f.marker.marks.first { it.types[SENTINEL] == CollectionType.DOING }
        }
        assertEquals(CollectionType.DOING, marks.types[BOOK])
        assertNull(marks.pending)
        release.complete(Unit)
        withTimeout(WAIT_BUDGET_MS) { f.markFailure.await() }
        assertEquals(CollectionType.DOING.value, f.collectionsDao.store[OTHER_ACCOUNT to BOOK]?.type)
    }

    private companion object {
        const val ACCOUNT = 7
        const val OTHER_ACCOUNT = 70
        const val ANIME = 8
        const val BOOK = 9
        const val SENTINEL = 10
        val TODAY: LocalDate = LocalDate.of(2026, 10, 24)

        /** 服务端逐集回读：特别篇 102 已看过。 */
        const val SERVER_SPECIAL_DONE = """{"total":1,"limit":100,"offset":0,"data":[{"episode":{"id":102,"subject_id":$ANIME,"type":1,"name":"特别篇","name_cn":"","comment":0,"duration":"","desc":"","disc":0,"sort":1.0,"airdate":""},"type":2,"updated_at":1000}]}"""
    }
}
