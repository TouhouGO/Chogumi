package io.github.touhougo.chogumi.feature.subjectdetails

import io.github.touhougo.chogumi.core.reader.ComposerActions
import io.github.touhougo.chogumi.core.reader.ComposerState

import io.github.touhougo.chogumi.core.common.draft.ComposerDraft
import io.github.touhougo.chogumi.core.common.draft.ComposerDraftOwner
import io.github.touhougo.chogumi.core.data.repository.TestComposerDrafts
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.res.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerActionsTest {
    private fun emptyDrafts(scope: CoroutineScope) = object : TestComposerDrafts<String>(scope) {
        override fun keyOf(target: String): Any = target
        override suspend fun read(owner: ComposerDraftOwner, target: String) = Outcome.Success(null)
        override suspend fun write(owner: ComposerDraftOwner, target: String, draft: ComposerDraft) = Outcome.Success(Unit)
        override suspend fun remove(owner: ComposerDraftOwner, target: String) = Outcome.Success(Unit)
    }

    @Test
    fun `关闭后才读到草稿不会重新打开面板`() = runTest {
        var state = ComposerState<String>()
        val loaded = CompletableDeferred<String?>()
        val drafts = object : TestComposerDrafts<String>(backgroundScope) {
            override fun keyOf(target: String): Any = target
            override suspend fun read(owner: ComposerDraftOwner, target: String) = Outcome.Success(loaded.await()?.let { ComposerDraft("", it) })
            override suspend fun write(owner: ComposerDraftOwner, target: String, draft: ComposerDraft) = Outcome.Success(Unit)
            override suspend fun remove(owner: ComposerDraftOwner, target: String) = Outcome.Success(Unit)
        }
        val actions = ComposerActions<String, Unit>(
            scope = backgroundScope,
            state = { state },
            update = { state = it(state) },
            submit = { _, _, _ -> Outcome.Success(Unit) },
            drafts = drafts,
        )
        actions.open("a")
        runCurrent()
        actions.close()
        loaded.complete("草稿")
        runCurrent()
        assertNull(state.target)
    }

    @Test
    fun `发送旧面板后打开另一个面板不会被旧成功收起`() = runTest {
        var state = ComposerState<String>()
        val submitted = CompletableDeferred<Outcome<Unit>>()
        var sent = 0
        val actions = ComposerActions<String, Unit>(
            scope = backgroundScope,
            state = { state },
            update = { state = it(state) },
            submit = { _, _, _ -> submitted.await() },
        )
        actions.open("a")
        actions.send(ComposerDraft("", "旧回复"), "", onSent = { sent++ })
        runCurrent()
        actions.close()
        actions.open("b", "新回复")
        submitted.complete(Outcome.Success(Unit))
        runCurrent()
        assertEquals("b", state.target)
        assertEquals("新回复", state.text)
        assertFalse(state.sent)
        assertFalse(state.sending)
        assertEquals(0, sent)
    }
    @Test
    fun `保存失败的旧文本不会覆盖重新打开后已保存的新文本`() = runTest {
        var state = ComposerState<String>()
        val stored = mutableMapOf<String, String>()
        var accept = false
        var oldAvailable = false
        val drafts = object : TestComposerDrafts<String>(backgroundScope) {
            override fun keyOf(target: String): Any = target
            override suspend fun read(owner: ComposerDraftOwner, target: String) = Outcome.Success(stored[target]?.let { ComposerDraft("", it) })
            override suspend fun write(owner: ComposerDraftOwner, target: String, draft: ComposerDraft): Outcome<Unit> {
                if (!accept || (draft.text == "旧文本" && !oldAvailable)) return Outcome.Failed(Failure.Offline)
                stored[target] = draft.text
                return Outcome.Success(Unit)
            }
            override suspend fun remove(owner: ComposerDraftOwner, target: String): Outcome<Unit> {
                stored.remove(target)
                return Outcome.Success(Unit)
            }
        }
        val actions = ComposerActions<String, Unit>(backgroundScope, { state }, { state = it(state) }, { _, _, _ -> Outcome.Success(Unit) }, drafts = drafts)
        actions.open("a")
        runCurrent()
        actions.edit(ComposerDraft("", "旧文本"))
        advanceTimeBy(501)
        runCurrent()
        assertNotNull(actions.failures.current.value)
        actions.close()
        runCurrent()
        actions.open("a")
        runCurrent()
        accept = true
        actions.edit(ComposerDraft("", "新文本"))
        advanceTimeBy(501)
        runCurrent()
        actions.close()
        runCurrent()
        assertEquals("新文本", stored["a"])
        // 旧文本的存储故障恢复后也不能让它复活。
        oldAvailable = true
        actions.open("b")
        runCurrent()
        assertEquals("新文本", stored["a"])
    }

    @Test
    fun `旧提交成功仍保留后来打开面板的待存草稿`() = runTest {
        var state = ComposerState<String>()
        val stored = mutableMapOf<String, String>()
        val submitted = CompletableDeferred<Outcome<Unit>>()
        val drafts = object : TestComposerDrafts<String>(backgroundScope) {
            override fun keyOf(target: String): Any = target
            override suspend fun read(owner: ComposerDraftOwner, target: String) = Outcome.Success(stored[target]?.let { ComposerDraft("", it) })
            override suspend fun write(owner: ComposerDraftOwner, target: String, draft: ComposerDraft): Outcome<Unit> {
                stored[target] = draft.text
                return Outcome.Success(Unit)
            }
            override suspend fun remove(owner: ComposerDraftOwner, target: String): Outcome<Unit> {
                stored.remove(target)
                return Outcome.Success(Unit)
            }
        }
        val actions = ComposerActions<String, Unit>(backgroundScope, { state }, { state = it(state) }, { _, _, _ -> submitted.await() }, drafts = drafts)
        actions.open("a")
        runCurrent()
        actions.send(ComposerDraft("", "已提交"), "")
        runCurrent()
        actions.close()
        actions.open("b")
        runCurrent()
        actions.edit(ComposerDraft("", "待保存"))
        submitted.complete(Outcome.Success(Unit))
        runCurrent()
        actions.close()
        runCurrent()
        assertEquals("待保存", stored["b"])
        assertNull(stored["a"])
    }

    private data class DraftTarget(val id: Int, val description: String)

    @Test
    fun `同一草稿的对象说明变了旧提交成功也不删除新正文`() = runTest {
        var state = ComposerState<DraftTarget>()
        val stored = mutableMapOf<Int, String>()
        val submitted = CompletableDeferred<Outcome<Unit>>()
        val drafts = object : TestComposerDrafts<DraftTarget>(backgroundScope) {
            override fun keyOf(target: DraftTarget): Any = target.id
            override suspend fun read(owner: ComposerDraftOwner, target: DraftTarget) = Outcome.Success(stored[target.id]?.let { ComposerDraft("", it) })
            override suspend fun write(owner: ComposerDraftOwner, target: DraftTarget, draft: ComposerDraft): Outcome<Unit> {
                stored[target.id] = draft.text
                return Outcome.Success(Unit)
            }
            override suspend fun remove(owner: ComposerDraftOwner, target: DraftTarget): Outcome<Unit> {
                stored.remove(target.id)
                return Outcome.Success(Unit)
            }
        }
        val actions = ComposerActions<DraftTarget, Unit>(backgroundScope, { state }, { state = it(state) }, { _, _, _ -> submitted.await() }, drafts = drafts)
        actions.open(DraftTarget(1, "旧摘要"))
        runCurrent()
        actions.send(ComposerDraft("", "已提交正文"), "")
        runCurrent()
        actions.close()
        val reopened = DraftTarget(1, "新摘要")
        actions.open(reopened)
        runCurrent()
        actions.edit(ComposerDraft("", "新草稿"))
        advanceTimeBy(501)
        runCurrent()
        submitted.complete(Outcome.Success(Unit))
        runCurrent()
        assertEquals("新草稿", stored[1])
        assertEquals(reopened, state.target)
        assertFalse(state.sent)
    }

    @Test
    fun `异步打开草稿时不提交先前面板的正文`() = runTest {
        var state = ComposerState<String>()
        val loaded = CompletableDeferred<String?>()
        val submitted = mutableListOf<String>()
        val drafts = object : TestComposerDrafts<String>(backgroundScope) {
            override fun keyOf(target: String): Any = target
            override suspend fun read(owner: ComposerDraftOwner, target: String) = Outcome.Success((if (target == "b") loaded.await() else null)?.let { ComposerDraft("", it) })
            override suspend fun write(owner: ComposerDraftOwner, target: String, draft: ComposerDraft) = Outcome.Success(Unit)
            override suspend fun remove(owner: ComposerDraftOwner, target: String) = Outcome.Success(Unit)
        }
        val actions = ComposerActions<String, Unit>(
            backgroundScope, { state }, { state = it(state) },
            { target, _, _ -> submitted += target; Outcome.Success(Unit) }, drafts = drafts,
        )
        actions.open("a")
        runCurrent()
        actions.open("b")
        runCurrent()
        actions.send(ComposerDraft("", "正文"), "")
        loaded.complete("b的草稿")
        runCurrent()
        assertEquals(emptyList(), submitted)
        assertEquals("b", state.target)
        assertEquals("b的草稿", state.text)
        assertFalse(state.sent)
    }

    @Test
    fun `保存失败后重新打开仍接着最后未存的正文`() = runTest {
        var state = ComposerState<String>()
        val drafts = object : TestComposerDrafts<String>(backgroundScope) {
            override fun keyOf(target: String): Any = target
            override suspend fun read(owner: ComposerDraftOwner, target: String) = Outcome.Success(ComposerDraft("", "已保存的旧正文"))
            override suspend fun write(owner: ComposerDraftOwner, target: String, draft: ComposerDraft) = Outcome.Failed(Failure.Offline)
            override suspend fun remove(owner: ComposerDraftOwner, target: String) = Outcome.Success(Unit)
        }
        val actions = ComposerActions<String, Unit>(backgroundScope, { state }, { state = it(state) }, { _, _, _ -> Outcome.Success(Unit) }, drafts = drafts)
        actions.open("a")
        runCurrent()
        actions.edit(ComposerDraft("", "尚未存下的新正文"))
        actions.close()
        runCurrent()
        actions.open("a")
        runCurrent()
        assertEquals("尚未存下的新正文", state.text)
        assertNotNull(actions.failures.current.value)
    }

    @Test
    fun `重开同一草稿写完再关闭旧提交成功仍不删除新正文`() = runTest {
        var state = ComposerState<String>()
        val stored = mutableMapOf<String, String>()
        val submitted = CompletableDeferred<Outcome<Unit>>()
        val drafts = object : TestComposerDrafts<String>(backgroundScope) {
            override fun keyOf(target: String): Any = target
            override suspend fun read(owner: ComposerDraftOwner, target: String) = Outcome.Success(stored[target]?.let { ComposerDraft("", it) })
            override suspend fun write(owner: ComposerDraftOwner, target: String, draft: ComposerDraft): Outcome<Unit> {
                stored[target] = draft.text
                return Outcome.Success(Unit)
            }
            override suspend fun remove(owner: ComposerDraftOwner, target: String): Outcome<Unit> {
                stored.remove(target)
                return Outcome.Success(Unit)
            }
        }
        val actions = ComposerActions<String, Unit>(backgroundScope, { state }, { state = it(state) }, { _, _, _ -> submitted.await() }, drafts = drafts)
        actions.open("a")
        runCurrent()
        actions.send(ComposerDraft("", "提交正文"), "")
        runCurrent()
        actions.close()
        actions.open("a")
        runCurrent()
        actions.edit(ComposerDraft("", "新草稿"))
        actions.close()
        runCurrent()
        submitted.complete(Outcome.Success(Unit))
        runCurrent()
        assertEquals("新草稿", stored["a"])
        assertNull(state.target)
    }

    @Test
    fun `初始恢复的对象迟到不会替换用户打开的面板`() = runTest {
        var state = ComposerState<String>()
        val target = CompletableDeferred<Outcome<String?>>()
        val actions = ComposerActions<String, Unit>(backgroundScope, { state }, { state = it(state) }, { _, _, _ -> Outcome.Success(Unit) }, drafts = emptyDrafts(backgroundScope))
        actions.openSaved(loadTarget = { target.await() }, onFailure = { error("unexpected failure: $it") })
        runCurrent()
        actions.open("b", "用户正文")
        target.complete(Outcome.Success("a"))
        runCurrent()
        assertEquals("b", state.target)
        assertEquals("用户正文", state.text)
    }

    @Test
    fun `关闭后初始恢复的对象迟到不会打开面板`() = runTest {
        var state = ComposerState<String>()
        val target = CompletableDeferred<Outcome<String?>>()
        val actions = ComposerActions<String, Unit>(backgroundScope, { state }, { state = it(state) }, { _, _, _ -> Outcome.Success(Unit) }, drafts = emptyDrafts(backgroundScope))
        actions.openSaved(loadTarget = { target.await() }, onFailure = { error("unexpected failure: $it") })
        runCurrent()
        actions.close()
        target.complete(Outcome.Success("a"))
        runCurrent()
        assertNull(state.target)
    }

    @Test
    fun `提交超时不知道发出没有 说明并重取列表一次 面板与正文都留着`() = runTest {
        var state = ComposerState<String>()
        var unsure = 0
        val actions = ComposerActions<String, Unit>(
            scope = backgroundScope,
            state = { state },
            update = { state = it(state) },
            submit = { _, _, _ -> Outcome.Failed(Failure.Timeout) },
            afterUnsure = { unsure++ },
        )
        actions.open("a", "正文")
        actions.send(ComposerDraft("", "正文"), "")
        runCurrent()
        assertEquals(1, unsure)
        assertEquals("a", state.target)
        assertFalse(state.sending)
        assertFalse(state.sent)
        assertEquals(R.string.composer_send_unsure, (actions.failures.current.value?.text as NoticeText.Resource).id)
    }

    @Test
    fun `提交被拒绝是确定没发出 不重取列表`() = runTest {
        var state = ComposerState<String>()
        var unsure = 0
        val actions = ComposerActions<String, Unit>(
            scope = backgroundScope,
            state = { state },
            update = { state = it(state) },
            submit = { _, _, _ -> Outcome.Failed(Failure.Http(403)) },
            afterUnsure = { unsure++ },
        )
        actions.open("a", "正文")
        actions.send(ComposerDraft("", "正文"), "")
        runCurrent()
        assertEquals(0, unsure)
        assertEquals("a", state.target)
        assertEquals("正文", state.text)
        assertFalse(state.sending)
        assertFalse(state.sent)
    }

    @Test
    fun `发帖草稿的标题与正文一起存下并在重开时带回`() = runTest {
        var state = ComposerState<Unit>()
        var stored: ComposerDraft? = null
        val drafts = object : TestComposerDrafts<Unit>(backgroundScope) {
            override fun keyOf(target: Unit): Any = "group"
            override suspend fun read(owner: ComposerDraftOwner, target: Unit) = Outcome.Success(stored)
            override suspend fun write(owner: ComposerDraftOwner, target: Unit, draft: ComposerDraft): Outcome<Unit> {
                stored = draft
                return Outcome.Success(Unit)
            }
            override suspend fun remove(owner: ComposerDraftOwner, target: Unit): Outcome<Unit> {
                stored = null
                return Outcome.Success(Unit)
            }
        }
        val actions = ComposerActions<Unit, Unit>(backgroundScope, { state }, { state = it(state) }, { _, _, _ -> Outcome.Success(Unit) }, drafts = drafts)
        actions.open(Unit)
        runCurrent()
        actions.edit(ComposerDraft("标题", "正文"))
        actions.close()
        runCurrent()
        assertEquals(ComposerDraft("标题", "正文"), stored)
        actions.open(Unit)
        runCurrent()
        assertEquals("标题", state.title)
        assertEquals("正文", state.text)
    }
}
