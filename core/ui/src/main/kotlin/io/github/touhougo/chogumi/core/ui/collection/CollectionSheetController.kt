package io.github.touhougo.chogumi.core.ui.collection

import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.collectSection
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.CollectionEdits
import io.github.touhougo.chogumi.core.model.CollectionSaveResult
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.CollectionWork
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 收藏面板此刻在哪一步：关着、开着、保存成功后正在退场（面板据此滑下去，滑完回到关着）。 */
enum class CollectionSheetPhase { CLOSED, OPEN, SAVED, REMOVED }

/** 面板的一次写入：保存和移除共享忙态，只接受一笔。 */
enum class CollectionSheetWrite { SAVE, REMOVE }

/**
 * 收藏面板在页面状态里的一份（详情页、进度页、首页各一份），只由 [CollectionSheetController] 改。
 *
 * @property subjectId 开着的是哪一部；关上后留着，[draft] 属于它。
 * @property draft 没保存就关掉时留下的修改：再打开同一部接着改；离开页面、或打开了另一部才丢。面板开着时修改在面板里。
 * @property writing 写入请求在途（面板上的，或不开面板的「标为看过」）：不接受第二次保存，也不开面板。
 * @property fetched 列表页长按时现取的那一部（[CollectionWorkSource.Fetch]）；详情页的作品就是页面本身，不经这里。
 */
data class CollectionSheetState(
    val subjectId: Int? = null,
    val phase: CollectionSheetPhase = CollectionSheetPhase.CLOSED,
    val draft: CollectionEdits? = null,
    val writing: CollectionSheetWrite? = null,
    val fetched: Load<CollectionWork> = Load.Pending,
) {
    val saving: Boolean get() = writing != null
    val removing: Boolean get() = writing == CollectionSheetWrite.REMOVE

    /** 面板在屏上（开着，或保存成功后正在退场）。 */
    val isShown: Boolean get() = phase != CollectionSheetPhase.CLOSED
}

/** 面板改的那一部从哪来。 */
sealed interface CollectionWorkSource {
    /** 详情页：作品、收藏、章节本来就在页面状态里，面板读那一份（[current]，还没到齐时为 null）；章节重试也是页面的。 */
    class OnPage(val current: () -> CollectionWork?, val retryEpisodes: () -> Unit) : CollectionWorkSource

    /** 列表页（进度、首页「继续」、有海报的页面）：长按哪部才取哪部，取到的放进 [CollectionSheetState.fetched]。 */
    class Fetch(val observe: (subjectId: Int) -> Flow<Load<CollectionWork>>) : CollectionWorkSource
}

/**
 * 收藏面板的开、关、保存——详情页的 FAB、进度页与首页的长按、海报环上的「管理」共用这一份；
 * 不开面板的「标为看过」（[markDone]）也经这里存，全 App 只有这一个写入点。
 *
 * 状态是调用方页面状态里的一份 [CollectionSheetState]，经 [read] / [write] 读写，ViewModel 的状态仍只有一份。
 * 保存失败经 [failures] 提示（面板留着、修改还在面板里）；成功后先调 [onSaved]（详情页回读收藏、FAB 的忙态交给回读），再让面板退场。
 *
 * @param save 存一张表（[io.github.touhougo.chogumi.core.model.CollectionEditing.save]）。
 */
class CollectionSheetController(
    private val scope: CoroutineScope,
    private val read: () -> CollectionSheetState,
    private val write: ((CollectionSheetState) -> CollectionSheetState) -> Unit,
    private val failures: FailureNotices,
    private val source: CollectionWorkSource,
    private val save: suspend (CollectionWork, CollectionEdits) -> CollectionSaveResult,
    private val remove: suspend (Int) -> Outcome<Unit>,
    private val onSaved: () -> Unit = {},
) {
    private var fetchJob: Job? = null

    /** 面板改的那一部：数据到齐了才有。 */
    fun work(): CollectionWork? = when (source) {
        is CollectionWorkSource.OnPage -> source.current()
        is CollectionWorkSource.Fetch -> read().fetched.valueOrNull()
    }

    /** 打开 [subjectId] 的面板。保存在途时不开（与详情页 FAB 忙时同一条规矩）；换了一部，上一部暂存的修改就丢掉。 */
    fun open(subjectId: Int) {
        if (read().saving) return
        write { state ->
            state.copy(
                subjectId = subjectId,
                phase = CollectionSheetPhase.OPEN,
                draft = state.draft.takeIf { state.subjectId == subjectId },
                fetched = if (state.subjectId == subjectId) state.fetched else Load.Pending,
            )
        }
        if (source is CollectionWorkSource.Fetch) fetch(source, subjectId)
    }

    /**
     * 面板滑完退场后到这里（点空白处、返回、往下拖，或保存成功后的退场），[edits] 是面板上此刻的修改。
     * 用户自己关的：还有没保存的修改就留着，下次打开接着改；保存成功后的退场：修改已经存了，不留。
     */
    fun close(edits: CollectionEdits) {
        val editor = work()?.editor
        write { state ->
            state.copy(
                phase = CollectionSheetPhase.CLOSED,
                fetched = if (state.phase == CollectionSheetPhase.REMOVED) Load.Pending else state.fetched,
                draft = edits.takeIf { state.phase == CollectionSheetPhase.OPEN && editor?.isDirty(it) == true },
            )
        }
        fetchJob?.cancel()
    }

    /** 章节取不到时进度区里的重试。 */
    fun retryEpisodes() {
        when (source) {
            is CollectionWorkSource.OnPage -> source.retryEpisodes()
            is CollectionWorkSource.Fetch -> read().subjectId?.let { fetch(source, it) }
        }
    }

    /** 确认移除后才调用：失败保留面板；成功退场，草稿和已移除收藏的取数缓存一并丢弃。 */
    fun remove() {
        val work = work() ?: return
        if (read().saving || !read().isShown || work.collection == null) return
        write { it.copy(writing = CollectionSheetWrite.REMOVE) }
        scope.launch {
            when (val result = remove(work.subject.id)) {
                is Outcome.Failed -> {
                    write { it.copy(writing = null) }
                    failures.post(R.string.collection_remove_failed, result.failure)
                }
                is Outcome.Success -> {
                    fetchJob?.cancel()
                    onSaved()
                    write { state ->
                        val same = state.subjectId == work.subject.id
                        state.copy(
                            writing = null,
                            phase = if (same && state.isShown) CollectionSheetPhase.REMOVED else state.phase,
                            draft = state.draft.takeUnless { same },
                            fetched = if (same && !state.isShown) Load.Pending else state.fetched,
                        )
                    }
                }
            }
        }
    }

    /**
     * 保存面板上的 [edits]。作品还没到齐、或上一笔还在途时不接受：第二笔会和第一笔抢在途标记。
     */
    fun save(edits: CollectionEdits) {
        val work = work() ?: return
        if (read().saving) return
        write { it.copy(writing = CollectionSheetWrite.SAVE) }
        scope.launch { commit(work, edits) }
    }

    /**
     * 「标为看过」（章节按钮完结后的那一下）：不开面板，把 [subjectId] 存成看过、进度不动，与面板保存同一条路、同一句失败提示。
     * 详情页存页面上的那一部；列表页先取这一部（有缓存就用缓存），取不到就是没存上。上一笔还在途时不接受。
     */
    fun markDone(subjectId: Int) {
        if (read().saving) return
        when (source) {
            is CollectionWorkSource.OnPage -> {
                val work = source.current() ?: return
                write { it.copy(writing = CollectionSheetWrite.SAVE) }
                scope.launch { commit(work, MARK_DONE) }
            }
            is CollectionWorkSource.Fetch -> {
                write { it.copy(writing = CollectionSheetWrite.SAVE) }
                scope.launch {
                    when (val fetched = source.observe(subjectId).settled()) {
                        is Outcome.Success -> commit(fetched.value, MARK_DONE)
                        is Outcome.Failed -> {
                            write { it.copy(writing = null) }
                            failures.post(R.string.collection_save_failed, fetched.failure)
                        }
                    }
                }
            }
        }
    }

    /** 存 [edits]（[CollectionSheetState.saving] 已置上）：失败提示；成功后这一部的面板开着就退场，暂存的正是这一份就清掉。 */
    private suspend fun commit(work: CollectionWork, edits: CollectionEdits) {
        when (val result = save(work, edits)) {
            is CollectionSaveResult.Failed -> {
                write { it.copy(writing = null) }
                val message = when (result.step) {
                    CollectionSaveResult.Step.RECORD -> R.string.collection_save_failed
                    CollectionSaveResult.Step.PROGRESS -> R.string.progress_save_failed
                }
                failures.post(message, result.failure)
            }
            CollectionSaveResult.Saved -> {
                // 先交出忙态（详情页的 FAB 改由回读撑着），再落下 saving：两拍之间 FAB 不会先闪回旧状态
                onSaved()
                write { state ->
                    val same = state.subjectId == work.subject.id
                    state.copy(
                        writing = null,
                        phase = if (same && state.phase == CollectionSheetPhase.OPEN) CollectionSheetPhase.SAVED else state.phase,
                        // 保存途中面板被关掉、修改暂存着：存的正是这一份时一并清掉
                        draft = state.draft?.takeUnless { same && it == edits },
                    )
                }
            }
        }
    }

    /**
     * 跟着 [subjectId] 的取数走（已显示的不被之后的失败抹掉）。一次都没取到就失败：面板不开，提示并给重试。
     */
    private fun fetch(source: CollectionWorkSource.Fetch, subjectId: Int) {
        fetchJob?.cancel()
        fetchJob = scope.launch {
            collectSection(
                source = source.observe(subjectId),
                failures = failures,
                retry = { open(subjectId) },
                get = { it.fetched },
                set = { copy(fetched = it) },
                update = write,
                alongside = { load ->
                    if (load is Load.Failed && fetched is Load.Failed) {
                        failures.post(R.string.collection_load_failed, retry = { open(subjectId) })
                        fetchJob?.cancel()
                        copy(phase = CollectionSheetPhase.CLOSED)
                    } else this
                },
            )
        }
    }
}

/** 「标为看过」存的那一笔：只改状态；进度已经到头，不再问要不要一起标完。 */
private val MARK_DONE = CollectionEdits(type = CollectionType.DONE, finishAll = false)
