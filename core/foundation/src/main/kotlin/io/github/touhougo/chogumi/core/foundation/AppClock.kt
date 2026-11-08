package io.github.touhougo.chogumi.core.foundation

/**
 * 墙钟（毫秒）：全 App 读「现在」只经它，系统时间只在 [SYSTEM] 这一处读（门禁 `tools/guards/check_clock.py`）；
 * 测试注入假钟。要随时间走的「现在 / 今天」用 [AppTime]，不自己隔一会儿读一次。
 */
fun interface AppClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = AppClock { System.currentTimeMillis() }
    }
}
