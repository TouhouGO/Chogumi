package io.github.touhougo.chogumi.core.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sign

/**
 * 玻璃底栏收起成左下角一颗圆钮（设计决策，not in M3；照 iOS 26 的标签栏）：页面内容往下读（往上滚）一段就收起，往回滚一段或点那颗圆钮
 * 就展开。只看内容真的滚动了多少（子级消费掉的量），拉到顶再往下拉、横向翻页都不算。同一个方向连续滚过 [threshold] 才切换，
 * 手指的小抖动不来回闪。
 */
@Stable
class GlassBarMinimize internal constructor(private val threshold: Float) {
    var minimized by mutableStateOf(false)
        private set

    fun expand() {
        minimized = false
        travelled = 0f
    }

    private var travelled = 0f

    internal val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy == 0f) return Offset.Zero
            // 换了方向从头量
            if (travelled != 0f && dy.sign != travelled.sign) travelled = 0f
            travelled += dy
            if (travelled < -threshold) minimized = true
            if (travelled > threshold) minimized = false
            return Offset.Zero
        }
    }
}

@Composable
fun rememberGlassBarMinimize(): GlassBarMinimize {
    val threshold = with(LocalDensity.current) { MinimizeTravel.toPx() }
    return remember(threshold) { GlassBarMinimize(threshold) }
}

/** 页面区接上 [minimize]，内容的滚动决定底栏收不收；[minimize] 为 null（玻璃关着）时什么也不做。 */
fun Modifier.glassBarScroll(minimize: GlassBarMinimize?): Modifier = if (minimize == null) this else nestedScroll(minimize.connection)

/** 同一个方向滚过这么多才收起或展开。 */
private val MinimizeTravel = 48.dp
