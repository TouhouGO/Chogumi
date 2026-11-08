package io.github.touhougo.chogumi.core.ui.common.navigation

import io.github.touhougo.chogumi.core.ui.composables.LocalPageOnScreen
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavEntryDecorator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 「已在 tab 第一层时再点这个 tab」的事件（[AppNavigator.selectTab] 发出）：第一层的列表滚回顶部、收起的头部重新展开。
 * 由 [AppNavigator] 按 tab 提供给栈里的页面；那时栈里只有第一层，别的页面（压上去的子页、导航项以外）收到的是空流。
 *
 * 页面不自己判断「再点了一次」，只把列表交给 [ScrollToTopOnTabReselect]；共用的列表外壳（`PagedListContent`、
 * `ContentGrid`、`CollapsibleHeader`）已经接好。
 */
val LocalTabReselect = staticCompositionLocalOf<Flow<Unit>> { emptyFlow() }

/** 给一个 tab 栈里的每个导航项提供这一 tab 的「再点一次」事件。 */
@Composable
internal fun <T : Any> rememberTabReselectNavEntryDecorator(reselects: Flow<Unit>): NavEntryDecorator<T> =
    remember(reselects) {
        NavEntryDecorator { entry ->
            CompositionLocalProvider(LocalTabReselect provides reselects) { entry.Content() }
        }
    }

/** 再点当前 tab 时做 [action]（收到一次做一次）。翻走的页（[LocalPageOnScreen]）不做：回去时还停在离开的地方。 */
@Composable
fun OnTabReselect(action: suspend () -> Unit) {
    val reselects = LocalTabReselect.current
    val onScreen = LocalPageOnScreen.current
    val current by rememberUpdatedState(action)
    LaunchedEffect(reselects, onScreen) { reselects.collect { if (onScreen()) current() } }
}

/** 再点当前 tab 时把列表滚回顶部，并让外框归位（[LocalTopReset]）。 */
@Composable
fun ScrollToTopOnTabReselect(state: LazyListState) {
    val motion = LocalAppMotion.current
    val reset = LocalTopReset.current
    OnTabReselect {
        motion.scrollToItem(state, 0)
        reset()
    }
}

/** 再点当前 tab 时把网格滚回顶部，并让外框归位（[LocalTopReset]）。 */
@Composable
fun ScrollToTopOnTabReselect(state: LazyGridState) {
    val motion = LocalAppMotion.current
    val reset = LocalTopReset.current
    OnTabReselect {
        motion.scrollToItem(state, 0)
        reset()
    }
}
