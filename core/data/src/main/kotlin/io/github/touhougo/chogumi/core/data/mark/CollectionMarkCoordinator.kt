package io.github.touhougo.chogumi.core.data.mark

import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.CollectionStore
import io.github.touhougo.chogumi.core.data.repository.ProgressOrchestrator
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.COLLECTION_MARK_WINDOW_NANOS
import io.github.touhougo.chogumi.core.model.CollectionMarkFailure
import io.github.touhougo.chogumi.core.model.CollectionMarker
import io.github.touhougo.chogumi.core.model.CollectionMarks
import io.github.touhougo.chogumi.core.model.CollectionModify
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.MarkOrigin
import io.github.touhougo.chogumi.core.model.PendingMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 海报长按标记的**全应用唯一**协调单元（探索、搜索、每日放送、详情页关联、角色人物作品、资料页收藏共用）。
 *
 * 1. **窗口**：标了状态先进 [COLLECTION_MARK_WINDOW_NANOS] 的窗口，海报上是可撤销的待提交胶囊，窗口走完才写服务端；
 *    窗口里再标同一部只改目标、重新计时，最终只写一次；标回原来的状态等于撤销。
 * 2. **全 App 至多一笔待提交**：去标另一部时，上一部立刻提交，不等它的窗口。
 * 3. **提交**：只带状态（[CollectionStore.updateCollection]，未收藏时它回退 POST 创建），与章节写入共用条目锁，
 *    同一部的写入按顺序到服务端；账号按标的那一刻的会话，途中换号就失败、不写进新账号。
 * 4. **提交途中**海报已显示新状态（[committing] 叠在本地收藏表上），成功后等本地表写进来再撤掉叠加，角标不会闪回旧状态；
 *    失败撤掉叠加（回到原状态），向发起页报错一次（[failures]），成功不提示。
 * 5. 在应用级 [scope] 里提交：离开页面不丢写。操作经 [actions] 串行处理，保证先后。
 */
class CollectionMarkCoordinator(
    private val collectionStore: CollectionStore,
    private val collectionObserver: CollectionObserver,
    private val tokenStore: TokenStore,
    private val progressOrchestrator: ProgressOrchestrator,
    private val scope: CoroutineScope,
    /** 倒计时时钟（生产是 [io.github.touhougo.chogumi.core.model.commitClockNanos]）。 */
    private val clock: () -> Long,
    /** 挂起到提交时刻（[clock] 时基）为止；窗口里改选、撤销或被下一部顶掉都会取消它。界面的倒计时朝同一个时刻走。 */
    private val awaitCommit: suspend (commitAtNanos: Long) -> Unit,
) : CollectionMarker {
    /** 窗口里的那一笔，连同发起页与标的那一刻的会话。 */
    private class Window(val origin: MarkOrigin, val mark: PendingMark, val session: AccountSession)

    /** 一笔在途提交：[seq] 区分同一部前后两次提交，旧的那次结束时不撤掉新的叠加。 */
    private data class InFlight(val seq: Long, val type: CollectionType, val session: AccountSession)

    private sealed interface Action {
        data class Mark(val origin: MarkOrigin, val subjectId: Int, val type: CollectionType, val session: Outcome<AccountSession>) : Action
        data class Undo(val subjectId: Int, val session: Outcome<AccountSession>) : Action
        data class Due(val window: Window) : Action
    }

    private data class Local(val session: AccountSession?, val types: Map<Int, CollectionType> = emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val local: StateFlow<Local> = tokenStore.sessionFlow.flatMapLatest { session ->
        if (!session.authenticated) flowOf(Local(session)) else {
            collectionObserver.observeCollectionTypes(session).map { Local(session, it) }.onStart { emit(Local(session)) }
        }
    }.stateIn(scope, SharingStarted.Eagerly, Local(null))
    private val window = MutableStateFlow<Window?>(null)
    private val committing = MutableStateFlow<Map<Int, InFlight>>(emptyMap())
    private val _failures = MutableSharedFlow<CollectionMarkFailure>(extraBufferCapacity = 16)
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private var timer: Job? = null
    private var commitSeq = 0L

    override val marks: StateFlow<CollectionMarks> = combine(local, committing, window) { local, committing, window ->
        CollectionMarks(
            types = local.types + committing.filterValues { it.session == local.session }.mapValues { it.value.type },
            pending = window?.takeIf { it.session == local.session }?.mark,
        )
    }.stateIn(scope, SharingStarted.Eagerly, CollectionMarks())

    override val failures: Flow<CollectionMarkFailure> = _failures.asSharedFlow()

    init {
        scope.launch { for (action in actions) process(action) }
    }

    override fun mark(origin: MarkOrigin, subjectId: Int, type: CollectionType) {
        actions.trySend(Action.Mark(origin, subjectId, type, tokenStore.captureSession()))
    }

    override fun undo(subjectId: Int) {
        actions.trySend(Action.Undo(subjectId, tokenStore.captureSession()))
    }

    private suspend fun process(action: Action) {
        when (action) {
            is Action.Mark -> open(action)
            is Action.Undo -> (action.session as? Outcome.Success)?.value?.let { session ->
                outcomeOf { tokenStore.requireCurrent(session) }.let { current ->
                    if (current is Outcome.Success && window.value?.let { it.mark.subjectId == action.subjectId && it.session == session } == true) close()
                }
            }
            is Action.Due -> window.value?.takeIf { it === action.window }?.let(::commit)
        }
    }

    private suspend fun open(action: Action.Mark) {
        val captured = action.session
        val current = when (captured) {
            is Outcome.Success -> outcomeOf { tokenStore.requireCurrent(captured.value); captured.value }
            is Outcome.Failed -> captured
        }
        val session = when (current) {
            is Outcome.Success -> current.value
            is Outcome.Failed -> {
                _failures.emit(CollectionMarkFailure(action.origin, action.subjectId, current.failure))
                return
            }
        }
        val previous = window.value
        if (previous != null && previous.session != session) close()
        else if (previous != null && previous.mark.subjectId != action.subjectId) commit(previous)
        val type = when (val read = outcomeOf {
            committing.value[action.subjectId]?.takeIf { it.session == session }?.type
                ?: collectionObserver.cachedCollection(action.subjectId, session)?.type
        }) {
            is Outcome.Success -> read.value
            is Outcome.Failed -> {
                _failures.emit(CollectionMarkFailure(action.origin, action.subjectId, read.failure))
                return
            }
        }
        // 窗口里改选：仍与同一账号窗口开始前的状态比较，标回它就是撤销。
        if (action.type == type) {
            if (window.value != null) close()
            return
        }
        val mark = PendingMark(action.subjectId, action.type, clock() + COLLECTION_MARK_WINDOW_NANOS)
        val opened = Window(action.origin, mark, session)
        val published = outcomeOf { tokenStore.commitForSession(session) { window.value = opened } }
        if (published is Outcome.Failed) {
            _failures.emit(CollectionMarkFailure(action.origin, action.subjectId, published.failure))
            return
        }
        timer?.cancel()
        timer = scope.launch {
            awaitCommit(mark.commitAtNanos)
            actions.send(Action.Due(opened))
        }
    }

    private fun close() {
        timer?.cancel()
        timer = null
        window.value = null
    }

    private fun commit(pending: Window) {
        close()
        val subjectId = pending.mark.subjectId
        val type = pending.mark.type
        val flight = InFlight(++commitSeq, type, pending.session)
        committing.update { it + (subjectId to flight) }
        scope.launch {
            val result = progressOrchestrator.withSubjectLock(subjectId) {
                collectionStore.updateCollection(subjectId, CollectionModify(type = type), expectedSession = pending.session)
            }
            if (result is Outcome.Success) {
                // 本地表的写入已提交，观察流稍后才送到：等它送到再撤叠加，角标不在中间闪回旧状态。
                // 途中换了账号时本地表换成新账号的，不会等到，过时限就撤。
                withTimeoutOrNull(LOCAL_ECHO_TIMEOUT_MS) {
                    local.first { it.session != pending.session || it.types[subjectId] == type }
                }
            }
            committing.update { current -> if (current[subjectId] == flight) current - subjectId else current }
            if (result is Outcome.Failed) {
                _failures.emit(CollectionMarkFailure(pending.origin, subjectId, result.failure))
            }
        }
    }

    private companion object {
        /** 等本地表送到新状态的上限（设计决定）：正常是几帧，只为换号这类永远等不到的情况兜底。 */
        const val LOCAL_ECHO_TIMEOUT_MS = 3_000L
    }
}
