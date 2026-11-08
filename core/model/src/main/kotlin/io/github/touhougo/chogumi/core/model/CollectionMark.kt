package io.github.touhougo.chogumi.core.model

import io.github.touhougo.chogumi.core.foundation.Failure
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 海报长按标了状态之后，留多久可撤销才提交（毫秒）：**本项目设计决定（not in M3）**，原型 `poster-long-press.html`。
 * 比章节的 [EP_COMMIT_DELAY_MS] 长：那个是合并连点，这个要留出看清、再撤的时间。
 */
private const val COLLECTION_MARK_WINDOW_MS: Long = 4000L

/** [COLLECTION_MARK_WINDOW_MS] 的纳秒值：与 [commitClockNanos] 同一单位。 */
val COLLECTION_MARK_WINDOW_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(COLLECTION_MARK_WINDOW_MS)

/** 窗口里待提交的那一笔：把 [subjectId] 标为 [type]，在 [commitAtNanos]（[commitClockNanos] 时基）提交。 */
data class PendingMark(val subjectId: Int, val type: CollectionType, val commitAtNanos: Long)

/**
 * 海报上看到的「我」的收藏状态：[types] 是已收藏的（本地收藏表，叠上正在提交的那一笔），[pending] 是窗口里还能撤销的那一笔。
 * 全 App 同一时刻至多一笔待提交。
 */
data class CollectionMarks(
    val types: Map<Int, CollectionType> = emptyMap(),
    val pending: PendingMark? = null,
) {
    /** [subjectId] 窗口里待提交的那一笔；不是它就是 null。 */
    fun pendingOf(subjectId: Int): PendingMark? = pending?.takeIf { it.subjectId == subjectId }
}

/** 标记从哪一页发起：失败只在这一页提示一次。按身份区分，每个页面实例一个。 */
class MarkOrigin

/** 一笔标记提交失败（海报已回到原来的状态）。 */
data class CollectionMarkFailure(val origin: MarkOrigin, val subjectId: Int, val failure: Failure)

/**
 * 海报长按标记的数据端（core:data 的协调器实现，全 App 一份）：标状态（留窗口可撤销）、撤销。
 * 环上「管理」打开的收藏面板不经这里，与列表行长按一样经 [CollectionEditing]。
 */
interface CollectionMarker {
    val marks: StateFlow<CollectionMarks>

    val failures: Flow<CollectionMarkFailure>

    /** 把 [subjectId] 标为 [type]：先进窗口，窗口走完才提交；别的作品还在窗口里就先把它提交了。 */
    fun mark(origin: MarkOrigin, subjectId: Int, type: CollectionType)

    /** 撤销 [subjectId] 窗口里的那一笔（已经在提交的撤不回）。 */
    fun undo(subjectId: Int)
}
