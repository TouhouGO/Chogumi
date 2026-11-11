package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion

/**
 * 页面区的唯一接法（红线：单一真源）：共享转场作用域、容器转换的起点登记、[NavDisplay] 的三个转场规格与排版方向，
 * 外壳和测试都经它，只换 [entryProvider]。转场取值全部来自主题的动效（[chogumiTransition]）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavDisplay(
    navigator: AppNavigator,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    sheets: PageSheetRegistry,
    modifier: Modifier = Modifier,
) {
    val page = LocalAppMotion.current.page
    // 位移与前进后退的方向都随排版方向翻转；预览位移与方向是两件事，分别传
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val shiftPx = with(LocalDensity.current) { page.shift.roundToPx() } * direction
    SharedTransitionLayout(modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalPageExpandRegistry provides navigator.expand,
            LocalSharedTransitionScope provides this,
        ) {
            NavDisplay(
                modifier = Modifier.fillMaxSize(),
                entries = navigator.entries(entryProvider, sheets),
                transitionSpec = { chogumiTransition(navigator.transitionKind(back = false, targetState, initialState), page, direction) },
                popTransitionSpec = { chogumiTransition(navigator.transitionKind(back = true, targetState, initialState), page, direction) },
                predictivePopTransitionSpec = { swipeEdge ->
                    chogumiPredictivePopTransition(navigator.transitionKind(back = true, targetState, initialState), page, direction, shiftPx, swipeEdge)
                },
                onBack = navigator::goBack,
            )
        }
    }
}
