package io.github.touhougo.chogumi.core.data.progress

import io.github.touhougo.chogumi.core.data.repository.CollectionObserver
import io.github.touhougo.chogumi.core.data.repository.CollectionStore
import io.github.touhougo.chogumi.core.data.repository.LocalProgressHolds
import io.github.touhougo.chogumi.core.data.repository.LocalPublish
import io.github.touhougo.chogumi.core.data.repository.ProgressOrchestrator
import io.github.touhougo.chogumi.core.data.repository.WatchStateStore
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.model.BookUnit
import io.github.touhougo.chogumi.core.model.CollectionModify
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EP_COMMIT_DELAY_MS
import io.github.touhougo.chogumi.core.model.EP_COMMIT_DELAY_NANOS
import io.github.touhougo.chogumi.core.model.EpButtonState
import io.github.touhougo.chogumi.core.model.EpPendingVisual
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.targetOf
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 进度写入的发起处：失败提示只在发起处出现一次（[WIDGET] 是桌面小组件，失败画在那一行上；
 * [NOTIFICATION] 是放送提醒上的「看过了」，没有地方可画，本地回滚就是全部）。
 */
enum class ProgressOrigin { HOME, PROGRESS, DETAILS, WIDGET, NOTIFICATION }

/** [EpTapCoordinator.advanceResolved] 读出的那一部，与读它时的业务日（判断哪集已播）。 */
data class AdvanceTarget(val subject: ProgressSubject, val today: LocalDate)

/** 一次提交失败（本地已回滚）。 */
data class ProgressWriteError(val origin: ProgressOrigin, val subjectId: Int, val failure: Failure)

/** 发起进度写入所需的条目信息。[episodes] 为该条目已知的章节（书籍为空）。 */
data class ProgressSubject(
    val subjectId: Int,
    val subjectType: SubjectType,
    val episodes: List<EpisodeBo>,
    val totalEps: Int = 0,
    val totalVols: Int = 0,
    /** false 时提交前先替用户建收藏（[CollectionType.startedByProgress]，详情页未收藏的条目）。 */
    val hasCollection: Boolean = true,
    /**
     * 书籍固定按这个单位前进（详情页工具栏按规格表只放「卷」，见 [io.github.touhougo.chogumi.core.model.ProgressUnit.bookUnitOverride]）；
     * null 时按收藏里的进度自动判定（[io.github.touhougo.chogumi.core.model.bookUnit]，首页与进度页）。
     */
    val bookUnitOverride: BookUnit? = null,
)

/**
 * 进度点击的**全应用唯一**协调单元（首页、进度页、详情页共用）。
 *
 * 1. **乐观更新**：每次点击立刻写入本地真源（逐集表 / 收藏里的话数卷数），界面不等服务器；
 * 2. **合并提交**：同一条目停手 [EP_COMMIT_DELAY_MS] 后只发一次写入——
 *    「前进一步」由本地当前状态经 [EpisodeTracking.epButtonState] 算出，网格点选以最后一次为准；
 *    点选以 [EpisodeProgressTarget] 区分真实集与空进度，`sort = 0.0` 不会被当成清空；
 * 3. **差量对服务端算**：提交时读取服务端现状再求差（[ProgressOrchestrator.markWatchedUpTo]），
 *    而不是信任窗口开始时的本地快照；
 * 4. **失败回滚**：以服务端状态校正本地（拿不到时退回窗口开始前的快照），并向发起页报错一次；
 *    成功不提示；
 * 5. 在应用级 [scope] 里提交：离开页面不丢写。点击经 [actions] 串行处理，保证连点顺序；
 * 6. **新旧身份**：每个窗口带一个递增的意图身份，条目只认最新的那个。提交途中用户又点了，
 *    旧的一轮完成后既不把结果写穿本地，失败时也不回滚——本地属于更新的意图，由它自己的提交收尾；
 *    判定（[isLatest]）与写入在同一把 [lock] 内原子完成；
 * 7. 特别篇逐集切换（[toggleSpecial]）走同一套乐观写入、占用、失败回滚与报错，只是不合并、立刻提交。
 * 8. **账号**：每次点击取一次当前会话，窗口记住它；乐观写、提交、回滚、发布都只写这个账号。
 *    途中登出或换号，旧账号的这一轮以 [Failure.SessionChanged] 失败、不写进新账号；没登录时点击直接报错。
 *    这里调用的写入都返回 [Outcome]，一个动作失败不会结束 [actions] 的处理循环。
 *
 * 在窗口与提交途中，条目被 [LocalProgressHolds.hold] 占用，服务端来源的刷新不覆盖它。
 */
class EpTapCoordinator(
    private val progressOrchestrator: ProgressOrchestrator,
    private val collectionStore: CollectionStore,
    private val collectionObserver: CollectionObserver,
    private val watchStateStore: WatchStateStore,
    private val progressHolds: LocalProgressHolds,
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope,
    /** 倒计时时钟（生产是 [io.github.touhougo.chogumi.core.model.commitClockNanos]）：每次点击把这一轮的提交时刻定在「现在 + [EP_COMMIT_DELAY_MS]」。 */
    private val clock: () -> Long,
    /**
     * 挂起到提交时刻（[clock] 时基）为止；窗口里再点会取消它、按新的提交时刻重新等。
     * 界面的倒计时也朝同一个时刻走（[EpPendingVisual.commitAtNanos]），走完即提交。
     */
    private val awaitCommit: suspend (commitAtNanos: Long) -> Unit,
) {
    private data class Pending(val session: AccountSession, val visual: EpPendingVisual)

    private val _pending = MutableStateFlow<Map<Int, Pending>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val pending: StateFlow<Map<Int, EpPendingVisual>> = tokenStore.sessionFlow.flatMapLatest { session ->
        _pending.map { entries -> entries.filterValues { it.session == session }.mapValues { it.value.visual } }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _active = MutableStateFlow<Set<Int>>(emptySet())

    /** 有待提交或在途写入的条目；某条目离开此集合 = 它的这轮写入（含失败回滚）已全部结束。 */
    val active: StateFlow<Set<Int>> = _active.asStateFlow()

    private val _creatingCollection = MutableStateFlow<Map<Int, Set<AccountSession>>>(emptyMap())

    /**
     * 当前账号在途写入里要先建收藏的条目（未收藏时点了一集）：从这一笔发出起，到条目离开 [active] 为止。
     * 收藏状态因它而变，详情页的收藏按钮据此显示「保存中」。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val creatingCollection: StateFlow<Set<Int>> = tokenStore.sessionFlow.flatMapLatest { session ->
        _creatingCollection.map { entries -> entries.filterValues { session in it }.keys }
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val _errors = MutableSharedFlow<ProgressWriteError>(extraBufferCapacity = 16)
    val errors: SharedFlow<ProgressWriteError> = _errors.asSharedFlow()

    private sealed interface Action {
        val origin: ProgressOrigin
        val subject: ProgressSubject
        val session: Outcome<AccountSession>
    }

    private data class Advance(
        override val origin: ProgressOrigin,
        override val subject: ProgressSubject,
        override val session: Outcome<AccountSession>,
        val today: LocalDate,
    ) : Action

    private data class WatchUpTo(
        override val origin: ProgressOrigin,
        override val subject: ProgressSubject,
        override val session: Outcome<AccountSession>,
        val target: EpisodeProgressTarget,
    ) : Action

    private data class ToggleSpecial(
        override val origin: ProgressOrigin,
        override val subject: ProgressSubject,
        override val session: Outcome<AccountSession>,
        val episode: EpisodeBo,
    ) : Action

    private sealed interface Window {
        val origin: ProgressOrigin
        val subject: ProgressSubject
        val count: Int

        /** 这一窗口的操作身份：越大越新，由 [intentSeq] 在窗口（含合并后的新窗口）诞生时分配。 */
        val intent: Long

        /** 点击那一刻的会话：这一轮的读写都只认它。 */
        val session: AccountSession
    }

    /** [snapshot]：窗口开始前的本地逐集状态，服务端也拿不到时的回滚底线。 */
    private data class EpisodeWindow(
        override val origin: ProgressOrigin,
        override val subject: ProgressSubject,
        override val count: Int,
        override val intent: Long,
        override val session: AccountSession,
        val target: EpisodeProgressTarget,
        val snapshot: Map<Int, EpisodeStatus>,
    ) : Window

    private data class BookWindow(
        override val origin: ProgressOrigin,
        override val subject: ProgressSubject,
        override val count: Int,
        override val intent: Long,
        override val session: AccountSession,
        val unit: BookUnit,
        val target: Int,
        val snapshot: Int,
    ) : Window

    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val lock = Mutex()
    private val windows = mutableMapOf<Int, Window>()
    private val timers = mutableMapOf<Int, Job>()

    private var intentSeq = 0L

    /**
     * 每个条目**最新**的本地意图（最后一次点击形成的窗口身份），条目空闲时移除。
     * 一轮提交完成后能否发布本地状态，只看它的窗口是不是这里记的那个——全仓唯一的新旧判定，见 [isLatest]。
     */
    private val latestIntent = mutableMapOf<Int, Long>()

    /** 每个条目在途的写入数（正片窗口的提交 + 特别篇切换）；归零且无待提交窗口才解除占用。 */
    private val inFlight = mutableMapOf<Int, Int>()

    private data class SpecialKey(val session: AccountSession, val episodeId: Int)
    private class SpecialFlight(var confirmed: EpisodeStatus, var intent: Long, var count: Int = 0)
    private val specialFlights = mutableMapOf<SpecialKey, SpecialFlight>()

    init {
        scope.launch { for (action in actions) process(action) }
    }

    /** EP 按钮：从本地当前进度前进一步（下一集 / 下一话 / 下一卷）。等待播出、已看完时不做任何事。 */
    fun advance(origin: ProgressOrigin, subject: ProgressSubject, today: LocalDate) {
        actions.trySend(Advance(origin, subject, tokenStore.captureSession(), today))
    }

    /**
     * 先要读数据才知道点的是哪一部的 EP 按钮（小组件：点击只带作品 id）。账号在调用的这一刻取，再 [resolve]
     * （可能等网络与数据库）；读出 null 什么也不做，返回 false。读的期间登出或换号，这次点击以 [Failure.SessionChanged]
     * 作废，不写进下一个账号（见第 8 条）。点击可能刚把进程拉起、本地会话还没恢复：先恢复（恢复过就立刻返回）再取。
     */
    suspend fun advanceResolved(origin: ProgressOrigin, resolve: suspend () -> AdvanceTarget?): Boolean {
        val session = when (val restored = outcomeOf { tokenStore.session() }) {
            is Outcome.Success -> tokenStore.captureSession()
            is Outcome.Failed -> restored
        }
        val target = resolve() ?: return false
        actions.trySend(Advance(origin, target.subject, session, target.today))
        return true
    }

    /** 章节网格：看到 [target]（显式空目标表示清空全部正片记录）。 */
    fun watchUpTo(origin: ProgressOrigin, subject: ProgressSubject, target: EpisodeProgressTarget) {
        actions.trySend(WatchUpTo(origin, subject, tokenStore.captureSession(), target))
    }

    /**
     * 特别篇（SP/OP/ED……）：只切换 [episode] 这一集「看过 ⇄ 无记录」（[EpisodeTracking.specialToggleTarget]），
     * 不计入正片进度，也不参与连点合并——一点即写本地并立刻提交这一集；失败回到点之前的状态并报错一次。
     */
    fun toggleSpecial(origin: ProgressOrigin, subject: ProgressSubject, episode: EpisodeBo) {
        actions.trySend(ToggleSpecial(origin, subject, tokenStore.captureSession(), episode))
    }

    private suspend fun process(action: Action) {
        val id = action.subject.subjectId
        val failure = when (val captured = action.session) {
            is Outcome.Success -> when (val opened = outcomeOf {
                lock.withLock {
                    try {
                        tokenStore.requireCurrent(captured.value)
                        open(action, captured.value)
                    } finally {
                        settle(id)
                    }
                }
            }) {
                is Outcome.Success -> opened.value
                is Outcome.Failed -> opened.failure
            }
            is Outcome.Failed -> captured.failure
        }
        if (failure != null) _errors.emit(ProgressWriteError(action.origin, id, failure))
    }

    /** 处理一次点击（在 [lock] 内调用）；本地乐观写没做成时返回失败原因，这次点击作废。 */
    private suspend fun open(action: Action, session: AccountSession): Failure? {
        val subject = action.subject
        val id = subject.subjectId
        // 另一个账号留下的窗口不能并进来：这次点击从这个账号的本地状态重新开一轮，旧窗口作废。
        val previous = windows[id]?.takeIf { it.session == session }
        val intent = ++intentSeq
        val states = collectionObserver.cachedEpisodeWatchStates(id, session)
        val window: Window = when (action) {
            is ToggleSpecial -> return startSpecialToggle(action, session, states[action.episode.id])
            is WatchUpTo -> episodeWindow(action.origin, subject, action.target, states, previous, intent, session)
            is Advance -> {
                val collection = collectionObserver.cachedCollection(id, session)
                val state = EpisodeTracking.epButtonState(
                    subjectType = subject.subjectType,
                    episodes = subject.episodes,
                    watchStates = states,
                    today = action.today,
                    epStatus = collection?.epStatus ?: 0,
                    volStatus = collection?.volStatus ?: 0,
                    totalEps = subject.totalEps,
                    totalVols = subject.totalVols,
                    bookUnitOverride = subject.bookUnitOverride,
                )
                when (state) {
                    is EpButtonState.Next -> episodeWindow(
                        action.origin,
                        subject,
                        targetOf(state.episode),
                        states,
                        previous,
                        intent,
                        session,
                    )
                    is EpButtonState.BookNext -> BookWindow(
                        origin = action.origin,
                        subject = subject,
                        count = (previous?.count ?: 0) + 1,
                        intent = intent,
                        session = session,
                        unit = state.unit,
                        target = state.current + 1,
                        snapshot = (previous as? BookWindow)?.snapshot ?: state.current,
                    )
                    is EpButtonState.Waiting, EpButtonState.Finished, EpButtonState.Empty, EpButtonState.Loading -> return null
                }
            }
        }
        progressHolds.hold(id)
        _active.update { it + id }
        val written = writeOptimistic(window, states)
        if (written is Outcome.Failed) {
            settle(id)
            return written.failure
        }
        windows[id] = window
        latestIntent[id] = window.intent
        val commitAt = clock() + EP_COMMIT_DELAY_NANOS
        _pending.update { it + (id to Pending(window.session, EpPendingVisual(count = window.count, commitAtNanos = commitAt))) }
        timers.remove(id)?.cancel()
        timers[id] = scope.launch {
            awaitCommit(commitAt)
            commit(id)
        }
        return null
    }

    private fun episodeWindow(
        origin: ProgressOrigin,
        subject: ProgressSubject,
        target: EpisodeProgressTarget,
        states: Map<Int, EpisodeStatus>,
        previous: Window?,
        intent: Long,
        session: AccountSession,
    ) = EpisodeWindow(
        origin = origin,
        subject = subject,
        count = (previous?.count ?: 0) + 1,
        intent = intent,
        session = session,
        target = target,
        snapshot = (previous as? EpisodeWindow)?.snapshot ?: states,
    )

    private suspend fun writeOptimistic(window: Window, states: Map<Int, EpisodeStatus>): Outcome<Unit> {
        val id = window.subject.subjectId
        return when (window) {
            is EpisodeWindow -> when (
                val wanted = progressOrchestrator.targetForWatchedUpTo(window.subject.episodes, states, window.target)
            ) {
                is Outcome.Success -> progressOrchestrator.writeLocal(id, window.subject.episodes, wanted.value, window.session)
                is Outcome.Failed -> wanted
            }
            is BookWindow -> writeBookLocal(window, window.target)
        }
    }

    private suspend fun writeBookLocal(window: BookWindow, value: Int): Outcome<Unit> {
        val id = window.subject.subjectId
        return when (window.unit) {
            BookUnit.VOL -> watchStateStore.syncEpisodeProgress(id, window.session, volStatus = value)
            BookUnit.EP -> watchStateStore.syncEpisodeProgress(id, window.session, epStatus = value)
        }
    }

    private suspend fun commit(subjectId: Int) {
        val window = lock.withLock {
            timers.remove(subjectId)
            windows.remove(subjectId)?.also {
                _pending.update { pending -> pending - subjectId }
                beginSend(it.subject, it.session)
            }
        } ?: return
        var reconciled = false
        val result = progressOrchestrator.withSubjectLock(subjectId) { send(window) { reconciled = true } }
        if (result is Outcome.Failed) rollback(window, reconciled)
        lock.withLock { endSend(subjectId) }
        if (result is Outcome.Failed) {
            _errors.emit(ProgressWriteError(window.origin, subjectId, result.failure))
        }
    }

    /** [window] 是否仍是其条目最新的本地意图（在 [lock] 内调用）。更旧的一轮提交不得发布或回滚本地状态。 */
    private fun isLatest(window: Window): Boolean = latestIntent[window.subject.subjectId] == window.intent

    /** 提交成功后的本地发布：与 [isLatest] 的判定在同一把 [lock] 内原子完成。 */
    private fun publishGate(window: Window) = LocalPublish { write ->
        lock.withLock { if (isLatest(window)) write() }
    }

    /**
     * 特别篇切换：乐观写这一集并占用条目，然后在应用级作用域里提交（在 [lock] 内调用）。
     * 乐观写没做成（途中换了号）时返回失败原因，不提交。
     */
    private suspend fun startSpecialToggle(action: ToggleSpecial, session: AccountSession, before: EpisodeStatus?): Failure? {
        val id = action.subject.subjectId
        val episodeId = action.episode.id
        val want = EpisodeTracking.specialToggleTarget(before)
        progressHolds.hold(id)
        _active.update { it + id }
        val written = watchStateStore.syncSingleEpisodeWatchState(id, episodeId, want, session)
        if (written is Outcome.Failed) {
            settle(id)
            return written.failure
        }
        val key = SpecialKey(session, episodeId)
        val intent = ++intentSeq
        val flight = specialFlights.getOrPut(key) { SpecialFlight(before ?: EpisodeStatus.NONE, intent) }
        flight.intent = intent
        flight.count++
        beginSend(action.subject, session)
        // UNDISPATCHED：在本次处理内就排进条目锁的队列（Mutex 先到先得），
        // 连点同一集时提交顺序与点击顺序一致，服务端的终态与本地一致。
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val result = progressOrchestrator.withSubjectLock(id) {
                val created = ensureCollected(action.subject, session, LocalPublish.Immediate)
                if (created is Outcome.Failed) return@withSubjectLock created
                watchStateStore.updateEpisodeStatus(episodeId, want, expectedSession = session).also { updated ->
                    lock.withLock { if (updated is Outcome.Success) flight.confirmed = want }
                }
            }
            try {
                // 这一集最新的一次失败：同正片的失败回滚（[rollback]），先只读回读服务端（响应途中断网时服务端可能已改），
                // 回读到了以服务端这一集为准并当作新的确认值，回读不到再退回确认值。回读不落库，不碰同作品其它在途的集。
                val latest = result is Outcome.Failed && result.failure != Failure.SessionChanged && lock.withLock { flight.intent == intent }
                val server = if (latest) watchStateStore.fetchServerEpisodeWatchStates(id, session) else null
                lock.withLock {
                    if (result is Outcome.Failed && flight.intent == intent) {
                        if (server is Outcome.Success) flight.confirmed = server.value[episodeId] ?: EpisodeStatus.NONE
                        watchStateStore.syncSingleEpisodeWatchState(id, episodeId, flight.confirmed, session)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    lock.withLock {
                        flight.count--
                        if (flight.count == 0) specialFlights.remove(key)
                        endSend(id)
                    }
                }
            }
            if (result is Outcome.Failed) {
                _errors.emit(ProgressWriteError(action.origin, id, result.failure))
            }
        }
        return null
    }

    /** 记一次在途写入（在 [lock] 内调用）；要先建收藏的记进 [creatingCollection]。 */
    private fun beginSend(subject: ProgressSubject, session: AccountSession) {
        val id = subject.subjectId
        inFlight[id] = (inFlight[id] ?: 0) + 1
        if (!subject.hasCollection) _creatingCollection.update { it + (id to (it[id].orEmpty() + session)) }
    }

    /**
     * 一次写入结束（在 [lock] 内调用）：条目既无在途写入、也无待提交窗口时解除占用，这轮写入到此结束；
     * 否则重新占用——失败回滚会先解除占用去拉服务端状态，其余写入还没完，不能让服务端来源的刷新盖掉它们。
     */
    private suspend fun endSend(subjectId: Int) {
        val left = (inFlight[subjectId] ?: 1) - 1
        if (left > 0) inFlight[subjectId] = left else inFlight.remove(subjectId)
        settle(subjectId)
    }

    /** 按条目现在还有没有在途写入或待提交窗口，重新占用或结束这一轮（在 [lock] 内调用）。 */
    private suspend fun settle(subjectId: Int) {
        if (inFlight.containsKey(subjectId) || windows.containsKey(subjectId)) {
            progressHolds.hold(subjectId)
        } else {
            progressHolds.release(subjectId)
            latestIntent.remove(subjectId)
            _creatingCollection.update { it - subjectId }
            _active.update { it - subjectId }
        }
    }

    private suspend fun send(window: Window, onReconciled: () -> Unit): Outcome<Unit> {
        val id = window.subject.subjectId
        val created = ensureCollected(window.subject, window.session, publishGate(window), onReconciled)
        if (created is Outcome.Failed) return created
        return when (window) {
            is EpisodeWindow -> progressOrchestrator.markWatchedUpTo(
                id, window.target,
                localPublish = publishGate(window), session = window.session,
            )
            is BookWindow -> collectionStore.updateCollection(
                id,
                when (window.unit) {
                    BookUnit.VOL -> CollectionModify(volStatus = window.target)
                    BookUnit.EP -> CollectionModify(epStatus = window.target)
                },
                expectedSession = window.session,
                localPublish = publishGate(window),
                onReconciled = onReconciled,
            )
        }
    }

    /** 还没收藏的条目先替用户建收藏（[CollectionType.startedByProgress]）；已收藏的什么也不做。 */
    private suspend fun ensureCollected(
        subject: ProgressSubject,
        session: AccountSession,
        localPublish: LocalPublish,
        onReconciled: () -> Unit = {},
    ): Outcome<Unit> =
        if (subject.hasCollection) {
            Outcome.Success(Unit)
        } else {
            collectionStore.updateCollection(
                subject.subjectId, CollectionModify(type = CollectionType.startedByProgress),
                expectedSession = session, localPublish = localPublish, onReconciled = onReconciled,
            )
        }

    /**
     * 失败回滚：先以服务端状态校正；服务端也拿不到时退回窗口开始前的快照。提交途中又点了（[isLatest] 不成立）时什么也不做，
     * 本地留给更新的意图。取服务端状态在 [lock] 外：网络慢的时候，别的条目的点击不能排在它后面。
     */
    private suspend fun rollback(window: Window, reconciled: Boolean) {
        val id = window.subject.subjectId
        when (window) {
            is EpisodeWindow -> {
                val latest = lock.withLock {
                    isLatest(window).also { if (it) progressHolds.release(id) }
                }
                if (!latest) return
                // 被占用时（期间又点了）它只读不落库
                val refreshed = watchStateStore.refreshEpisodeWatchStates(id, window.session)
                lock.withLock {
                    if (refreshed is Outcome.Failed && isLatest(window)) {
                        progressHolds.hold(id)
                        progressOrchestrator.writeLocal(id, window.subject.episodes, window.snapshot, window.session)
                    }
                }
            }
            is BookWindow -> lock.withLock {
                if (!reconciled && isLatest(window)) writeBookLocal(window, window.snapshot)
            }
        }
    }
}
