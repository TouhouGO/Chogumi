package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.bbcode.QuoteHeader
import io.github.touhougo.chogumi.core.common.bbcode.quoteOf
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.NoBlockedUsers
import io.github.touhougo.chogumi.core.data.repository.StillUserModeration
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.TopicRepository
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.boundDraftOwner
import io.github.touhougo.chogumi.core.data.repository.testDraftRepository
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.CommentItemBo
import io.github.touhougo.chogumi.core.model.CommentReactionBo
import io.github.touhougo.chogumi.core.model.CommentState
import io.github.touhougo.chogumi.core.model.CommentThreadBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TopicDetailBo
import io.github.touhougo.chogumi.core.model.TopicParent
import io.github.touhougo.chogumi.core.model.TopicSort
import io.github.touhougo.chogumi.core.model.TopicSubjectBo
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.model.shownUnder
import io.github.touhougo.chogumi.core.reader.EditTarget
import io.github.touhougo.chogumi.core.reader.ReplyTarget
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class TopicViewModelTest {

    private val main = TestMain()
    private val viewModels = mutableListOf<StateViewModel<*>>()
    private val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest
    fun setUp() {
        main.install()
    }

    @AfterTest
    fun tearDown() {
        main.uninstall(viewModels.map { it.viewModelScope } + draftScope)
    }

    private suspend fun <T : StateViewModel<*>> onMain(create: () -> T): T =
        withContext(main.dispatcher) { create().also { viewModels += it } }

    private fun post(id: Int, creatorId: Int, content: String = "c$id") = CommentItemBo(
        id = id, mainId = 1, relatedId = 0, creatorId = creatorId, creatorNickname = "u$creatorId",
        creatorUsername = "u$creatorId", creatorAvatar = null, content = content, createdAt = id.toLong(),
        state = CommentState.NORMAL,
    )

    private fun reactable(id: Int, creatorId: Int, vararg reactions: CommentReactionBo) =
        post(id, creatorId).copy(reactions = reactions.toList())

    private fun topicOf(vararg threads: CommentThreadBo) = TopicDetailBo(
        id = 1, title = "t", parent = TopicParent.Subject(TopicSubjectBo(253, SubjectType.ANIME, "n", "", null)),
        creatorId = 7, replyCount = threads.size, createdAt = 0L, threads = threads.toList(),
    )

    /** 记下每次取话题、贴贴带的话题类型与回复的正文、归属楼、令牌。 */
    private class FakeTopics(var topic: () -> TopicDetailBo) : TopicRepository {
        val fetched = ConcurrentLinkedQueue<Pair<TopicType, Int>>()
        val reacted = ConcurrentLinkedQueue<Triple<TopicType, Int, Int?>>()
        val replied = ConcurrentLinkedQueue<Triple<String, Int, String>>()
        val edited = ConcurrentLinkedQueue<Pair<Int, String>>()
        val deleted = ConcurrentLinkedQueue<Int>()
        var replyResult: Outcome<Int> = Outcome.Success(NEW_POST_ID)

        override suspend fun getTopic(type: TopicType, topicId: Int): TopicDetailBo {
            fetched += type to topicId
            return topic()
        }

        override suspend fun setPostReaction(type: TopicType, postId: Int, value: Int?) = outcomeOf<Unit> {
            reacted += Triple(type, postId, value)
        }

        override suspend fun reply(type: TopicType, topicId: Int, content: String, replyTo: Int, turnstileToken: String): Outcome<Int> {
            replied += Triple(content, replyTo, turnstileToken)
            return replyResult
        }

        override suspend fun editPost(type: TopicType, postId: Int, content: String) = outcomeOf<Unit> {
            edited += postId to content
        }

        override suspend fun deletePost(type: TopicType, postId: Int) = outcomeOf<Unit> {
            deleted += postId
        }
    }

    private val drafts = testDraftRepository(draftScope)

    private suspend fun topicVm(
        type: TopicType,
        topicId: Int,
        repo: FakeTopics,
        draftPostId: Int? = null,
        blocked: NoBlockedUsers = NoBlockedUsers(),
    ) = onMain { TopicViewModel(type, topicId, draftPostId, repo, drafts, Dispatchers.Default, AppClock { 1234L }, StillUserModeration(), blocked) }

    private companion object {
        const val NEW_POST_ID = 99
    }

    @Test
    fun `已显示的楼层里绝交一个人，这个人的楼与楼中楼当场不显示且不重取楼层，取消后回来`() = runBlocking {
        val repo = FakeTopics {
            topicOf(
                CommentThreadBo(post(10, 7, "正文")),
                CommentThreadBo(post(20, 9)),
                CommentThreadBo(post(30, 2), listOf(post(31, 9), post(32, 1))),
            )
        }
        val blocked = NoBlockedUsers()
        val vm = topicVm(TopicType.SUBJECT, 1, repo, blocked = blocked)
        awaitUntil("话题落定") { vm.uiState.value.topic is Load.Ready }
        fun shown() = vm.uiState.value.reading.replies.map { floor -> floor.thread.anchor.id to floor.thread.replies.map { it.id } }
        assertEquals(listOf(20 to emptyList(), 30 to listOf(31, 32)), shown())

        blocked.list.value = BlockedUsers(setOf(9))
        awaitUntil("绝交后楼层跟上") { vm.uiState.value.reading.blocked?.hides(9) == true }
        assertEquals(listOf(30 to listOf(32)), shown())
        assertEquals(1, repo.fetched.size)

        blocked.list.value = BlockedUsers.NONE
        awaitUntil("取消绝交后楼层跟上") { vm.uiState.value.reading.blocked == BlockedUsers.NONE }
        assertEquals(listOf(20 to emptyList(), 30 to listOf(31, 32)), shown())
        assertEquals(1, repo.fetched.size)
    }

    @Test
    fun `名单还没读到时楼层先到_不先画出被绝交的人的楼_整页仍是骨架_名单到了按它一次画出`() = runBlocking<Unit> {
        val repo = FakeTopics {
            topicOf(
                CommentThreadBo(post(10, 7, "正文")),
                CommentThreadBo(post(20, 9)),
                CommentThreadBo(post(30, 2), listOf(post(31, 9), post(32, 1))),
            )
        }
        val blocked = NoBlockedUsers(initial = null)
        val vm = topicVm(TopicType.SUBJECT, 1, repo, blocked = blocked)
        awaitUntil("话题先到") { vm.uiState.value.topic is Load.Ready }
        fun shown() = vm.uiState.value.reading.replies.map { floor -> floor.thread.anchor.id to floor.thread.replies.map { it.id } }
        assertEquals(emptyList(), shown(), "名单没到：一层楼也不给，不先按谁也不屏蔽画出来")
        assertEquals(Load.Pending, vm.uiState.value.topic.shownUnder(vm.uiState.value.reading.blocked), "页面按它画：仍是骨架")

        blocked.list.value = BlockedUsers(setOf(9))
        awaitUntil("名单到了") { vm.uiState.value.reading.blocked != null }
        assertEquals(listOf(30 to listOf(32)), shown())
        assertIs<Load.Ready<*>>(vm.uiState.value.topic.shownUnder(vm.uiState.value.reading.blocked), "名单到了：整页画出来")
    }

    @Test
    fun `楼层随排序与只看楼主派生，引用落点被滤掉时不可点`() = runBlocking {
        // #1 楼主；#2 路人；#3 路人引用 #2（同串外，按全帖更早楼层反查）
        val repo = FakeTopics {
            topicOf(
                CommentThreadBo(post(10, 7, "正文")),
                CommentThreadBo(post(20, 1, "路人的观点很长很长")),
                CommentThreadBo(post(30, 2), listOf(post(31, 2, "[quote][b]u1[/b] 说: 路人的观点[/quote]同意"))),
            )
        }
        val vm = topicVm(TopicType.SUBJECT, 1, repo)
        awaitUntil("话题落定") { vm.uiState.value.topic !is Load.Pending }

        val loaded = vm.uiState.value
        assertEquals(listOf(1, 2, 3), loaded.reading.floors.map { it.number })
        assertEquals(mapOf(31 to 20), loaded.reading.quoteTargets)
        assertEquals(1, loaded.reading.floorIndexByPost[20])

        withContext(main.dispatcher) { vm.selectSort(TopicSort.LATEST) }
        assertEquals(listOf(1, 3, 2), vm.uiState.value.reading.floors.map { it.number })
        assertEquals(2, vm.uiState.value.reading.floorIndexByPost[20], "落点下标随排序更新")

        withContext(main.dispatcher) { vm.toggleOpOnly() }
        val opOnly = vm.uiState.value
        assertEquals(listOf(1), opOnly.reading.floors.map { it.number })
        assertEquals(emptyMap<Int, Int>(), opOnly.reading.quoteTargets, "被引楼不在列表里：引用不可点")
    }

    @Test
    fun `加载失败给出错误，重试成功后清掉`() = runBlocking {
        var fail = true
        val repo = FakeTopics { if (fail) error("boom") else topicOf(CommentThreadBo(post(10, 7))) }
        val vm = topicVm(TopicType.SUBJECT, 1, repo)
        awaitUntil("首次加载失败落定") { vm.uiState.value.topic is Load.Failed }

        fail = false
        withContext(main.dispatcher) { vm.retry() }
        awaitUntil("重试后话题到位") { vm.uiState.value.topic is Load.Ready }
        assertFalse(vm.uiState.value.topic is Load.Failed)
        assertEquals(listOf(1), vm.uiState.value.reading.floors.map { it.number })
    }

    @Test
    fun `小组话题：取话题与贴贴都带小组类型，先改本地再发请求，换贴是替换我原来那个`() = runBlocking {
        val repo = FakeTopics {
            topicOf(
                CommentThreadBo(post(10, 7)),
                CommentThreadBo(post(20, 1), listOf(reactable(21, 2, CommentReactionBo(0, 2, selected = true), CommentReactionBo(54, 1)))),
            )
        }
        val vm = topicVm(TopicType.GROUP, 472971, repo)
        awaitUntil("话题落定") { vm.uiState.value.topic !is Load.Pending }
        withContext(main.dispatcher) { vm.react(21, 54) }
        awaitUntil("请求发出") { repo.reacted.isNotEmpty() }

        assertEquals(listOf(TopicType.GROUP to 472971), repo.fetched.toList())
        assertEquals(listOf(Triple(TopicType.GROUP, 21, 54)), repo.reacted.toList(), "楼中楼也按楼 id 贴")
        assertEquals(
            listOf(CommentReactionBo(0, 1), CommentReactionBo(54, 2, selected = true)),
            vm.uiState.value.reading.threads[1].replies.single().reactions,
        )
        assertNull(vm.failures.current.value)
    }

    @Test
    fun `回复楼中楼：正文带引用、挂在所在主楼下，发出后重新拉楼层、面板收起并交回新楼 id`() = runBlocking {
        val repo = FakeTopics { topicOf(CommentThreadBo(post(10, 7)), CommentThreadBo(post(20, 1), listOf(post(21, 2, "楼中楼的话")))) }
        val vm = topicVm(TopicType.GROUP, 5, repo)
        awaitUntil("话题落定") { vm.uiState.value.topic !is Load.Pending }
        val target = ReplyTarget(postId = 21, replyTo = 20, floor = "#2-1", preview = QuoteHeader("u2", "楼中楼的话"), quote = quoteOf("u2", "楼中楼的话"))
        var sentId: Int? = null
        // 留草稿的面板先读草稿再打开：面板出现了才能发
        withContext(main.dispatcher) { vm.composer.open(target) }
        awaitUntil("面板打开") { vm.uiState.value.composer.target != null }
        withContext(main.dispatcher) { vm.composer.send(ComposerDraft(title = "", text = "同意"), "tk") { sentId = it } }
        awaitUntil("新楼 id 交回") { sentId != null }

        assertEquals(listOf(Triple("[quote][b]u2[/b] 说: 楼中楼的话[/quote]\n同意", 20, "tk")), repo.replied.toList())
        assertEquals(2, repo.fetched.size, "发出后重新拉一次楼层")
        assertEquals(NEW_POST_ID, sentId)
        assertTrue(vm.uiState.value.composer.sent, "面板收起")
        assertNull(vm.composer.failures.current.value)
    }

    @Test
    fun `回复被拒：面板和对象留着、提示原因，不重新拉楼层`() = runBlocking {
        val repo = FakeTopics { topicOf(CommentThreadBo(post(10, 7))) }
        repo.replyResult = Outcome.Failed(Failure.Rejected(RejectReason.POSTING_NOT_ALLOWED))
        val vm = topicVm(TopicType.SUBJECT, 1, repo)
        awaitUntil("话题落定") { vm.uiState.value.topic !is Load.Pending }
        withContext(main.dispatcher) { vm.composer.open(ReplyTarget.Topic) }
        awaitUntil("面板打开") { vm.uiState.value.composer.target != null }
        withContext(main.dispatcher) { vm.composer.send(ComposerDraft(title = "", text = "同意"), "tk") }
        awaitUntil("提示到位") { vm.composer.failures.current.value != null }

        val composer = vm.uiState.value.composer
        assertEquals(ReplyTarget.Topic, composer.target)
        assertFalse(composer.sending || composer.sent)
        assertEquals(listOf(Triple("同意", 0, "tk")), repo.replied.toList())
        assertEquals(1, repo.fetched.size)
    }

    @Test
    fun `回复留草稿：关面板存下，从草稿进页接着写，发出后删掉`() = runBlocking {
        val repo = FakeTopics { topicOf(CommentThreadBo(post(10, 7)), CommentThreadBo(post(20, 1))) }
        val target = ReplyTarget(postId = 20, replyTo = 20, floor = "#2", preview = QuoteHeader("u1", "c20"))
        val first = topicVm(TopicType.GROUP, 5, repo)
        awaitUntil("话题落定") { first.uiState.value.topic !is Load.Pending }
        withContext(main.dispatcher) { first.composer.open(target) }
        awaitUntil("面板打开") { first.uiState.value.composer.target != null }
        withContext(main.dispatcher) {
            first.composer.edit(ComposerDraft("", "写了一半"))
            first.composer.close()
        }
        awaitUntil("草稿落库") { runBlocking { drafts.get(drafts.boundDraftOwner(), TopicType.GROUP, 5, 20) } != null }
        assertEquals("t", drafts.get(drafts.boundDraftOwner(), TopicType.GROUP, 5, 20)?.topicTitle, "草稿记下话题标题，草稿列表认帖子用")

        val second = topicVm(TopicType.GROUP, 5, repo, draftPostId = 20)
        awaitUntil("草稿面板打开") { second.uiState.value.composer.target != null }
        assertEquals(target, second.uiState.value.composer.target, "从草稿还原回复对象")
        assertEquals("写了一半", second.uiState.value.composer.text)

        withContext(main.dispatcher) { second.composer.send(ComposerDraft(title = "", text = "写完了"), "tk") }
        awaitUntil("面板收起") { second.uiState.value.composer.sent }
        assertNull(drafts.get(drafts.boundDraftOwner(), TopicType.GROUP, 5, 20), "发出成功删掉草稿")
    }

    @Test
    fun `发送失败草稿还在：点发送时立刻落库，不等停笔`() = runBlocking {
        val repo = FakeTopics { topicOf(CommentThreadBo(post(10, 7))) }
        repo.replyResult = Outcome.Failed(Failure.Rejected(RejectReason.POSTING_NOT_ALLOWED))
        val vm = topicVm(TopicType.SUBJECT, 1, repo)
        awaitUntil("话题落定") { vm.uiState.value.topic !is Load.Pending }
        withContext(main.dispatcher) { vm.composer.open(ReplyTarget.Topic) }
        awaitUntil("面板打开") { vm.uiState.value.composer.target != null }
        withContext(main.dispatcher) {
            vm.composer.edit(ComposerDraft("", "没发出去的话"))
            vm.composer.send(ComposerDraft(title = "", text = "没发出去的话"), "tk")
        }
        awaitUntil("提示到位") { vm.composer.failures.current.value != null }

        assertEquals("没发出去的话", drafts.get(drafts.boundDraftOwner(), TopicType.SUBJECT, 1, 0)?.text)
    }

    @Test
    fun `改自己的楼：引用头原样留着，正文换成新的，不要验证令牌，改完重新拉楼层`() = runBlocking {
        val quote = quoteOf("u2", "楼中楼的话")
        val mine = post(22, 1, quote + "旧的话").copy(isOwn = true)
        val repo = FakeTopics { topicOf(CommentThreadBo(post(10, 7)), CommentThreadBo(post(20, 2), listOf(post(21, 2, "楼中楼的话"), mine))) }
        val vm = topicVm(TopicType.GROUP, 5, repo)
        awaitUntil("话题落定") { vm.uiState.value.topic !is Load.Pending }
        val target = EditTarget(postId = 22, floor = "#2-2", lead = quote, quote = null, body = "旧的话")
        withContext(main.dispatcher) { vm.edit(target) }
        assertEquals("旧的话", vm.uiState.value.editor.text, "编辑框里是去掉引用头的正文")
        withContext(main.dispatcher) { vm.editor.send(ComposerDraft(title = "", text = "新的话"), "") }
        awaitUntil("面板收起") { vm.uiState.value.editor.sent }

        assertEquals(listOf(22 to quote + "新的话"), repo.edited.toList())
        assertEquals(2, repo.fetched.size, "改完重新拉一次楼层")
    }

    @Test
    fun `删自己的楼：发删除请求后重新拉楼层`() = runBlocking {
        val repo = FakeTopics { topicOf(CommentThreadBo(post(10, 7)), CommentThreadBo(post(20, 1).copy(isOwn = true))) }
        val vm = topicVm(TopicType.SUBJECT, 1, repo)
        awaitUntil("话题落定") { vm.uiState.value.topic !is Load.Pending }
        withContext(main.dispatcher) { vm.deletePost(20) }
        awaitUntil("重新拉楼层") { repo.fetched.size == 2 }

        assertEquals(listOf(20), repo.deleted.toList())
        assertNull(vm.failures.current.value)
    }
}
