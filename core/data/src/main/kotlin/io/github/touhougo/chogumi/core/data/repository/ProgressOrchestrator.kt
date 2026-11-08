package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.settled
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeProgressTarget
import io.github.touhougo.chogumi.core.model.EpisodeStatus
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 进度写入编排：**进度只有「看到某一集」一个入口**（[markWatchedUpTo]）。
 *
 * ## 语义
 *
 * 传入的目标是「看到某一集」或「空进度」：
 * - 目标集及之前的**全部**正片（`type == 0`）标为看过；
 * - 目标之后的**全部**正片清为无记录；
 * - 非正片（SP/OP/ED）任何路径都不参与；
 * - 各发**一次**批量请求（增一次、减一次），不是逐集循环。
 *
 * 这是**状态式**而非增量式：不依赖「旧值」做区间推算。原因见下。
 *
 * ## 为什么不用 (old, new] 区间、也不按「+1」标下一集
 *
 * 服务端的条目级 `epStatus` 是「**看过的记录条数**」，不是「看到第几集」。
 * 用户记录稀疏时（如实际标过 {3,5,7} 而 `epStatus = 3`），把它当 sort 阈值用会出错：
 * 按 `(3,8]` 只补 4..8 的话，合并后服务端 `epStatus` 变成 6 而不是 8——
 * 点第 8 集却显示「看到第 6 集」。
 *
 * 用 `epStatus + 1` 当集号去标下一集更糟：
 * 记录稀疏时**会标错集**。下一集只由 [EpisodeTracking.nextEpisode]
 * （唯一判定点，读逐集状态）算出下一集的 sort；界面点击经 `EpTapCoordinator`
 * 乐观写本地、停手合并后走本方法提交。
 *
 * ## 已知语义（保持现状，勿擅改）
 *
 * - **批量 PATCH 的 404 不回退创建收藏**：该端点 spec 的 404 原文是「条目不存在」，
 *   不指向「条目未收藏」；本方法用于改写已有收藏的进度，未收藏时前置条件本就不成立。
 *   404 一律原样透传。
 * - **目标集合为空时不发任何网络写**，直接写本地缓存并返回成功（既有语义）。
 * - **收藏计数与正片锚点分开**：`epStatus` 只数看过的逐集记录，特别篇也算；正片操作保留想看与抛弃，
 *   不把它们计入已看。服务端回读可校正其他客户端的并发修改。
 *
 * 章节写入接口有频率限制（每用户每 5 分钟 10 次），
 * 因此本方法**每次调用只发一组批量请求**（增/减各一次），不得改成逐集循环。
 */
class ProgressOrchestrator(
    private val subjectRepository: SubjectRepository,
    private val watchStateStore: WatchStateStore,
) {
    private val subjectLocks = ConcurrentHashMap<Int, Mutex>()

    private class SubjectLockContext(val heldSubjectIds: Set<Int>) : CoroutineContext.Element {
        companion object Key : CoroutineContext.Key<SubjectLockContext>
        override val key: CoroutineContext.Key<SubjectLockContext> = Key
    }

    /**
     * 按 [subjectId] 互斥加锁执行写入操作，同一条目从任何页面写入都串行。
     * 支持同协程内的可重入，防止嵌套加锁死锁。
     */
    suspend fun <T> withSubjectLock(subjectId: Int, action: suspend () -> T): T {
        val currentContext = kotlin.coroutines.coroutineContext[SubjectLockContext]
        if (currentContext != null && subjectId in currentContext.heldSubjectIds) {
            return action()
        }
        val mutex = subjectLocks.computeIfAbsent(subjectId) { Mutex() }
        return mutex.withLock {
            val newHeld = (currentContext?.heldSubjectIds ?: emptySet()) + subjectId
            withContext(SubjectLockContext(newHeld)) {
                action()
            }
        }
    }

    /**
     * 把逐集记进度的条目（动画、三次元）设为「看到 [target]」——**全 App 唯一的逐集进度写入口**。书籍的话数卷数随收藏 PATCH
     * 写，不经这里。
     *
     * 先拉章节和**服务端**逐集状态（GET 不计写入限额，不落库——本地可能正持有乐观状态），状态式过滤需变化的章节再批量请求。
     * 目标集已不在拉到的章节里（服务端删了或换了那一集）时以 [Failure.BadResponse] 结束，什么也不写。
     *
     * 读、写与本地写穿都固定在调用方动手时取的 [session]：途中登出或换号就以
     * [io.github.touhougo.chogumi.core.foundation.Failure.SessionChanged] 结束，不写进另一个账号。
     */
    suspend fun markWatchedUpTo(
        subjectId: Int,
        target: EpisodeProgressTarget,
        localPublish: LocalPublish,
        session: AccountSession,
    ): Outcome<Unit> = withSubjectLock(subjectId) {
        val episodes = when (val fetched = subjectRepository.getEpisodes(subjectId).settled()) {
            is Outcome.Success -> fetched.value
            is Outcome.Failed -> return@withSubjectLock fetched
        }
        val states = when (val fetched = watchStateStore.fetchServerEpisodeWatchStates(subjectId, session)) {
            is Outcome.Success -> fetched.value
            is Outcome.Failed -> return@withSubjectLock fetched
        }
        val wanted = when (val derived = targetForWatchedUpTo(episodes, states, target)) {
            is Outcome.Success -> derived.value
            is Outcome.Failed -> return@withSubjectLock derived
        }
        applyWatchStates(
            subjectId = subjectId,
            episodes = episodes,
            current = states,
            target = wanted,
            localPublish = localPublish,
            session = session,
        )
    }

    /**
     * 状态式批量写入：**全仓唯一的进度写核心**，只对状态需要变化的集发批量；非正片不参与。
     *
     * [target] 是「每集应该是什么状态」的完整目标（按 episodeId 索引，缺省即
     * [EpisodeStatus.NONE]）。与 [current] 相同的集**不进请求**——这既是省流，
     * 也是本方法满足章节接口频率限制（每用户 5 分钟 10 次）的前提。
     *
     * 按目标状态分组各发**一次**批量请求（不是逐集循环）。分组顺序固定为
     * 看过 → 无记录 → 想看 → 抛弃，保证同一输入下请求序列可预期（测试据此断言）。
     *
     * 成功后经 [writeLocal] 写穿本地：逐集行与条目级进度**都从 [target] 派生**，不另算一遍；
     * 写穿这一步交给 [localPublish] 裁决，被更新的本地意图抢占时它可以丢弃这次写入。会话规则同 [markWatchedUpTo]。
     */
    internal suspend fun applyWatchStates(
        subjectId: Int,
        episodes: List<EpisodeBo>,
        current: Map<Int, EpisodeStatus>,
        target: Map<Int, EpisodeStatus>,
        localPublish: LocalPublish,
        session: AccountSession,
    ): Outcome<Unit> = withSubjectLock(subjectId) {
        val mains = EpisodeTracking.mainEpisodes(episodes)
        val changes = mains
            .mapNotNull { ep ->
                val now = current[ep.id] ?: EpisodeStatus.NONE
                val want = target[ep.id] ?: EpisodeStatus.NONE
                (ep to want).takeIf { want != now }
            }
            .groupBy({ it.second }, { it.first })

        for (status in BATCH_ORDER) {
            val toWrite = changes[status] ?: continue
            val result = watchStateStore.updateEpisodeBatch(
                subjectId = subjectId,
                episodeIds = toWrite.map { it.id },
                status = status,
                expectedSession = session,
            )
            if (result is Outcome.Failed) return@withSubjectLock result
        }

        var written: Outcome<Unit> = Outcome.Success(Unit)
        localPublish.publish { written = writeLocal(subjectId, episodes, target, session) }
        written
    }

    /**
     * 把目标逐集状态写进本地真源（不发请求）：乐观更新、提交成功后的写穿、失败回滚共用这一处。
     *
     * 收藏里的已看记录数从完整逐集状态派生，包含特别篇但不把想看或抛弃算成已看。
     *
     * 只写 [session] 这个账号；它已不是当前会话时什么也不写，返回失败。
     */
    suspend fun writeLocal(
        subjectId: Int,
        episodes: List<EpisodeBo>,
        target: Map<Int, EpisodeStatus>,
        session: AccountSession,
    ): Outcome<Unit> = watchStateStore.syncEpisodeWatchStates(subjectId, episodes, target, session)

    /**
     * 状态式批量写入的**目标推导**：由 [EpisodeTracking.statesAfterWatchedUpTo]（唯一判定点）算出「看到 [target]」后每个正片
     * 应处的状态。目标集不在 [episodes] 的正片里是 [Failure.BadResponse]：不当成清空。
     *
     * 纯计算，不发请求；[markWatchedUpTo] 与乐观写本地的 `EpTapCoordinator` 共用它。
     */
    fun targetForWatchedUpTo(
        episodes: List<EpisodeBo>,
        current: Map<Int, EpisodeStatus>,
        target: EpisodeProgressTarget,
    ): Outcome<Map<Int, EpisodeStatus>> =
        EpisodeTracking.statesAfterWatchedUpTo(episodes, current, target)
            ?.let { Outcome.Success(it) }
            ?: Outcome.Failed(Failure.BadResponse)
}

/**
 * 批量请求的发送顺序：看过 → 无记录 → 想看 → 抛弃。
 *
 * 固定顺序只为让同一输入产生可预期的请求序列（测试与日志据此断言），
 * 无业务优先级含义。
 */
private val BATCH_ORDER = listOf(
    EpisodeStatus.DONE,
    EpisodeStatus.NONE,
    EpisodeStatus.WISH,
    EpisodeStatus.DROPPED,
)
