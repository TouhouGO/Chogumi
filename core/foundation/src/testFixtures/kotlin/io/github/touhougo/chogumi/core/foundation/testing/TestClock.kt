package io.github.touhougo.chogumi.core.foundation.testing

import io.github.touhougo.chogumi.core.foundation.AppClock

/** 测试用的墙钟：停在 [time]，要「过一会儿」就改它或 [advance]。 */
class TestClock(var time: Long = 0L) : AppClock {
    override fun nowMillis(): Long = time

    fun advance(millis: Long) {
        time += millis
    }
}
