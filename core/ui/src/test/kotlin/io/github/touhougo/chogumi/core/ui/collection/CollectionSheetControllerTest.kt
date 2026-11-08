package io.github.touhougo.chogumi.core.ui.collection

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.notice.NoticeText
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionSaveResult
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.UserCollectionBo
import io.github.touhougo.chogumi.core.res.R
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 取数与保存都由测试手动放行（[works] 发一拍、[saves] 完成）：Unconfined 下协程就地恢复，状态立刻落定，不用等、不用猜时序。
 */
class CollectionSheetControllerTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private var state = CollectionSheetState()
    private val failures = FailureNotices()
    private val works = mutableMapOf<Int, MutableSharedFlow<Load<CollectionWork>>>()
    private val removals = mutableListOf<Int>()
    private val removal = CompletableDeferred<Outcome<Unit>>()
    private val saves = mutableListOf<Pair<CollectionWork, CollectionEdits>>()
    private val replies = mutableListOf<CompletableDeferred<CollectionSaveResult>>()

    private fun workOf(id: Int) = CollectionWork(
        subject = SubjectBo(id = id, type = SubjectType.ANIME, name = "作品$id", nameCn = ""),
        collection = UserCollectionBo(subjectId = id, subjectType = SubjectType.ANIME, type = CollectionType.DOING),
        episodes = Load.Ready(emptyList()),
        watchStates = emptyMap(),
    )

    private fun controller(source: CollectionWorkSource = fetching()) = CollectionSheetController(
        scope = scope,
        read = { state },
        write = { change -> state = change(state) },
        failures = failures,
        source = source,
        remove = { id -> removals += id; removal.await() },
        save = { work, edits ->
            saves += work to edits
            CompletableDeferred<CollectionSaveResult>().also { replies += it }.await()
        },
    )

    private fun fetching() = CollectionWorkSource.Fetch { id -> works.getOrPut(id) { MutableSharedFlow(replay = 1) } }

    private fun emit(id: Int, load: Load<CollectionWork>) = check(works.getValue(id).tryEmit(load))

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `列表页长按：取到之前面板不画 取到了才有作品`() {
        val sheet = controller()
        sheet.open(8)
        assertEquals(CollectionSheetPhase.OPEN, state.phase)
        assertNull(sheet.work(), "作品没到：面板不画（不拿默认值开）")
        emit(8, Load.Ready(workOf(8)))
        assertEquals(workOf(8), sheet.work())
    }

    @Test
    fun `没保存就关掉：再开同一部接着改 换一部就丢`() {
        val sheet = controller()
        val edits = CollectionEdits(rate = 7)
        sheet.open(8)
        emit(8, Load.Ready(workOf(8)))
        sheet.close(edits)
        assertEquals(CollectionSheetPhase.CLOSED, state.phase)
        assertEquals(edits, state.draft)

        sheet.open(8)
        assertEquals(edits, state.draft, "同一部：修改带回来")
        sheet.close(edits)
        sheet.open(9)
        assertNull(state.draft, "换了一部：上一部的修改不能带到这一部")
        assertNull(sheet.work(), "换了一部：上一部的作品不能拿来画这一部")
    }

    @Test
    fun `一次都没取到：面板关上 提示并给重试`() {
        val sheet = controller()
        sheet.open(8)
        emit(8, Load.Failed(Failure.Offline))
        assertEquals(CollectionSheetPhase.CLOSED, state.phase)
        val notice = assertNotNull(failures.current.value)
        assertEquals(NoticeText.Resource(R.string.collection_load_failed), notice.text)
        assertNotNull(notice.retry, "提示带重试：再取一次")
    }

    @Test
    fun `取到之后的失败不抹掉已显示的作品 面板留着`() {
        val sheet = controller()
        sheet.open(8)
        emit(8, Load.Ready(workOf(8)))
        emit(8, Load.Failed(Failure.Offline))
        assertEquals(CollectionSheetPhase.OPEN, state.phase)
        assertEquals(workOf(8), sheet.work())
        val notice = assertNotNull(failures.current.value)
        assertEquals(NoticeText.Reason(Failure.Offline), notice.text)
        assertNotNull(notice.retry)
    }

    @Test
    fun `保存在途时不接受第二笔也不开面板 失败按哪一步提示 面板留着`() {
        val sheet = controller()
        sheet.open(8)
        emit(8, Load.Ready(workOf(8)))
        sheet.save(CollectionEdits(rate = 7))
        sheet.save(CollectionEdits(rate = 8))
        assertEquals(1, saves.size, "在途时第二笔不发")
        sheet.close(CollectionEdits(rate = 7))
        sheet.open(9)
        assertEquals(8, state.subjectId, "在途时不开面板")

        replies[0].complete(CollectionSaveResult.Failed(CollectionSaveResult.Step.PROGRESS, Failure.Offline))
        assertEquals(false, state.saving)
        val notice = assertNotNull(failures.current.value)
        assertEquals(NoticeText.Resource(R.string.progress_save_failed, listOf(Failure.Offline)), notice.text, "进度没存上：说的是进度")
    }

    @Test
    fun `保存成功：开着的面板退场 暂存的同一份修改清掉`() {
        val sheet = controller()
        val edits = CollectionEdits(type = CollectionType.DONE)
        sheet.open(8)
        emit(8, Load.Ready(workOf(8)))
        sheet.save(edits)
        replies[0].complete(CollectionSaveResult.Saved)
        assertEquals(CollectionSheetPhase.SAVED, state.phase, "成功后面板滑下去")
        sheet.close(edits)
        assertEquals(CollectionSheetPhase.CLOSED, state.phase)
        assertNull(state.draft, "存过的修改不再暂存")
    }

    @Test
    fun `详情页：作品读页面的 章节重试走页面`() {
        var retried = 0
        val page = workOf(8)
        val sheet = controller(CollectionWorkSource.OnPage(current = { page }, retryEpisodes = { retried++ }))
        // 「标为看过」不开面板也走同一条保存
        sheet.markDone(8)
        assertEquals(listOf(page to MARKED_DONE), saves)
        replies[0].complete(CollectionSaveResult.Saved)
        assertEquals(CollectionSheetPhase.CLOSED, state.phase, "面板没开：保存成功也不改面板")
        sheet.retryEpisodes()
        assertEquals(1, retried)
    }

    @Test
    fun `列表页标为看过：先取那一部再走同一条保存 在途时不接受第二下 不碰面板`() {
        val sheet = controller()
        sheet.markDone(8)
        assertEquals(true, state.saving, "取数时就算在途：连点第二下不发")
        sheet.markDone(8)
        emit(8, Load.Ready(workOf(8)))
        assertEquals(listOf(workOf(8) to MARKED_DONE), saves)
        replies[0].complete(CollectionSaveResult.Saved)
        assertEquals(false, state.saving)
        assertEquals(CollectionSheetPhase.CLOSED, state.phase)
        assertNull(state.subjectId, "不开面板，也不记成面板上的那一部")
    }

    @Test
    fun `列表页标为看过：那一部取不到就是没存上`() {
        val sheet = controller()
        sheet.markDone(8)
        emit(8, Load.Failed(Failure.Offline))
        assertEquals(emptyList(), saves)
        assertEquals(false, state.saving)
        val notice = assertNotNull(failures.current.value)
        assertEquals(NoticeText.Resource(R.string.collection_save_failed, listOf(Failure.Offline)), notice.text)
    }

    @Test
    fun `移除在途不接受重复写_成功退场并丢弃草稿`() {
        val sheet = controller()
        sheet.open(8)
        emit(8, Load.Ready(workOf(8)))
        sheet.close(CollectionEdits(rate = 7))
        sheet.open(8)
        sheet.remove()
        sheet.remove()
        sheet.save(CollectionEdits(rate = 9))
        assertEquals(listOf(8), removals)
        assertEquals(emptyList(), saves)
        removal.complete(Outcome.Success(Unit))
        assertEquals(CollectionSheetPhase.REMOVED, state.phase)
        assertNull(state.draft)
        sheet.close(CollectionEdits(rate = 7))
        assertNull(state.draft)
        assertEquals(Load.Pending, state.fetched, "移除后清掉旧收藏缓存")
    }

    @Test
    fun `移除失败保留面板并发出失败提示`() {
        val sheet = controller()
        sheet.open(8)
        emit(8, Load.Ready(workOf(8)))
        sheet.remove()
        removal.complete(Outcome.Failed(Failure.Offline))
        assertEquals(CollectionSheetPhase.OPEN, state.phase)
        assertEquals(false, state.saving)
        assertNotNull(sheet.work())
        assertNotNull(failures.current.value)
    }

    private companion object {
        /** 「标为看过」存的那一笔：只改状态，不问要不要一起标完。 */
        val MARKED_DONE = CollectionEdits(type = CollectionType.DONE, finishAll = false)
    }
}
