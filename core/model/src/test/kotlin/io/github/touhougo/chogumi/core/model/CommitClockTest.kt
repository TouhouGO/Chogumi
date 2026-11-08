package io.github.touhougo.chogumi.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/** 倒计时剩几成只看真实时间：刚开始满、过半一半、到点与过点都是 0、时钟往回跳也不超过 1；按各自的窗口长度算。 */
class CommitClockTest {
    private val commitAt = 10_000_000_000L

    @Test
    fun `剩余比例按离提交时刻的真实时间算`() {
        assertEquals(1f, commitRemainingFraction(commitAt, commitAt - EP_COMMIT_DELAY_NANOS, EP_COMMIT_DELAY_NANOS))
        assertEquals(0.5f, commitRemainingFraction(commitAt, commitAt - EP_COMMIT_DELAY_NANOS / 2, EP_COMMIT_DELAY_NANOS))
        assertEquals(0f, commitRemainingFraction(commitAt, commitAt, EP_COMMIT_DELAY_NANOS))
    }

    @Test
    fun `过了提交时刻是0_早于整个窗口也不超过1`() {
        assertEquals(0f, commitRemainingFraction(commitAt, commitAt + 1, EP_COMMIT_DELAY_NANOS))
        assertEquals(1f, commitRemainingFraction(commitAt, commitAt - EP_COMMIT_DELAY_NANOS * 2, EP_COMMIT_DELAY_NANOS))
    }

    @Test
    fun `同一剩余时间按各自窗口长度归一`() {
        val now = commitAt - EP_COMMIT_DELAY_NANOS
        assertEquals(1f, commitRemainingFraction(commitAt, now, EP_COMMIT_DELAY_NANOS))
        assertEquals(EP_COMMIT_DELAY_NANOS.toFloat() / COLLECTION_MARK_WINDOW_NANOS, commitRemainingFraction(commitAt, now, COLLECTION_MARK_WINDOW_NANOS))
    }
}
