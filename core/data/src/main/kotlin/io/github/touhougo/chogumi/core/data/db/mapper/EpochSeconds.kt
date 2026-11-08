package io.github.touhougo.chogumi.core.data.db.mapper

/**
 * 接口给的 Unix 秒 → epoch 毫秒，全模块只有这一处换算（p1 与 v0 的时刻字段都是秒）。
 * 0 和负数是「没有这个时刻」，一律记 0。不按数值大小猜单位：猜会把服务端改单位这种接口变化悄悄盖过去。
 */
internal fun Long.epochSecondsToMillis(): Long = if (this <= 0L) 0L else Math.multiplyExact(this, 1000L)
