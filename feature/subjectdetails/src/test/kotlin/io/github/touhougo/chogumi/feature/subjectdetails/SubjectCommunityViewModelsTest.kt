package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.FakeCommentRepository
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.StillUserModeration
import io.github.touhougo.chogumi.core.data.repository.SubjectCommunityRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.EpisodeThreadBo
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.PagedResult
import io.github.touhougo.chogumi.core.model.SlimBlogBo
import io.github.touhougo.chogumi.core.model.SubjectCollectorBo
import io.github.touhougo.chogumi.core.model.SubjectTopicBo
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.reactionOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SubjectCommunityViewModelsTest {

    private val main = TestMain()
    private val viewModels = mutableListOf<StateViewModel<*>>()
    private val commentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest
    fun setUp() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope } + commentScope)
    }

    private suspend fun <T : StateViewModel<*>> onMain(create: () -> T): T =
        withContext(main.dispatcher) { create().also { viewModels += it } }

    private fun post(id: Int, creatorId: Int, content: String = "c$id") = CommentItemBo(
        id = id, mainId = 1, relatedId = 0, creatorId = creatorId, creatorNickname = "u$creatorId",
        creatorUsername = "u$creatorId", creatorAvatar = null, content = content, createdAt = id.toLong(),
        state = CommentState.NORMAL,
    )

    private fun user(id: Int) = UserBo(id = id, username = "u$id", nickname = "n$id")

    /** 每个接口一个可替换的实现 + 调用计数。 */
    private class FakeCommunity : SubjectCommunityRepository {
        val calls = AtomicInteger()
        var comments: (CollectionType?, Int) -> PagedResult<CommentThreadBo> = { _, _ -> PagedResult(emptyList(), 0, null) }
        var topicsTotal: () -> Int = { 0 }
        var reviewsTotal: () -> Int = { 0 }
        var indexesTotal: () -> Int = { 0 }
        var collectors: (CollectionType?, Int) -> PagedResult<SubjectCollectorBo> = { _, _ -> PagedResult(emptyList(), 0, null) }
        var react: suspend (Int, Int?) -> Unit = { _, _ -> }
        val reactCalls = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Int?>>()

        /** 先计数再取值：抛错的请求也算发出过。 */
        private fun <T> counted(block: () -> T): T {
            calls.incrementAndGet()
            return block()
        }

        override suspend fun getComments(subjectId: Int, type: CollectionType?, limit: Int, offset: Int) =
            counted { comments(type, offset) }
        override suspend fun getTopics(subjectId: Int, limit: Int, offset: Int) =
            counted { PagedResult(emptyList<SubjectTopicBo>(), topicsTotal(), null) }
        override suspend fun getReviews(subjectId: Int, limit: Int, offset: Int) =
            counted { PagedResult(emptyList<SlimBlogBo>(), reviewsTotal(), null) }
        override suspend fun getIndexes(subjectId: Int, limit: Int, offset: Int) =
            counted { PagedResult(emptyList<IndexBo>(), indexesTotal(), null) }
        override suspend fun getCollectors(subjectId: Int, type: CollectionType?, limit: Int, offset: Int) =
            counted { collectors(type, limit) }
        override suspend fun setCommentReaction(commentId: Int, value: Int?) = outcomeOf<Unit> {
            reactCalls += commentId to value
            react(commentId, value)
        }

        override suspend fun getEpisode(episodeId: Int): EpisodeThreadBo = error("单集讨论页不在这条测试里")

        var episodeComments: () -> List<CommentThreadBo> = { emptyList() }

        override suspend fun getEpisodeComments(episodeId: Int): List<CommentThreadBo> = counted { episodeComments() }

        override suspend fun setEpisodeCommentReaction(commentId: Int, value: Int?) = Outcome.Success(Unit)
    }

    @Test
    fun `社区 tab：start 之前不发请求，start 幂等，数量与头像齐，单项失败只让那一项为空`() = runBlocking {
        val repo = FakeCommunity().apply {
            comments = { _, _ -> PagedResult(listOf(CommentThreadBo(post(1, 9))), total = 1448, nextOffset = 20) }
            reviewsTotal = { 80 }
            topicsTotal = { error("boom") }
            indexesTotal = { 1274 }
            collectors = { type, limit ->
                assertNull(type)
                assertEquals(RECENT_COLLECTORS, limit)
                PagedResult(listOf(SubjectCollectorBo(user(1), CollectionType.DOING, 0, 1L)), total = 17685, nextOffset = 5)
            }
        }
        val vm = onMain { SubjectCommunityViewModel(253, repo, StillUserModeration(), NoBlockedUsers()) }
        assertEquals(0, repo.calls.get(), "没选中社区 tab 就不发请求")

        withContext(main.dispatcher) { vm.start() }
        awaitUntil("吐槽第一页与入口数量都落定") { !vm.uiState.value.comments.isLoading && vm.uiState.value.overview is Load.Ready }
        assertEquals(5, repo.calls.get(), "吐槽 1 + 数量 3 + 头像 1")

        // 第一轮落定后再选中一次：假仓库同步返回，再取的一轮整轮不离开 Main，排空 Main 之后就数得到它的请求
        withContext(main.dispatcher) { vm.start() }
        withContext(main.dispatcher) { }
        assertEquals(5, repo.calls.get(), "第二次 start 不再发请求")
        val overview = vm.uiState.value.overview.valueOrNull()!!
        assertEquals(80, overview.reviews)
        assertNull(overview.topics, "讨论版数量失败：只有这一项为空")
        assertEquals(1274, overview.indexes)
        assertEquals(17685, overview.collectorsTotal)
        assertEquals(listOf(1), overview.recentCollectors.map { it.user.id })
        assertEquals(1448, vm.uiState.value.comments.total)
        assertEquals(1, vm.uiState.value.comments.items.size)
    }

    @Test
    fun `吐槽里绝交一个人，这个人的吐槽当场不显示且不重取，游标与总数不变`() = runBlocking {
        val commentRequests = AtomicInteger()
        val repo = FakeCommunity().apply {
            comments = { _, _ ->
                commentRequests.incrementAndGet()
                PagedResult(listOf(CommentThreadBo(post(1, 9)), CommentThreadBo(post(2, 8))), total = 40, nextOffset = 20)
            }
        }
        val blocked = NoBlockedUsers()
        val vm = onMain { SubjectCommunityViewModel(253, repo, StillUserModeration(), blocked) }
        withContext(main.dispatcher) { vm.start() }
        awaitUntil("吐槽落定") { vm.uiState.value.comments.items.isNotEmpty() }
        assertEquals(listOf(1, 2), vm.uiState.value.shownComments.items.map { it.anchor.id })

        blocked.list.value = BlockedUsers(setOf(9))
        awaitUntil("绝交后吐槽跟上") { vm.uiState.value.blocked?.hides(9) == true }
        val shown = vm.uiState.value.shownComments
        assertEquals(listOf(2), shown.items.map { it.anchor.id })
        assertEquals(40 to 20, shown.total to shown.nextOffset)
        assertEquals(1, commentRequests.get(), "名单变了只重排已到手的吐槽")
    }

    @Test
    fun `名单还没读到时吐槽先到_按首屏加载画_名单到了按它一次画出`() = runBlocking {
        val repo = FakeCommunity().apply {
            comments = { _, _ -> PagedResult(listOf(CommentThreadBo(post(1, 9)), CommentThreadBo(post(2, 8))), total = 40, nextOffset = 20) }
        }
        val blocked = NoBlockedUsers(initial = null)
        val vm = onMain { SubjectCommunityViewModel(253, repo, StillUserModeration(), blocked) }
        withContext(main.dispatcher) { vm.start() }
        awaitUntil("吐槽先到") { vm.uiState.value.comments.items.isNotEmpty() }
        val unread = vm.uiState.value.shownComments
        assertEquals(emptyList(), unread.items.map { it.anchor.id }, "名单没到：一条也不给")
        assertTrue(unread.isLoading, "按首屏加载画占位")

        blocked.list.value = BlockedUsers(setOf(9))
        awaitUntil("名单到了") { vm.uiState.value.blocked != null }
        val shown = vm.uiState.value.shownComments
        assertEquals(listOf(2), shown.items.map { it.anchor.id })
        assertEquals(40 to 20, shown.total to shown.nextOffset)
    }

    @Test
    fun `名单还没读到时单集评论的楼层先到_仍按等待画_名单到了按它一次画出`() = runBlocking {
        val repo = FakeCommunity().apply { episodeComments = { listOf(CommentThreadBo(post(1, 9)), CommentThreadBo(post(2, 8))) } }
        val blocked = NoBlockedUsers(initial = null)
        val vm = onMain { EpisodeCommentsViewModel(5, repo, FakeCommentRepository(commentScope), StillUserModeration(), blocked) }
        awaitUntil("楼层先到") { vm.uiState.value.comments.floors is Load.Ready }
        assertEquals(emptyList(), vm.uiState.value.comments.reading.replies.map { it.thread.anchor.id }, "名单没到：一层楼也不给")
        assertEquals(Load.Pending, vm.uiState.value.comments.shown, "评论区按它画：仍是占位")

        blocked.list.value = BlockedUsers(setOf(9))
        awaitUntil("名单到了") { vm.uiState.value.comments.blocked != null }
        assertEquals(listOf(2), vm.uiState.value.comments.reading.replies.map { it.thread.anchor.id })
    }

    @Test
    fun `单集评论里绝交一个人，这个人的楼当场不显示且不重取`() = runBlocking {
        val repo = FakeCommunity().apply { episodeComments = { listOf(CommentThreadBo(post(1, 9)), CommentThreadBo(post(2, 8))) } }
        val blocked = NoBlockedUsers()
        val vm = onMain { EpisodeCommentsViewModel(5, repo, FakeCommentRepository(commentScope), StillUserModeration(), blocked) }
        awaitUntil("楼层落定") { vm.uiState.value.comments.floors is Load.Ready }
        fun shown() = vm.uiState.value.comments.reading.replies.map { it.thread.anchor.id }
        assertEquals(listOf(1, 2), shown())

        blocked.list.value = BlockedUsers(setOf(9))
        awaitUntil("绝交后楼层跟上") { vm.uiState.value.comments.blocked?.hides(9) == true }
        assertEquals(listOf(2), shown())
        assertEquals(1, repo.calls.get())
    }

    @Test
    fun `谁在看面板：切状态按新状态重载，同一状态不重载`() = runBlocking {
        val requested = mutableListOf<CollectionType?>()
        val repo = FakeCommunity().apply {
            collectors = { type, _ ->
                synchronized(requested) { requested += type }
                PagedResult(listOf(SubjectCollectorBo(user(type?.value ?: 0), type ?: CollectionType.DONE, 0, 1L)), 1, null)
            }
        }
        val vm = onMain { SubjectCollectorsViewModel(253, repo) }
        awaitUntil("「最近」第一页落定") { !vm.uiState.value.collectors.isLoading }

        withContext(main.dispatcher) { vm.selectType(CollectionType.WISH) }
        awaitUntil("「想看」第一页落定") { vm.uiState.value.collectors.items.singleOrNull()?.user?.id == CollectionType.WISH.value }
        withContext(main.dispatcher) { vm.selectType(CollectionType.WISH) }

        assertEquals(CollectionType.WISH, vm.uiState.value.type)
        assertEquals(listOf(null, CollectionType.WISH), synchronized(requested) { requested.toList() })
    }

    @Test
    fun `吐槽筛选：按收藏状态从第一页重拉，同一状态不重拉`() = runBlocking {
        val requested = mutableListOf<CollectionType?>()
        val repo = FakeCommunity().apply {
            comments = { type, _ ->
                synchronized(requested) { requested += type }
                PagedResult(listOf(CommentThreadBo(post(type?.value ?: 0, 9))), total = 1, nextOffset = null)
            }
        }
        val vm = onMain { SubjectCommunityViewModel(253, repo, StillUserModeration(), NoBlockedUsers()) }
        withContext(main.dispatcher) { vm.start() }
        awaitUntil("全部吐槽到位") { vm.uiState.value.comments.items.isNotEmpty() }
        withContext(main.dispatcher) { vm.selectCommentFilter(CollectionType.DONE) }
        awaitUntil("看过的吐槽到位") { vm.uiState.value.comments.items.singleOrNull()?.anchor?.id == CollectionType.DONE.value }
        withContext(main.dispatcher) { vm.selectCommentFilter(CollectionType.DONE) }
        assertEquals(CollectionType.DONE, vm.uiState.value.commentFilter)
        assertEquals(listOf(null, CollectionType.DONE), synchronized(requested) { requested.toList() })
    }

    @Test
    fun `详情页下拉刷新：社区 tab 没打开过不发请求，打开过重取吐槽，失败时吐槽留着并提示`() = runBlocking {
        var commentsFail = false
        val repo = FakeCommunity().apply {
            comments = { _, _ ->
                if (commentsFail) error("offline")
                PagedResult(listOf(CommentThreadBo(post(1, 9))), total = 1, nextOffset = null)
            }
        }
        val vm = onMain { SubjectCommunityViewModel(253, repo, StillUserModeration(), NoBlockedUsers()) }
        withContext(main.dispatcher) { vm.refresh() }
        assertEquals(0, repo.calls.get(), "没打开过社区 tab：下拉刷新不发请求")

        withContext(main.dispatcher) { vm.start() }
        awaitUntil("吐槽与入口数量落定") { vm.uiState.value.comments.items.isNotEmpty() && vm.uiState.value.overview is Load.Ready }
        val before = repo.calls.get()

        commentsFail = true
        withContext(main.dispatcher) { vm.refresh() }
        awaitUntil("给出失败提示") { vm.failures.current.value != null }

        assertTrue(repo.calls.get() > before, "下拉刷新重取了")
        assertEquals(listOf(1), vm.uiState.value.comments.items.map { it.anchor.id }, "已显示的吐槽留着")
        assertTrue(vm.failures.current.value?.retry != null, "提示带重试")
    }

    private fun reactable(id: Int, creatorId: Int, vararg reactions: CommentReactionBo) =
        post(id, creatorId).copy(reactions = reactions.toList())

    @Test
    fun `吐槽贴贴：请求失败退回原来的贴贴并提示`() = runBlocking {
        val original = listOf(reactionOf(0, 3))
        val repo = FakeCommunity().apply {
            comments = { _, _ -> PagedResult(listOf(CommentThreadBo(reactable(5, 9, *original.toTypedArray()))), total = 1, nextOffset = null) }
            react = { _, _ -> error("429") }
        }
        val vm = onMain { SubjectCommunityViewModel(253, repo, StillUserModeration(), NoBlockedUsers()) }
        withContext(main.dispatcher) { vm.start() }
        awaitUntil("吐槽到位") { vm.uiState.value.comments.items.isNotEmpty() }
        withContext(main.dispatcher) { vm.react(5, 0) }
        awaitUntil("给出失败提示") { vm.failures.current.value != null }
        assertTrue(vm.failures.current.value?.text is NoticeText.Reason)
        assertEquals(original, vm.uiState.value.comments.items.single().anchor.reactions)
    }

    @Test
    fun `吐槽贴贴：贴上之后再加载下一页，贴贴留着`() = runBlocking {
        val repo = FakeCommunity().apply {
            comments = { _, offset ->
                if (offset == 0) {
                    PagedResult(listOf(CommentThreadBo(reactable(5, 9, reactionOf(0, 3)))), total = 2, nextOffset = 1)
                } else {
                    PagedResult(listOf(CommentThreadBo(post(6, 9))), total = 2, nextOffset = null)
                }
            }
        }
        val vm = onMain { SubjectCommunityViewModel(253, repo, StillUserModeration(), NoBlockedUsers()) }
        withContext(main.dispatcher) { vm.start() }
        awaitUntil("第一页吐槽到位") { vm.uiState.value.comments.items.isNotEmpty() }
        withContext(main.dispatcher) { vm.react(5, 0) }
        awaitUntil("贴贴已发出") { repo.reactCalls.isNotEmpty() }

        withContext(main.dispatcher) { vm.loadMore() }
        awaitUntil("第二页接上") { vm.uiState.value.comments.items.size == 2 }

        assertEquals(
            listOf(reactionOf(0, 4, selected = true)),
            vm.uiState.value.comments.items.first().anchor.reactions,
            "加载下一页不得把刚贴上的贴贴退回原样",
        )
    }
}
