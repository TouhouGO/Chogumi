package io.github.touhougo.chogumi.core.model.statistics

import kotlin.math.roundToInt

/** 统计里的百分比整数：四舍五入，分母为 0 记 0。数据层的占比与界面上的占比都用它，同一页上的数取整方式一致。 */
fun percentOf(count: Int, total: Int): Int =
    if (total <= 0) 0 else (count * 100.0 / total).roundToInt()
