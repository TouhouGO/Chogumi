package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.db.collection.UserCollectionEntity
import io.github.touhougo.chogumi.core.data.db.episode.EpisodeWatchStateEntity
import io.github.touhougo.chogumi.core.data.db.subject.SubjectEntity
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.network.api.CollectionApi
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlin.test.assertFailsWith

class CollectionObserverStorageFailureTest {
    @get:org.junit.Rule
    val resources = TestResources()

    private class ThrowingPreferencesDataStore(private val failure: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw failure }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw failure
    }

    private class ScriptedUserCollectionDao(
        private val source: (Int) -> Flow<List<UserCollectionEntity>>,
    ) : FakeUserCollectionDao() {
        override fun observeCurrent(accountId: Int): Flow<List<UserCollectionEntity>> = source(accountId)
    }

    private fun observer(
        tokenStore: TokenStore,
        userCollectionDao: FakeUserCollectionDao = FakeUserCollectionDao(),
        episodeWatchStateDao: FakeEpisodeWatchStateDao = FakeEpisodeWatchStateDao(),
        subjectDao: FakeSubjectDao = FakeSubjectDao(),
        computationDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = CollectionObserver(
        subjectDao,
        userCollectionDao,
        episodeWatchStateDao,
        tokenStore,
        computationDispatcher,
    )

    private fun subject(id: Int) = SubjectEntity(id = id, type = 2, name = "Anime $id", nameCn = "动画$id", updatedAt = 0)

    private fun collection(accountId: Int, subjectId: Int) = UserCollectionEntity(
        subjectId = subjectId,
        subjectType = 2,
        type = CollectionType.DOING.value,
        detailsLoaded = true,
        accountId = accountId,
        updatedAt = 0,
    )

    private fun Load<List<UserCollectionBo>>.subjectIds(): List<Int> =
        assertIs<Load.Ready<List<UserCollectionBo>>>(this).value.map { it.subjectId }

    @Test
    fun `会话存储首次读取失败收成一拍 Storage`() = runBlocking {
        val observer = observer(TokenStore(ThrowingPreferencesDataStore(IOException("tokens unavailable"))))

        val loads = observer.observeCollections().take(1).toList()

        assertEquals(listOf(Load.Failed(Failure.Storage)), loads)
    }

    @Test
    fun `收藏表已有 Ready 后失效重查失败再发一拍 Storage`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val subjectDao = FakeSubjectDao().apply { upsert(subject(SUBJECT)) }
        val userCollectionDao = ScriptedUserCollectionDao {
            flow {
                emit(listOf(collection(ACCOUNT, SUBJECT)))
                throw IOException("database went away")
            }
        }

        val loads = observer(tokenStore, userCollectionDao, subjectDao = subjectDao).observeCollections().take(2).toList()

        assertEquals(listOf(SUBJECT), loads[0].subjectIds())
        assertEquals(Load.Failed(Failure.Storage), loads[1])
    }

    @Test
    fun `进度快照两个分支同时失败收成一拍 Storage`() = runTest {
        // 会话也放在 testScheduler 上提交，runCurrent 之后两路 DAO 的启动才有确定的调度边界。
        val tokenStore = testTokenStore(backgroundScope)
        val pending = tokenStore.beginSession("fixture")
        tokenStore.completeSession(pending, ACCOUNT, "fixture_user")
        val collectionsEntered = CompletableDeferred<Unit>()
        val watchesEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val userCollectionDao = ScriptedUserCollectionDao {
            flow {
                collectionsEntered.complete(Unit)
                release.await()
                throw IOException("collections unavailable")
            }
        }
        val watchStateDao = object : FakeEpisodeWatchStateDao() {
            override fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>> =
                flow {
                    watchesEntered.complete(Unit)
                    release.await()
                    throw IOException("watch states unavailable")
                }
        }

        // 收下全部拍子（不只第一拍）：两路各失败一次，去重后只剩一拍
        val updates = CopyOnWriteArrayList<CollectionProgressUpdate>()
        backgroundScope.launch {
            observer(
                tokenStore,
                userCollectionDao,
                watchStateDao,
                computationDispatcher = StandardTestDispatcher(testScheduler),
            ).observeProgress().collect { updates += it }
        }

        testScheduler.runCurrent()
        assertTrue(collectionsEntered.isCompleted, "收藏分支没有进入失败闸门")
        assertTrue(watchesEntered.isCompleted, "逐集分支没有进入失败闸门")
        release.complete(Unit)
        testScheduler.runCurrent()
        val failures = updates.filterIsInstance<CollectionProgressUpdate.Value>().map { it.load }.filterIsInstance<Load.Failed>()
        assertEquals(listOf(Load.Failed(Failure.Storage)), failures)
    }

    @Test
    fun `进度快照一分支失败另一分支未出首拍也立即失败`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val collections = ScriptedUserCollectionDao { flow { throw IOException("unavailable") } }
        val watches = object : FakeEpisodeWatchStateDao() {
            override fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>> = flow { awaitCancellation() }
        }

        val result = withTimeoutOrNull(WAIT_BUDGET_MS) { observer(tokenStore, collections, watches).progressSnapshot() }

        assertEquals(Load.Failed(Failure.Storage), result)
    }

    @Test
    fun `同步构造 DAO 流抛出的存储异常收成 Storage`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val collections = ScriptedUserCollectionDao { throw IOException("construction failed") }

        val result = observer(tokenStore, collections).observeCollections().first()

        assertEquals(Load.Failed(Failure.Storage), result)
    }

    @Test
    fun `同步构造 DAO 流的取消原样传播`() = runBlocking<Unit> {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val collections = ScriptedUserCollectionDao { throw CancellationException("cancelled") }
        val exception = assertFailsWith<CancellationException> {
            withTimeout(WAIT_BUDGET_MS) { observer(tokenStore, collections).observeCollections().first() }
        }
        assertEquals("cancelled", exception.message)
    }

    @Test
    fun `组合流中同步构造取消不会挂起 first`() = runBlocking<Unit> {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val collections = ScriptedUserCollectionDao { throw CancellationException("cancelled") }
        val exception = assertFailsWith<CancellationException> {
            withTimeout(WAIT_BUDGET_MS) { observer(tokenStore, collections).progressSnapshot() }
        }
        assertEquals("cancelled", exception.message)
    }

    @Test
    fun `组合流收集时取消不会变成 Storage 或挂起`() = runBlocking<Unit> {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val collections = ScriptedUserCollectionDao { flow { throw CancellationException("collect cancelled") } }
        val exception = assertFailsWith<CancellationException> {
            withTimeout(WAIT_BUDGET_MS) { observer(tokenStore, collections).progressSnapshot() }
        }
        assertEquals("collect cancelled", exception.message)
    }

    @Test
    fun `组合观察逐集分支取消原样传播 收藏分支未首拍`() = runBlocking<Unit> {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val collections = ScriptedUserCollectionDao { flow { awaitCancellation() } }
        val watches = object : FakeEpisodeWatchStateDao() {
            override fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>> =
                flow { throw CancellationException("watch cancelled") }
        }
        val exception = assertFailsWith<CancellationException> {
            withTimeout(WAIT_BUDGET_MS) {
                observer(tokenStore, collections, watches).observeProgress()
                    .first { it is CollectionProgressUpdate.Value && it.load !is Load.Pending }
            }
        }
        assertEquals("watch cancelled", exception.message)
    }

    @Test
    fun `外部取消组合观察不发 Storage`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val collections = ScriptedUserCollectionDao { flow { awaitCancellation() } }
        val updates = CopyOnWriteArrayList<CollectionProgressUpdate>()
        val job = launch(Dispatchers.Unconfined) {
            observer(tokenStore, collections).observeProgress().collect { updates += it }
        }
        awaitUntil("组合观察启动") { updates.isNotEmpty() }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(updates.none { it is CollectionProgressUpdate.Value && it.load is Load.Failed })
    }

    @Test
    fun `组合输入失败一次 重订阅同时重启两路`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val broken = AtomicBoolean(true)
        val collectionReads = AtomicInteger()
        val watchReads = AtomicInteger()
        val collections = ScriptedUserCollectionDao {
            collectionReads.incrementAndGet()
            if (broken.get()) flow { throw IOException("database unavailable") } else flowOf(emptyList())
        }
        val watches = object : FakeEpisodeWatchStateDao() {
            override fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>> {
                watchReads.incrementAndGet()
                return flowOf(emptyList())
            }
        }
        val observer = observer(tokenStore, collections, watches)
        assertEquals(Load.Failed(Failure.Storage), observer.progressSnapshot())
        val firstCollections = collectionReads.get()
        val firstWatches = watchReads.get()

        broken.set(false)
        assertIs<Load.Ready<CollectionProgressSnapshot>>(observer.progressSnapshot())
        assertTrue(collectionReads.get() > firstCollections)
        assertTrue(watchReads.get() > firstWatches)
    }

    @Test
    fun `组合输入换号后失败先交重置 不混入旧账号`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT_A)
        val subjectDao = FakeSubjectDao().apply { upsert(subject(SUBJECT_A)) }
        val collections = ScriptedUserCollectionDao { id ->
            if (id == ACCOUNT_A) flowOf(listOf(collection(id, SUBJECT_A)))
            else flow { throw IOException("new account unavailable") }
        }
        val updates = CopyOnWriteArrayList<CollectionProgressUpdate>()
        val job = launch(Dispatchers.Unconfined) {
            observer(tokenStore, collections, subjectDao = subjectDao).observeProgress().collect { updates += it }
        }
        awaitUntil("A ready") { updates.any { it is CollectionProgressUpdate.Value && it.load is Load.Ready } }

        tokenStore.completeSession(tokenStore.beginSession("token-b"), ACCOUNT_B, "beta")
        awaitUntil("B failure") { updates.any { it == CollectionProgressUpdate.Value(Load.Failed(Failure.Storage)) } }

        val reset = updates.indexOf(CollectionProgressUpdate.Reset)
        val failed = updates.indexOf(CollectionProgressUpdate.Value(Load.Failed(Failure.Storage)))
        assertTrue(reset >= 0 && reset < failed, "$updates")
        assertTrue(updates.drop(reset + 1).none {
            it is CollectionProgressUpdate.Value && (it.load as? Load.Ready)?.value?.collections?.any { row -> row.subjectId == SUBJECT_A } == true
        })
        job.cancel()
    }

    @Test
    fun `生涯统计两个观察分支同时失败交 Storage`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val userCollectionDao = ScriptedUserCollectionDao { flow { throw IOException("collections unavailable") } }
        val watchStateDao = object : FakeEpisodeWatchStateDao() {
            override fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>> =
                flow { throw IOException("watch states unavailable") }
        }
        val repo = testCollections(
            resources,
            CollectionApi(resources.apiClient { error("统计只读本地") }),
            FakeSubjectDao(),
            userCollectionDao,
            tokenStore,
            watchStateDao,
        )

        val loads = repo.statistics.observeStatistics(currentYear = 2026).take(1).toList()

        assertEquals(listOf(Load.Failed(Failure.Storage)), loads)
    }

    @Test
    fun `生涯统计一分支失败 另一分支未出首拍时立即失败`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val collections = ScriptedUserCollectionDao { flow { throw IOException("unavailable") } }
        val watches = object : FakeEpisodeWatchStateDao() {
            override fun observeAll(accountId: Int): Flow<List<EpisodeWatchStateEntity>> = flow { awaitCancellation() }
        }
        val repo = testCollections(
            resources, CollectionApi(resources.apiClient { error("统计只读本地") }),
            FakeSubjectDao(), collections, tokenStore, watches,
        )
        val result = withTimeoutOrNull(WAIT_BUDGET_MS) { repo.statistics.observeStatistics(2026).first() }
        assertEquals(Load.Failed(Failure.Storage), result)
    }

    @Test
    fun `同账号重新订阅第一拍直接是已提交收藏 不先清空`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val subjectDao = FakeSubjectDao().apply { upsert(subject(SUBJECT)) }
        val userCollectionDao = FakeUserCollectionDao().apply { upsert(collection(ACCOUNT, SUBJECT)) }
        val observer = observer(tokenStore, userCollectionDao, subjectDao = subjectDao)

        val first = observer.observeCollections().first()
        val second = observer.observeCollections().first()

        assertEquals(listOf(SUBJECT), first.subjectIds())
        assertEquals(first, second)
    }

    @Test
    fun `换号先清旧收藏 新账号读取失败也不留旧数据`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT_A)
        val subjectDao = FakeSubjectDao().apply { upsert(subject(SUBJECT_A)) }
        val userCollectionDao = ScriptedUserCollectionDao { accountId ->
            if (accountId == ACCOUNT_A) flowOf(listOf(collection(ACCOUNT_A, SUBJECT_A)))
            else flow { throw IOException("B database unavailable") }
        }
        val emissions = CopyOnWriteArrayList<Load<List<UserCollectionBo>>>()
        val job = launch(Dispatchers.Unconfined) {
            observer(tokenStore, userCollectionDao, subjectDao = subjectDao).observeCollections().collect { emissions += it }
        }
        awaitUntil("A 的收藏到达") { emissions.any { it is Load.Ready && it.value.isNotEmpty() } }

        val pending = tokenStore.beginSession("token-b")
        tokenStore.completeSession(pending, ACCOUNT_B, "beta")
        awaitUntil("B 的失败到达") { emissions.any { it == Load.Failed(Failure.Storage) } }

        val afterA = emissions.dropWhile { it !is Load.Ready || it.value.isEmpty() }.drop(1)
        val emptyAt = afterA.indexOfFirst { it is Load.Ready && it.value.isEmpty() }
        val failedAt = afterA.indexOf(Load.Failed(Failure.Storage))
        assertTrue(emptyAt >= 0, "换号没有先发空收藏：$afterA")
        assertTrue(emptyAt < failedAt, "新账号失败前必须清掉旧账号收藏：$afterA")
        assertTrue(afterA.none { it is Load.Ready && it.value.any { row -> row.subjectId == SUBJECT_A } })
        job.cancel()
    }

    @Test
    fun `取消观察流原样传播 不变成 Storage 失败`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val subjectDao = FakeSubjectDao().apply { upsert(subject(SUBJECT)) }
        val userCollectionDao = ScriptedUserCollectionDao {
            flow {
                emit(listOf(collection(ACCOUNT, SUBJECT)))
                awaitCancellation()
            }
        }
        val loads = CopyOnWriteArrayList<Load<List<UserCollectionBo>>>()
        val job = launch(Dispatchers.Unconfined) {
            observer(tokenStore, userCollectionDao, subjectDao = subjectDao).observeCollections().collect { loads += it }
        }
        awaitUntil("收藏首拍到达") { loads.isNotEmpty() }

        job.cancel()
        job.join()

        assertEquals(1, loads.size)
        loads.single().subjectIds()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `海报类型流读取失败静默保留上一拍且不抛异常`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val userCollectionDao = ScriptedUserCollectionDao {
            flow {
                emit(listOf(collection(ACCOUNT, SUBJECT)))
                throw IOException("collection types unavailable")
            }
        }
        val session = tokenStore.session()

        val emissions = observer(tokenStore, userCollectionDao).observeCollectionTypes(session).toList()

        assertEquals(listOf(mapOf(SUBJECT to CollectionType.DOING)), emissions)
    }

    @Test
    fun `正常收藏发射带条目的完整映射`() = runBlocking {
        val tokenStore = testTokenStore(resources, userId = ACCOUNT)
        val subjectDao = FakeSubjectDao().apply { upsert(subject(SUBJECT)) }
        val userCollectionDao = FakeUserCollectionDao().apply { upsert(collection(ACCOUNT, SUBJECT)) }

        val rows = assertIs<Load.Ready<List<UserCollectionBo>>>(
            observer(tokenStore, userCollectionDao, subjectDao = subjectDao).observeCollections().first(),
        ).value

        assertEquals(listOf(SUBJECT), rows.map { it.subjectId })
        assertEquals("动画$SUBJECT", rows.single().subject?.nameCn)
    }

    private companion object {
        const val ACCOUNT = 7
        const val ACCOUNT_A = 7
        const val ACCOUNT_B = 9
        const val SUBJECT = 8
        const val SUBJECT_A = 8
    }
}
