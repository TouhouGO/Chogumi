package io.github.touhougo.chogumi.core.model

import java.util.concurrent.TimeUnit

/**
 * 停手多久才提交（毫秒）：**本项目设计决定（not in M3）**，原型 `phase1.html`
 * 的 `COMMIT_DELAY` 取值。给连点合并留出窗口，也正好避开章节接口的频率限制（每用户 5 分钟 10 次）。
 */
const val EP_COMMIT_DELAY_MS: Long = 1500L

/** [EP_COMMIT_DELAY_MS] 的纳秒值：与 [commitClockNanos] 同一单位。 */
val EP_COMMIT_DELAY_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(EP_COMMIT_DELAY_MS)

/**
 * 待提交倒计时的时钟（单调，纳秒）：协调器记下提交时刻、各页面算还剩多久都读它，同一时基，
 * 所以首页与进度页的倒计时是同一条，离开再回来也接着走，走完的那一刻就是真正提交的那一刻。
 * 章节进度与海报长按标记两种待提交共用它。
 */
fun commitClockNanos(): Long = System.nanoTime()

/**
 * 离 [commitAtNanos] 这一轮提交还剩几成（1 = 刚开始、0 = 到点），[windowNanos] 是这种待提交的整段窗口：倒计时只按它画。
 *
 * 按真实时间（[commitClockNanos] 时基的 [nowNanos]）算，不交给动画时长：系统的动画倍率（开发者选项、
 * 无障碍「移除动画」）会把动画拉长、缩短或直接跳到终点，真正的提交却仍在 [commitAtNanos] 那一刻。
 */
fun commitRemainingFraction(commitAtNanos: Long, nowNanos: Long, windowNanos: Long): Float =
    ((commitAtNanos - nowNanos).toFloat() / windowNanos).coerceIn(0f, 1f)

/**
 * 在途写入的乐观视觉：点了几下（[count]，按钮右上角的 `+N`）与这一轮的提交时刻（[commitAtNanos]，[commitClockNanos] 时基）。
 * 按钮上的字不看它：每次点击已乐观写进本地，按钮状态当即前进到下一步。
 */
data class EpPendingVisual(
    val count: Int,
    val commitAtNanos: Long,
)
