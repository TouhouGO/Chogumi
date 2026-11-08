package io.github.touhougo.chogumi.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.TopLevelDestination

/**
 * 平板 / 大屏的主导航：M3 Expressive 的收起态导航轨（collapsed navigation rail）。
 *
 * From M3（navigation.md「Navigation rail」）：收起态导航轨用于 medium 及以上窗口，**should not be hidden**；
 * 「Always put the rail in the same place, even on different screens of an app」；平板上导航项居中对齐更好够到。
 * 导航轨若在进入作品详情等非顶级页时整个移除、返回时再插回（M3 已不推荐的 baseline 导航轨就这样），页面区宽度当场变化，
 * 而页面切换要晚几帧才画出来——进详情时闪两帧空白、返回时导航轨先压在旧页上（平板上实录到的）。
 * 所以导航轨常驻，页面区宽度始终不变。
 *
 * 目的地与底栏同源（[destinations] 来自 `topLevelDestinationsFor(features)`，专注模式 4 个），本组件不自行筛除。
 * 点哪一项都交给 [onSelect]（`AppNavigator.selectTab`）：导航轨常驻，所以在任何一层再点当前项都能回到它的第一层。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TopLevelNavigationRail(
    currentTab: Route,
    destinations: List<TopLevelDestination>,
    onSelect: (Route) -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
) {
    WideNavigationRail(
        modifier = modifier.testTag("TopLevelNavigationRail"),
        arrangement = Arrangement.Center,
    ) {
        destinations.forEachIndexed { index, destination ->
            val selected = destination.route == currentTab
            WideNavigationRailItem(
                selected = selected,
                onClick = { onSelect(destination.route) },
                icon = { destination.Icon(selected, unreadCount) },
                label = { TopLevelLabel(stringResource(destination.title)) },
                railExpanded = false,
                modifier = with(destination) { Modifier.unreadSemantics(selected, unreadCount) }.testTag("TopLevelNavigationRailItem$index"),
            )
        }
    }
}
