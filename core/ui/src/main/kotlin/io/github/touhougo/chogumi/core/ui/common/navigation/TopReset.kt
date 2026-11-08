package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * 程序把列表滚回顶部之后，让「靠滚动增量判断内容在不在下面」的外框归位：吸顶条的柔边、随滚动让位的顶栏。
 * 程序滚动不经过嵌套滚动分发，这些外框收不到增量，只能由回顶的入口（回到顶部按钮、再点底栏 tab）显式通知。
 * 外框用 [ProvideTopReset] 登记自己的归位，入口滚完调 [LocalTopReset]（外层的归位也一并执行）。
 */
val LocalTopReset = compositionLocalOf<() -> Unit> { {} }

/** 登记 [reset]：里面的回顶入口滚到顶之后，先执行外层的归位，再执行它。 */
@Composable
fun ProvideTopReset(reset: () -> Unit, content: @Composable () -> Unit) {
    val outer = LocalTopReset.current
    val current by rememberUpdatedState(reset)
    val combined = remember(outer) { { outer(); current() } }
    CompositionLocalProvider(LocalTopReset provides combined) { content() }
}
