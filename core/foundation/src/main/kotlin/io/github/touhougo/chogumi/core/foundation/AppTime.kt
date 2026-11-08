package io.github.touhougo.chogumi.core.foundation

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 应用的「现在」与「今天」：全 App 唯一的时间刻度。各页订阅它，不各自取时间、不各自起计时，
 * 于是同一时刻首页、进度、详情、探索看到的是同一个「今天」，跨零点时一起变。
 *
 * - [now]：墙钟毫秒，整分钟一跳。相对时间（「5 分钟前」）、动态的日期头按它重算。
 * - [today]：业务日（[businessDateOf]，北京时间），由 [now] 推出，跨零点才变。放送、进度、年份按它重算。
 *
 * 系统时间走 [ticking]；测试直接给一个 [now]，改它的值就是「时间过去了」。
 */
class AppTime(val now: StateFlow<Long>, scope: CoroutineScope) {
    val today: StateFlow<LocalDate> = now.map(::businessDateOf).stateIn(scope, SharingStarted.Eagerly, businessDateOf(now.value))

    companion object {
        /**
         * 系统时间：[foreground] 为真时整分钟一跳，在后台不走（没人看）。回到前台立刻对一次表——
         * 后台的定时会因深度睡眠顺延，不能指望它准点。
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun ticking(clock: AppClock, foreground: Flow<Boolean>, scope: CoroutineScope): AppTime = AppTime(
            foreground.distinctUntilChanged()
                .flatMapLatest { shown -> if (shown) minuteTicks(clock) else emptyFlow() }
                .stateIn(scope, SharingStarted.Eagerly, clock.nowMillis()),
            scope,
        )

        /** 先报一次此刻，之后每到整分钟报一次。 */
        private fun minuteTicks(clock: AppClock): Flow<Long> = flow {
            while (true) {
                val now = clock.nowMillis()
                emit(now)
                delay(MINUTE_MILLIS - Math.floorMod(now, MINUTE_MILLIS))
            }
        }

        private const val MINUTE_MILLIS = 60_000L
    }
}
