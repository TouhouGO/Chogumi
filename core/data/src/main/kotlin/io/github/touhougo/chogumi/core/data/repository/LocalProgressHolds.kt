package io.github.touhougo.chogumi.core.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次服务端读取发起的时刻（[LocalProgressHolds.beginRead]）：落库时这之后本地写过的条目不归它管。 */
@JvmInline
value class ReadStart internal constructor(internal val seq: Long)

/**
 * 本人收藏与进度的本地写，和服务端来源的落库之间的唯一规则。
 *
 * **规则**：服务端来源的落库（逐集刷新 [WatchStateStore.refreshEpisodeWatchStates]、逐集记录补拉
 * [WatchStateStore.writeWatchHistories]、单条收藏读取 [CollectionStore.getCollection]、全量快照与同步
 * [CollectionStore.applySnapshot]、首页的收藏合并与逐集灌表 [CollectionStore.writeHomeCollections]）跳过两种条目：
 * - **占用中**：有待提交或在途的进度写（[hold] / [release] 只由 `EpTapCoordinator` 调用）——
 *   否则用户刚点完、提交还没发出去时一次刷新就会把界面打回旧进度；
 * - **读取发起之后本地写过**：占用、释放与每一次本地写（逐集状态与进度的写穿、面板保存、取消收藏都在落库前调
 *   [markWritten]）都记一个递增的写序号。读取在发请求之前取 [beginRead]，回来时序号比它新的条目说明服务端回的是
 *   写之前的样子，不能盖掉本地。
 *
 * 判定与落库在同一把锁里（[applyRead]）：判完到写完之间不会有新的占用插进来。落库方先拿会话提交锁
 * （`TokenStore.commitForSession`）再进这把锁；这把锁里的其余操作只动内存，不等别的锁。
 *
 * 和整份名单的写栅栏（`WriteFence`：读的途中有写就整份重读）不同，这里按条目跳过：一次快照上千部，
 * 点了一部不该让整次同步重来。
 *
 * 写序号按条目记，不淘汰：只有用户动过的条目才有，一次进程里是用户点过、改过的那些作品。
 * 条目不分账号：另一个账号的写只让这一次读取少写几行，下次读取照常补上。
 */
class LocalProgressHolds {
    private val gate = Mutex()
    @Volatile private var held: Set<Int> = emptySet()
    private var seq = 0L
    private val writtenAt = HashMap<Int, Long>()

    suspend fun hold(subjectId: Int) = gate.withLock {
        held = held + subjectId
        writtenAt[subjectId] = ++seq
    }

    suspend fun release(subjectId: Int) = gate.withLock {
        held = held - subjectId
        writtenAt[subjectId] = ++seq
    }

    /** 本人收藏或进度的一次本地写：在同一会话提交里、写本地之前调用。 */
    suspend fun markWritten(subjectId: Int) = gate.withLock { writtenAt[subjectId] = ++seq }

    fun isHeld(subjectId: Int): Boolean = subjectId in held

    /** 服务端读取发请求之前调用；回来后用 [applyRead] 落库。 */
    suspend fun beginRead(): ReadStart = gate.withLock { ReadStart(seq) }

    /** 以 [start] 发起的读取落库：[block] 拿到不能写的条目（占用中或之后写过的），在锁内写完。 */
    suspend fun <T> applyRead(start: ReadStart, block: suspend (protected: Set<Int>) -> T): T = gate.withLock {
        block(held + writtenAt.filterValues { it > start.seq }.keys)
    }
}
