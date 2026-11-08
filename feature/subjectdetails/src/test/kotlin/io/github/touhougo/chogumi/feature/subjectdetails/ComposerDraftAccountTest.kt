package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.reader.ComposerActions
import io.github.touhougo.chogumi.core.reader.ComposerState

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.data.db.ChogumiDatabase
import io.github.touhougo.chogumi.core.data.db.community.PostDraftDao
import io.github.touhougo.chogumi.core.data.db.community.PostDraftEntity
import io.github.touhougo.chogumi.core.data.repository.DraftRepository
import io.github.touhougo.chogumi.core.data.repository.TestMain
import io.github.touhougo.chogumi.core.data.repository.WAIT_BUDGET_MS
import io.github.touhougo.chogumi.core.data.repository.awaitUntil
import io.github.touhougo.chogumi.core.data.repository.cancelAndAwait
import io.github.touhougo.chogumi.core.data.repository.postDrafts
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.PostDraftKind
import io.github.touhougo.chogumi.core.network.api.CommunityApi
import io.github.touhougo.chogumi.core.network.testApiClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实草稿仓库与编辑器共用一个账号绑定；延迟动作不能改变新账号的同键草稿。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerDraftAccountTest {
    private class ReadGate(private val dao: PostDraftDao) : PostDraftDao by dao {
        var pause = false
        var failWrites = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun upsert(draft: PostDraftEntity) {
            if (failWrites) throw FailureException(Failure.Storage)
            dao.upsert(draft)
        }

        override suspend fun get(accountId: Int, kind: String, key: String): PostDraftEntity? {
            val row = dao.get(accountId, kind, key)
            if (pause) {
                entered.complete(Unit)
                release.await()
            }
            return row
        }
    }

    /** 调度器只在测试明确推进时走；Room、DataStore、HTTP 客户端与作用域都归这一份所有者。 */
    private class Owner(afterSend: suspend (Int) -> Unit = {}) {
        val scheduler = TestCoroutineScheduler()
        private val main = TestMain().apply { install() }
        private val composerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        private val saveScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChogumiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val readGate = ReadGate(database.postDraftDao())
        val tokens = testTokenStore(storeScope)
        val drafts = DraftRepository(database.topicReplyDraftDao(), readGate, tokens, saveScope)
        private val engine = MockEngine { respond("""{"id":501}""", headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }
        private val client = testApiClient(engine)
        private val api = CommunityApi(client)
        var state = ComposerState<Unit>()
            private set
        val actions = ComposerActions<Unit, Int>(
            scope = composerScope,
            state = { state },
            update = { state = it(state) },
            submit = { _, draft, token ->
                outcomeOf {
                    tokens.withSessionToken(tokens.requireSession()) { _, access ->
                        api.createGroupTopic("group", draft.title, draft.text, token, requireNotNull(access))
                    }
                }
            },
            afterSend = afterSend,
            drafts = drafts.postDrafts(PostDraftKind.GROUP_TOPIC, "group", AppClock { 10 }),
        )

        suspend fun switchTo(id: Int) {
            tokens.completeSession(tokens.beginSession("draft-test-$id"), id, "draft-user-$id")
        }

        suspend fun seed() {
            switchTo(8)
            drafts.savePost((drafts.bind() as Outcome.Success).value, PostDraftKind.GROUP_TOPIC, "group", "乙标题", "乙已存正文", now = 1)
            switchTo(7)
            drafts.savePost((drafts.bind() as Outcome.Success).value, PostDraftKind.GROUP_TOPIC, "group", "甲标题", "甲已存正文", now = 2)
        }

        fun start(action: () -> Unit): List<Job> {
            val before = composerScope.coroutineContext.job.children.toSet() + saveScope.coroutineContext.job.children.toSet()
            action()
            return (composerScope.coroutineContext.job.children + saveScope.coroutineContext.job.children).filter { it !in before }.toList()
        }

        suspend fun awaitRound(jobs: List<Job>) {
            awaitUntil("本轮编辑器动作完成") {
                scheduler.runCurrent()
                jobs.all { it.isCompleted }
            }
        }

        suspend fun round(action: () -> Unit) = awaitRound(start(action))

        suspend fun open() = round { actions.open(Unit) }

        suspend fun row(id: Int) = database.postDraftDao().get(id, "GROUP_TOPIC", "group")

        suspend fun awaitSaves() {
            awaitUntil("原账号草稿收尾完成") {
                scheduler.runCurrent()
                saveScope.coroutineContext.job.children.none()
            }
        }

        suspend fun leavePage() = withContext(Dispatchers.Default) {
            val stopped = async { cancelAndAwait(listOf(composerScope)) }
            awaitUntil("编辑页面作用域已取消") {
                scheduler.runCurrent()
                stopped.isCompleted
            }
            stopped.await()
            awaitSaves()
        }

        suspend fun close() {
            client.close()
            engine.close()
            withTimeout(WAIT_BUDGET_MS) {
                client.coroutineContext.job.join()
                engine.coroutineContext.job.join()
            }
            withContext(Dispatchers.Default) {
                val stopped = async { main.uninstall(listOf(composerScope, saveScope, storeScope)) }
                awaitUntil("草稿测试所有者释放") {
                    scheduler.runCurrent()
                    stopped.isCompleted
                }
                stopped.await()
            }
            database.close()
        }
    }

    private fun owned(afterSend: suspend (Int) -> Unit = {}, body: suspend Owner.() -> Unit) = runBlocking {
        val owner = Owner(afterSend)
        try {
            owner.seed()
            owner.body()
        } finally {
            owner.close()
        }
    }

    @Test
    fun `换号后的停笔保存只更新原账号的草稿`() = owned {
        open()
        val save = start { actions.edit(ComposerDraft("甲新标题", "甲未落盘正文")) }
        scheduler.runCurrent()
        switchTo(8)
        scheduler.advanceTimeBy(501)
        awaitRound(save)
        round { actions.close() }

        assertEquals("乙标题", row(8)?.title)
        assertEquals("乙已存正文", row(8)?.text, "停笔保存不能覆盖新账号的同键草稿")
        assertEquals("甲新标题", row(7)?.title)
        assertEquals("甲未落盘正文", row(7)?.text, "未落盘输入只向捕获的原账号保存")
    }

    @Test
    fun `换号后的关闭刷新只保存原账号未落盘的输入`() = owned {
        open()
        actions.edit(ComposerDraft("甲新标题", "甲未落盘正文"))
        switchTo(8)
        round { actions.close() }

        assertEquals("乙已存正文", row(8)?.text, "关闭面板不能把旧正文写入新账号")
        assertEquals("甲未落盘正文", row(7)?.text)
        assertNull(state.target)
    }

    @Test
    fun `旧提交刷新列表期间换号不会删除新账号草稿`() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        owned(afterSend = { entered.complete(Unit); release.await() }) {
            open()
            val send = start { actions.send(ComposerDraft("甲标题", "甲提交正文"), "test-turnstile") }
            awaitUntil("已提交并进入列表刷新") {
                scheduler.runCurrent()
                entered.isCompleted
            }
            switchTo(8)
            release.complete(Unit)
            awaitRound(send)
            round { actions.close() }

            assertEquals("乙已存正文", row(8)?.text, "旧提交的删除只属于原账号")
            assertNull(row(7), "已成功提交的原账号草稿应清理")
            assertNull(state.target)
        }
    }

    @Test
    fun `旧账号草稿读取迟到不会在新账号打开面板`() = owned {
        readGate.pause = true
        val open = start { actions.open(Unit) }
        awaitUntil("原账号草稿已读出") {
            scheduler.runCurrent()
            readGate.entered.isCompleted
        }
        switchTo(8)
        readGate.release.complete(Unit)
        awaitRound(open)

        assertNull(state.target, "读取结果属于已离开的账号，不能打开面板")
        assertEquals("乙已存正文", row(8)?.text)
        assertEquals("甲已存正文", row(7)?.text)
    }

    @Test
    fun `换号无需关闭事件也会收起旧账号面板并保存输入`() = owned {
        open()
        actions.edit(ComposerDraft("甲新标题", "甲最后一笔"))
        switchTo(8)
        awaitUntil("旧账号面板已收起") {
            scheduler.runCurrent()
            state.target == null
        }
        awaitSaves()

        assertEquals("甲最后一笔", row(7)?.text)
        assertEquals("乙已存正文", row(8)?.text)
    }

    @Test
    fun `页面取消后最后一笔仍由草稿存储保存到原账号`() = owned {
        open()
        actions.edit(ComposerDraft("甲新标题", "页面离开的最后一笔"))
        switchTo(8)
        leavePage()

        assertEquals("页面离开的最后一笔", row(7)?.text)
        assertEquals("乙已存正文", row(8)?.text)
    }

    @Test
    fun `旧账号落盘失败仍保留待存输入且新账号重开只读自己的稿`() = owned {
        open()
        readGate.failWrites = true
        actions.edit(ComposerDraft("甲新标题", "故障时未落盘正文"))
        switchTo(8)
        round { actions.close() }
        awaitSaves()
        assertNotNull(actions.failures.current.value, "落盘失败不能静默丢稿")
        assertEquals("甲已存正文", row(7)?.text)

        open()
        assertEquals("乙已存正文", state.text)
        round { actions.close() }
        switchTo(7)
        open()
        assertEquals("故障时未落盘正文", state.text, "原账号重开从唯一待存队列恢复最新输入")
        readGate.failWrites = false
        round { actions.close() }
        awaitSaves()
        assertEquals("故障时未落盘正文", row(7)?.text)
        assertEquals("乙已存正文", row(8)?.text)
    }
}
