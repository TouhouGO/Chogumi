package io.github.touhougo.chogumi.core.foundation.testing

import io.github.touhougo.chogumi.core.foundation.AppTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 测试用的 [AppTime]：时间停在 [now] 当前的值，改 [now] 就是「时间过去了」，[AppTime.today] 当场跟上
 * （Unconfined：设值的那一刻就推出新的一天，测试不用等调度）。
 */
fun testAppTime(now: MutableStateFlow<Long>): AppTime = AppTime(now, CoroutineScope(Dispatchers.Unconfined))

/** 时间停在 [nowMillis]、不会走的 [AppTime]。 */
fun testAppTime(nowMillis: Long): AppTime = testAppTime(MutableStateFlow(nowMillis))
