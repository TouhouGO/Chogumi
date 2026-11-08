package io.github.touhougo.chogumi.feature.monodetails

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.data.mark.StillCollectionEditing
import io.github.touhougo.chogumi.core.data.mark.StillCollectionMarker
import io.github.touhougo.chogumi.core.data.repository.FakeCommentRepository
import io.github.touhougo.chogumi.core.data.repository.MonoRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.StillUserModeration
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.testing.testAppTime
import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.FactRow
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.MonoCharacterRefBo
import io.github.touhougo.chogumi.core.model.MonoDetailsBo
import io.github.touhougo.chogumi.core.model.MonoPersonRefBo
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.PersonCastFilter
import io.github.touhougo.chogumi.core.model.SubjectType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MonoDetailsViewModelTest {

    private val main = TestMain()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModels = mutableListOf<MonoDetailsViewModel>()

    @BeforeTest
    fun setUp() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope } + scope)
        viewModels.clear()
    }

    private class FakeDetailsRepository : FakeMonoRepository() {
        var characterDetailsCallCount = 0
        var personDetailsCallCount = 0
        var addCollectionCallCount = 0
        var deleteCollectionCallCount = 0

        var characterDetailsResult: Result<MonoDetailsBo> = Result.success(
            MonoDetailsBo(
                id = 50576,
                name = "千束",
                nameCn = "锦木千束",
                careers = emptyList(),
                summary = "Lycoris 成员",
                image = "https://large.jpg",
                infobox = listOf(FactRow("生日", "9月23日")),
                collectCount = 50,
                isCollected = false,
            ),
        )

        var personDetailsResult: Result<MonoDetailsBo> = Result.success(
            MonoDetailsBo(
                id = 1234,
                name = "安济知佳",
                nameCn = "安济知佳",
                careers = listOf("seiyu"),
                summary = "声优简介",
                image = "https://person_large.jpg",
                infobox = listOf(FactRow("血型", "O型")),
                collectCount = 30,
                isCollected = true,
            ),
        )

        var characterWorksResult: Result<List<MonoWorkBo>> = Result.success(
            listOf(
                MonoWorkBo(
                    id = 364450,
                    name = "Lycoris Recoil",
                    nameCn = "莉可丽丝",
                    image = "https://subject.jpg",
                    subjectType = SubjectType.ANIME,
                    role = CastRole.MAIN,
                    actors = listOf(MonoPersonRefBo(id = 1234, name = "安济知佳", avatarUrl = null)),
                ),
            ),
        )

        var personWorksResult: Result<List<MonoWorkBo>> = Result.success(
            listOf(
                MonoWorkBo(
                    id = 364450,
                    name = "Lycoris Recoil",
                    nameCn = "莉可丽丝",
                    image = "https://subject.jpg",
                    subjectType = SubjectType.ANIME,
                    positions = listOf("配音"),
                ),
            ),
        )

        var characterRelationsResult: Result<PagedResult<MonoRelationBo>> = Result.success(
            PagedResult(
                items = listOf(
                    MonoRelationBo(
                        id = 50577,
                        name = "井之上泷奈",
                        nameCn = "井之上泷奈",
                        relation = "搭档",
                        image = "https://takina.jpg",
                    ),
                ),
                total = 1,
                nextOffset = null,
            ),
        )

        var personRelationsResult: Result<PagedResult<MonoRelationBo>> = Result.success(
            PagedResult(items = emptyList(), total = 0, nextOffset = null),
        )

        var characterIndexesResult: Result<PagedResult<IndexBo>> = Result.success(
            PagedResult(
                items = listOf(
                    IndexBo(id = 1, title = "2022年7月番角色一览", total = 10),
                ),
                total = 1,
                nextOffset = null,
            ),
        )

        var personIndexesResult: Result<PagedResult<IndexBo>> = Result.success(
            PagedResult(items = emptyList(), total = 0, nextOffset = null),
        )

        data class IndexRequest(val type: MonoType, val offset: Int)

        val indexRequests = mutableListOf<IndexRequest>()
        var indexPage: (suspend (type: MonoType, offset: Int) -> PagedResult<IndexBo>)? = null
        var indexGate: CompletableDeferred<Unit>? = null
        val gatedIndexStarted = CompletableDeferred<Unit>()

        var characterCommentsResult: Result<List<CommentThreadBo>> = Result.success(
            listOf(
                CommentThreadBo(
                    anchor = CommentItemBo(
                        id = 101,
                        mainId = 50576,
                        relatedId = 0,
                        creatorId = 1,
                        creatorNickname = "Alice",
                        creatorUsername = "alice",
                        creatorAvatar = "https://avatar.jpg",
                        content = "千束天下第一！",
                        createdAt = 1660000000L,
                        state = CommentState.NORMAL,
                    ),
                    replies = emptyList(),
                ),
            ),
        )

        var personCommentsResult: Result<List<CommentThreadBo>> = Result.success(emptyList())

        var addCollectionError: Throwable? = null
        var deleteCollectionError: Throwable? = null
        var collectionGate: CompletableDeferred<Unit>? = null

        override suspend fun getDetails(type: MonoType, id: Int): MonoDetailsBo = when (type) {
            MonoType.CHARACTER -> {
                characterDetailsCallCount++
                characterDetailsResult.getOrThrow()
            }
            MonoType.PERSON -> {
                personDetailsCallCount++
                personDetailsResult.getOrThrow()
            }
        }

        override suspend fun getAllWorks(type: MonoType, id: Int, refresh: Boolean): List<MonoWorkBo> = when (type) {
            MonoType.CHARACTER -> characterWorksResult.getOrThrow()
            MonoType.PERSON -> personWorksResult.getOrThrow()
        }

        override suspend fun getPersonCharacters(id: Int, limit: Int, offset: Int, filter: PersonCastFilter): PagedResult<MonoCharacterRefBo> =
            PagedResult(emptyList(), 0, null)

        override suspend fun getRelations(type: MonoType, id: Int, limit: Int, offset: Int): PagedResult<MonoRelationBo> = when (type) {
            MonoType.CHARACTER -> characterRelationsResult.getOrThrow()
            MonoType.PERSON -> personRelationsResult.getOrThrow()
        }

        override suspend fun getIndexes(type: MonoType, id: Int, limit: Int, offset: Int): PagedResult<IndexBo> = when (type) {
            MonoType.CHARACTER, MonoType.PERSON -> {
                indexRequests += IndexRequest(type, offset)
                indexGate?.let { gate ->
                    indexGate = null
                    gatedIndexStarted.complete(Unit)
                    gate.await()
                }
                indexPage?.invoke(type, offset) ?: when (type) {
                    MonoType.CHARACTER -> characterIndexesResult.getOrThrow()
                    MonoType.PERSON -> personIndexesResult.getOrThrow()
                }
            }
        }

        /** 非空时下一次取评论停在这里，拿到闸门后就清空（只挡一次）。 */
        var commentsGate: CompletableDeferred<Unit>? = null

        /** 被闸门挡住的那次取评论已经发出 / 被取消了（单调：一经完成不撤销）。 */
        val gatedCommentsStarted = CompletableDeferred<Unit>()
        val gatedCommentsCancelled = CompletableDeferred<Unit>()

        var commentsCallCount = 0

        override suspend fun getComments(type: MonoType, id: Int): List<CommentThreadBo> {
            commentsCallCount++
            val gate = commentsGate
            if (gate != null) {
                commentsGate = null
                val result = characterCommentsResult
                gatedCommentsStarted.complete(Unit)
                try {
                    gate.await()
                } catch (cancelled: CancellationException) {
                    gatedCommentsCancelled.complete(Unit)
                    throw cancelled
                }
                return result.getOrThrow()
            }
            return when (type) {
                MonoType.CHARACTER -> characterCommentsResult.getOrThrow()
                MonoType.PERSON -> personCommentsResult.getOrThrow()
            }
        }

        override suspend fun setCollected(type: MonoType, id: Int, collected: Boolean): Outcome<Unit> = outcomeOf {
            if (collected) addCollectionCallCount++ else deleteCollectionCallCount++
            collectionGate?.await()
            (if (collected) addCollectionError else deleteCollectionError)?.let { throw it }
        }
    }

    private fun createViewModel(
        monoType: MonoType,
        id: Int,
        repository: MonoRepository,
        startComments: Boolean = true,
    ): MonoDetailsViewModel {
        return MonoDetailsViewModel(
            monoType = monoType,
            id = id,
            monoRepository = repository,
            comments = FakeCommentRepository(scope),
            collectionMarker = StillCollectionMarker(),
            collectionEditing = StillCollectionEditing(),
            appTime = testAppTime(System.currentTimeMillis()),
            userModeration = StillUserModeration(),
            blockedUsers = NoBlockedUsers(),
        ).also {
            // 吐槽区出现在屏幕上才取；多数用例要的是它已经取过的状态
            if (startComments) it.startComments()
            viewModels.add(it)
        }
    }

    /** 收藏写入失败后发出的提示文本（无提示时为 null）。 */
    private fun MonoDetailsViewModel.failureReason(): Failure? =
        (failures.current.value?.text as? NoticeText.Reason)?.failure

    /** 首轮加载全部落定：各区块初值都是「等待」、取过一次就不再回到等待，所以这是单调的信号。 */
    private fun MonoDetailsUiState.allSettled() =
        details !is Load.Pending &&
            !indexes.isLoading &&
            listOf(works, characters, relations, comments.floors).none { it is Load.Pending }

    private fun commentThread(id: Int, content: String) = CommentThreadBo(
        anchor = CommentItemBo(
            id = id,
            mainId = 50576,
            relatedId = 0,
            creatorId = 1,
            creatorNickname = "Alice",
            creatorUsername = "alice",
            creatorAvatar = null,
            content = content,
            createdAt = 1660000000L,
            state = CommentState.NORMAL,
        ),
    )

    private fun index(id: Int) = IndexBo(id = id, title = "目录 $id", total = 0, createdAt = id.toLong())

    /** 已就绪区块的值；没就绪直接让测试失败。 */
    private val <T> Load<T>.ready: T get() = (this as Load.Ready).value

    @Test
    fun `角色初始加载并发拉取核心数据与作品并成功进入Loaded状态`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository()
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }

            val state = vm.uiState.value
            assertFalse(state.details is Load.Pending)
            assertNull(state.details.failureOrNull())
            assertEquals("千束", state.name)
            assertEquals("锦木千束", state.nameCn)
            assertEquals(50, state.collectCount)
            assertFalse(state.isCollected)
            assertEquals(CastRole.MAIN, state.billingRole)
            assertEquals("安济知佳", state.voiceActor?.name)
            assertEquals(1, state.works.ready.size)
            assertEquals("Lycoris Recoil", state.works.ready.first().name)
            assertEquals(1, state.relations.ready.size)
            assertEquals("井之上泷奈", state.relations.ready.first().name)
            assertEquals(1, state.indexes.items.size)
            assertEquals(1, state.indexes.total)
            assertEquals(1, state.comments.floors.ready.threads.size)
            assertEquals("千束天下第一！", state.comments.floors.ready.threads.first().anchor.content)
            assertEquals(1, repo.characterDetailsCallCount)
        }
    }

    @Test
    fun `关联目录短首屏保留服务端总数并从 offset 30 续取`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository().apply {
                indexPage = { _, offset ->
                    if (offset == 0) {
                        PagedResult(List(17) { IndexBo(id = it + 1, title = "目录 $it", total = 0) }, total = 31, nextOffset = 30)
                    } else {
                        PagedResult(listOf(IndexBo(id = 31, title = "目录 30", total = 0)), total = 31, nextOffset = null)
                    }
                }
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }

            val first = vm.uiState.value.indexes
            assertEquals(17, first.items.size)
            assertEquals(31, first.total)
            assertEquals(30, first.nextOffset)

            vm.loadMoreIndexes()
            awaitUntil("第二页落定") { vm.uiState.value.indexes.items.size == 18 }

            val second = vm.uiState.value.indexes
            assertEquals(31, second.total)
            assertEquals(18, second.items.size)
            assertNull(second.nextOffset)
            assertEquals(listOf(0, 30), repo.indexRequests.map { it.offset })
        }
    }

    @Test
    fun `关联目录空首屏仍有总数并可从 offset 30 续取`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository().apply {
                indexPage = { _, offset ->
                    if (offset == 0) {
                        PagedResult(emptyList(), total = 31, nextOffset = 30)
                    } else {
                        PagedResult(listOf(index(31)), total = 31, nextOffset = null)
                    }
                }
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("空首屏落定") { vm.uiState.value.allSettled() }

            val empty = vm.uiState.value.indexes
            assertTrue(empty.items.isEmpty())
            assertEquals(31, empty.total)
            assertEquals(30, empty.nextOffset)
            assertTrue(empty.hasMore)

            vm.loadMoreIndexes()
            awaitUntil("空首屏后的第一行到达") { vm.uiState.value.indexes.items.size == 1 }

            assertEquals(31, vm.uiState.value.indexes.total)
            assertEquals(listOf(0, 30), repo.indexRequests.map { it.offset })
        }
    }

    @Test
    fun `关联目录total为0不请求下一页`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository().apply {
                indexPage = { _, _ -> PagedResult(emptyList(), total = 0, nextOffset = null) }
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("空目录落定") { vm.uiState.value.allSettled() }
            vm.loadMoreIndexes()

            val state = vm.uiState.value.indexes
            assertEquals(0, state.total)
            assertFalse(state.hasMore)
            assertEquals(listOf(0), repo.indexRequests.map { it.offset })
        }
    }

    @Test
    fun `关联目录满一页且无next时不再请求`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository().apply {
                indexPage = { _, _ -> PagedResult(List(30) { index(it + 1) }, total = 30, nextOffset = null) }
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("满一页落定") { vm.uiState.value.allSettled() }
            vm.loadMoreIndexes()

            assertEquals(30, vm.uiState.value.indexes.items.size)
            assertFalse(vm.uiState.value.indexes.hasMore)
            assertEquals(listOf(0), repo.indexRequests.map { it.offset })
        }
    }

    @Test
    fun `关联目录续取失败保留已有条目并重试同一页`() {
        runBlocking(main.dispatcher) {
            var failSecondPage = true
            val repo = FakeDetailsRepository().apply {
                indexPage = { _, offset ->
                    when {
                        offset == 0 -> PagedResult(List(30) { index(it + 1) }, total = 31, nextOffset = 30)
                        failSecondPage -> {
                            failSecondPage = false
                            throw FailureException(Failure.Offline)
                        }
                        else -> PagedResult(listOf(index(31)), total = 31, nextOffset = null)
                    }
                }
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("首屏落定") { vm.uiState.value.allSettled() }
            vm.loadMoreIndexes()
            awaitUntil("续取失败落定") { vm.uiState.value.indexes.loadMoreError != null }

            val failed = vm.uiState.value.indexes
            assertEquals(30, failed.items.size)
            assertEquals(31, failed.total)
            assertEquals(30, failed.nextOffset)
            assertEquals(Failure.Offline, failed.loadMoreError)

            vm.loadMoreIndexes()
            awaitUntil("重试续取成功") { vm.uiState.value.indexes.items.size == 31 }

            assertNull(vm.uiState.value.indexes.loadMoreError)
            assertEquals(listOf(0, 30, 30), repo.indexRequests.map { it.offset })
        }
    }

    @Test
    fun `关联目录首屏失败可重试`() {
        runBlocking(main.dispatcher) {
            var fail = true
            val repo = FakeDetailsRepository().apply {
                indexPage = { _, _ ->
                    if (fail) throw FailureException(Failure.Http(500))
                    PagedResult(listOf(index(1)), total = 1, nextOffset = null)
                }
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("首屏失败落定") { vm.uiState.value.allSettled() }
            assertEquals(Failure.Http(500), vm.uiState.value.indexes.error)
            assertEquals(0, vm.uiState.value.indexes.total)

            fail = false
            vm.retryIndexes()
            awaitUntil("重试成功") { vm.uiState.value.indexes.total == 1 }

            assertEquals(1, vm.uiState.value.indexes.items.size)
            assertEquals(listOf(0, 0), repo.indexRequests.map { it.offset })
        }
    }

    @Test
    fun `关联目录刷新在途保留旧页 成功后替换`() {
        runBlocking(main.dispatcher) {
            var firstPage = List(3) { index(it + 1) }
            val repo = FakeDetailsRepository().apply {
                indexPage = { _, offset ->
                    if (offset == 0) PagedResult(firstPage, total = firstPage.size, nextOffset = null)
                    else error("刷新不应请求后续页")
                }
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)
            awaitUntil("首屏落定") { vm.uiState.value.allSettled() }
            val shown = vm.uiState.value.indexes

            firstPage = List(2) { index(it + 10) }
            val gate = CompletableDeferred<Unit>()
            repo.indexGate = gate
            vm.refresh()
            withTimeout(10.seconds) { repo.gatedIndexStarted.await() }

            assertEquals(shown, vm.uiState.value.indexes)

            gate.complete(Unit)
            awaitUntil("刷新替换首屏") { vm.uiState.value.indexes.items.map { it.id } == listOf(10, 11) }
            assertEquals(2, vm.uiState.value.indexes.total)
        }
    }

    @Test
    fun `关联目录刷新失败保留旧页并发出可重试提示`() {
        runBlocking(main.dispatcher) {
            var fail = false
            val repo = FakeDetailsRepository().apply {
                indexPage = { _, _ ->
                    if (fail) throw FailureException(Failure.Offline)
                    PagedResult(List(3) { index(it + 1) }, total = 31, nextOffset = 30)
                }
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)
            awaitUntil("首屏落定") { vm.uiState.value.allSettled() }
            val shown = vm.uiState.value.indexes

            fail = true
            vm.refresh()
            awaitUntil("刷新失败提示") { vm.failures.current.value != null }

            assertEquals(shown, vm.uiState.value.indexes)
            assertEquals(Failure.Offline, vm.failureReason())
            assertNotNull(vm.failures.current.value?.retry)
        }
    }

    @Test
    fun `角色与人物共用同一条目录分页路径`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository().apply {
                indexPage = { type, _ ->
                    when (type) {
                        MonoType.CHARACTER -> PagedResult(listOf(index(1)), total = 1, nextOffset = null)
                        MonoType.PERSON -> PagedResult(listOf(index(2), index(3)), total = 2, nextOffset = null)
                    }
                }
            }
            val character = createViewModel(MonoType.CHARACTER, 50576, repo)
            val person = createViewModel(MonoType.PERSON, 1234, repo)

            awaitUntil("两个页面落定") {
                character.uiState.value.allSettled() && person.uiState.value.allSettled()
            }

            assertEquals(1, character.uiState.value.indexes.total)
            assertEquals(2, person.uiState.value.indexes.total)
            assertEquals(
                setOf(
                    FakeDetailsRepository.IndexRequest(MonoType.CHARACTER, 0),
                    FakeDetailsRepository.IndexRequest(MonoType.PERSON, 0),
                ),
                repo.indexRequests.toSet(),
            )
        }
    }

    @Test
    fun `人物初始加载并发拉取核心数据与作品并成功进入Loaded状态`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository()
            val vm = createViewModel(MonoType.PERSON, 1234, repo)

            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }

            val state = vm.uiState.value
            assertFalse(state.details is Load.Pending)
            assertNull(state.details.failureOrNull())
            assertEquals("安济知佳", state.name)
            assertEquals(30, state.collectCount)
            assertTrue(state.isCollected)
            assertEquals(listOf("seiyu"), state.careers)
            assertNull(state.billingRole, "人物作品不带定位")
            assertNull(state.voiceActor, "人物作品不带声优")
            assertEquals(1, state.works.ready.size)
            assertEquals("Lycoris Recoil", state.works.ready.first().name)
            assertEquals(1, repo.personDetailsCallCount)
        }
    }

    @Test
    fun `局部容错：评论加载失败不破坏已加载的头部与作品数据`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository().apply {
                characterCommentsResult = Result.failure(FailureException(Failure.Http(504)))
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }

            val state = vm.uiState.value
            assertFalse(state.details is Load.Pending, "主状态依然标记为已加载完成")
            assertNull(state.details.failureOrNull(), "全局错误应为 null，不导致整页空白")
            assertEquals("千束", state.name, "头部数据应成功保留")
            assertEquals(1, state.works.ready.size, "作品列表应成功保留")
            assertEquals(Load.Failed(Failure.Http(504)), state.comments.floors, "评论记为局部失败，原因可追溯")

            // 测试局部重试
            repo.characterCommentsResult = Result.success(
                listOf(
                    CommentThreadBo(
                        anchor = CommentItemBo(
                            id = 101,
                            mainId = 50576,
                            relatedId = 0,
                            creatorId = 1,
                            creatorNickname = "Alice",
                            creatorUsername = "alice",
                            creatorAvatar = null,
                            content = "重试成功后的评论",
                            createdAt = 1660000000L,
                            state = CommentState.NORMAL,
                        ),
                    ),
                ),
            )
            vm.retryComments()
            awaitUntil("评论加载") { vm.uiState.value.comments.floors is Load.Ready }
            assertEquals(1, vm.uiState.value.comments.floors.ready.threads.size, "重试成功后失败被就绪替换")
        }
    }

    @Test
    fun `局部容错：关联加载失败不破坏头部与作品数据`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository().apply {
                characterRelationsResult = Result.failure(RuntimeException("关联数据拉取失败"))
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }

            val state = vm.uiState.value
            assertFalse(state.details is Load.Pending)
            assertNull(state.details.failureOrNull())
            assertEquals("千束", state.name)
            assertEquals(1, state.works.ready.size)
            assertTrue(state.relations is Load.Failed)
        }
    }

    /** 有内容时下拉刷新、详情取失败：内容留着，不写整页错误，改发一条带重试的提示。 */
    @Test
    fun `有内容时刷新详情失败 不写整页错误 发一条带重试的提示`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository()
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)
            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }

            repo.characterDetailsResult = Result.failure(FailureException(Failure.Http(503)))
            vm.refresh()
            awaitUntil("刷新失败的提示") { vm.failures.current.value != null }

            val state = vm.uiState.value
            assertNull(state.details.failureOrNull(), "有内容时整页错误位不写")
            assertEquals("千束", state.name, "已显示的详情留着")
            assertEquals(Failure.Http(503), vm.failureReason())
            assertNotNull(vm.failures.current.value?.retry, "提示带重试")
        }
    }

    /** 有内容时下拉刷新、某个区块失败：区块的内容留着，失败另发提示（区块自己不再变成错误）。 */
    @Test
    fun `有内容时刷新区块失败 区块内容留着 失败另发提示`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository()
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)
            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }
            val shownWorks = vm.uiState.value.works

            repo.characterWorksResult = Result.failure(FailureException(Failure.Http(502)))
            vm.refresh()
            awaitUntil("刷新失败的提示") { vm.failures.current.value != null }

            assertEquals(shownWorks, vm.uiState.value.works, "作品区块的内容留着")
            assertEquals(Failure.Http(502), vm.failureReason())
        }
    }

    /** 连点两次重试：先发的一轮被作废，慢的旧结果不会晚到盖掉新的。 */
    @Test
    fun `连点重试时先发的一轮被取消 旧结果不盖掉新的`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository()
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)
            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }

            val gate = CompletableDeferred<Unit>()
            repo.commentsGate = gate
            repo.characterCommentsResult = Result.success(listOf(commentThread(201, "旧一轮")))
            vm.retryComments()
            withTimeout(10.seconds) { repo.gatedCommentsStarted.await() }
            repo.characterCommentsResult = Result.success(listOf(commentThread(202, "新一轮")))
            vm.retryComments()
            awaitUntil("新一轮落定") { vm.uiState.value.comments.floors.ready.threads.firstOrNull()?.anchor?.id == 202 }

            gate.complete(Unit)
            withTimeout(10.seconds) { repo.gatedCommentsCancelled.await() }
            assertEquals("新一轮", vm.uiState.value.comments.floors.ready.threads.single().anchor.content)
        }
    }

    @Test
    fun `乐观收藏写路径：点击收藏立即翻转状态，网络成功时不重新拉取整体详情`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository()
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }
            assertEquals(false, vm.uiState.value.isCollected)
            assertEquals(50, vm.uiState.value.collectCount)
            assertEquals(1, repo.characterDetailsCallCount)

            // 触发收藏
            vm.toggleCollection()

            // 1. 立即乐观翻转
            assertEquals(true, vm.uiState.value.isCollected)
            assertEquals(51, vm.uiState.value.collectCount)

            // 等收藏写入落定（isCollectionLoading 在点击时同步置真，之后只会变回假一次）
            awaitUntil("收藏写入落定") { !vm.uiState.value.isCollectionLoading }
            assertEquals(1, repo.addCollectionCallCount)

            // 2. 验证网络成功后保持翻转，且严禁全量刷新详情
            assertEquals(true, vm.uiState.value.isCollected)
            assertEquals(51, vm.uiState.value.collectCount)
            assertEquals(1, repo.characterDetailsCallCount, "网络成功后严禁全量重新拉取详情，防止闪烁与滚动重置")
            assertNull(vm.failures.current.value)
        }
    }

    @Test
    fun `收藏写入在途时再点不发第二个请求`() {
        runBlocking(main.dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repo = FakeDetailsRepository().apply { collectionGate = gate }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)
            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }

            vm.toggleCollection()
            vm.toggleCollection()

            assertEquals(true, vm.uiState.value.isCollected, "第二次点击不得把乐观值翻回去")
            gate.complete(Unit)
            awaitUntil("收藏写入落定") { !vm.uiState.value.isCollectionLoading }
            assertEquals(1, repo.addCollectionCallCount)
        }
    }

    @Test
    fun `乐观收藏写路径：网络失败时状态回滚并发出错误事件`() {
        runBlocking(main.dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repo = FakeDetailsRepository().apply {
                addCollectionError = FailureException(Failure.Offline)
                collectionGate = gate
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }
            assertEquals(false, vm.uiState.value.isCollected)
            assertEquals(50, vm.uiState.value.collectCount)

            // 触发收藏
            vm.toggleCollection()

            // 1. 立即乐观翻转（网络尚未返回前）
            assertEquals(true, vm.uiState.value.isCollected)
            assertEquals(51, vm.uiState.value.collectCount)

            // 2. 释放门闩让网络抛出异常，等待失败并断言状态回滚
            gate.complete(Unit)
            awaitUntil("失败提示发出") { vm.failures.current.value != null }

            assertEquals(false, vm.uiState.value.isCollected, "失败后必须回滚 isCollected")
            assertEquals(50, vm.uiState.value.collectCount, "失败后必须回滚 collectCount")
            assertEquals(Failure.Offline, vm.failureReason())
            assertNull(vm.uiState.value.details.failureOrNull(), "收藏失败不是整页失败")
            assertEquals(1, repo.characterDetailsCallCount, "回滚时也不应全量刷新详情")
        }
    }

    @Test
    fun `乐观取消收藏写路径：已收藏时点击取消收藏，成功则减一，失败回滚`() {
        runBlocking(main.dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val repo = FakeDetailsRepository().apply {
                deleteCollectionError = RuntimeException("取消收藏服务端错误")
                collectionGate = gate
            }
            // 人物初始状态为已收藏 (collectCount = 30, isCollected = true)
            val vm = createViewModel(MonoType.PERSON, 1234, repo)

            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }
            assertTrue(vm.uiState.value.isCollected)
            assertEquals(30, vm.uiState.value.collectCount)

            vm.toggleCollection()

            // 立即乐观翻转（网络尚未返回前）
            assertFalse(vm.uiState.value.isCollected)
            assertEquals(29, vm.uiState.value.collectCount)

            // 释放门闩让网络抛出异常，等待失败回滚
            gate.complete(Unit)
            awaitUntil("失败提示发出") { vm.failures.current.value != null }

            assertTrue(vm.uiState.value.isCollected, "失败后回滚为已收藏")
            assertEquals(30, vm.uiState.value.collectCount, "失败后回滚计数")
        }
    }

    @Test
    fun `关联关系合并：同一实体多种关系合并为单张卡片且文案以斜杠连接`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository().apply {
                characterRelationsResult = Result.success(
                    PagedResult(
                        items = listOf(
                            MonoRelationBo(
                                id = 1,
                                name = "Anya Forger",
                                nameCn = "阿尼亚·福杰",
                                relation = "同事",
                                image = "https://anya.jpg",
                            ),
                            MonoRelationBo(
                                id = 1,
                                name = "Anya Forger",
                                nameCn = "阿尼亚·福杰",
                                relation = "朋友",
                                image = "https://anya.jpg",
                            ),
                            MonoRelationBo(
                                id = 2,
                                name = "Yor Forger",
                                nameCn = "约尔·福杰",
                                relation = "亲属",
                                image = "https://yor.jpg",
                            ),
                        ),
                        total = 3,
                        nextOffset = null,
                    ),
                )
            }
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo)

            awaitUntil("首轮加载全部落定") { vm.uiState.value.allSettled() }

            val relations = vm.uiState.value.relations.ready
            assertEquals(2, relations.size)
            assertEquals(1, relations[0].id)
            assertEquals("同事 / 朋友", relations[0].relation)
            assertEquals("Anya Forger", relations[0].name)
            assertEquals("阿尼亚·福杰", relations[0].nameCn)
            assertEquals("https://anya.jpg", relations[0].image)

            assertEquals(2, relations[1].id)
            assertEquals("亲属", relations[1].relation)
            assertEquals("Yor Forger", relations[1].name)
            assertEquals("约尔·福杰", relations[1].nameCn)
            assertEquals("https://yor.jpg", relations[1].image)
        }
    }

    @Test
    fun `代表角色的「全部」比较总数与真实露出条数，没就绪时不给`() {
        val character = MonoCharacterRefBo(1, "角色", "", null, "作品")
        fun page(shown: Int, total: Int) =
            MonoDetailsUiState(characters = Load.Ready(PagedResult(List(shown) { character.copy(id = it) }, total, null)))
        assertFalse(page(shown = 10, total = 10).hasMoreCharacters)
        assertFalse(page(shown = 10, total = 9).hasMoreCharacters)
        assertTrue(page(shown = 10, total = 11).hasMoreCharacters)
        assertTrue(page(shown = 8, total = 10).hasMoreCharacters, "预览被过滤掉一部分")
        assertTrue(page(shown = 0, total = 31).hasMoreCharacters, "预览全被过滤仍留入口")
        assertFalse(page(shown = 0, total = 0).hasMoreCharacters)
        assertFalse(MonoDetailsUiState(characters = Load.Pending).hasMoreCharacters)
    }

    @Test
    fun `mergeByEntity 纯函数：输入同一实体多条关系合并为单条并保留首次顺序`() {
        val input = listOf(
            MonoRelationBo(id = 1, name = "A", nameCn = "甲", relation = "同事", image = "img1"),
            MonoRelationBo(id = 1, name = "A", nameCn = "甲", relation = "朋友", image = "img1"),
            MonoRelationBo(id = 2, name = "B", nameCn = "乙", relation = "亲属", image = "img2"),
        )
        val result = input.mergeByEntity()
        assertEquals(2, result.size)
        assertEquals("同事 / 朋友", result[0].relation)
        assertEquals("亲属", result[1].relation)
    }

    @Test
    fun `吐槽区没出现前不取吐槽_出现后才取一次`() {
        runBlocking(main.dispatcher) {
            val repo = FakeDetailsRepository()
            val vm = createViewModel(MonoType.CHARACTER, 50576, repo, startComments = false)

            awaitUntil("除吐槽外的区块落定") {
                val state = vm.uiState.value
                state.details !is Load.Pending &&
                    !state.indexes.isLoading &&
                    listOf(state.works, state.characters, state.relations).none { it is Load.Pending }
            }
            assertEquals(0, repo.commentsCallCount, "专注模式下吐槽区不渲染，也就不为它发请求")
            assertEquals(Load.Pending, vm.uiState.value.comments.floors)

            vm.startComments()
            awaitUntil("吐槽取到") { vm.uiState.value.comments.floors !is Load.Pending }
            assertEquals(1, repo.commentsCallCount)

            // 再进入一次：重取会在 ViewModel 里起一轮取数，等它（若有）跑完再数
            val viewModelJob = vm.viewModelScope.coroutineContext[Job]!!
            val before = viewModelJob.children.toSet()
            vm.startComments()
            awaitUntil("再进入引起的协程都结束") { viewModelJob.children.none { it !in before } }
            assertEquals(1, repo.commentsCallCount, "重复进入不重复取")
        }
    }
}
