package io.github.touhougo.chogumi.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.TopLevelDestination

/**
 * 手机（compact 窗口）的主导航：M3 导航栏，高度、触控与选中动画由 Material 管理。
 * 只在顶级页显示（[isVisible]），收起时整条滑出屏幕；平板改用常驻的 [TopLevelNavigationRail]。
 *
 * 收起**不让出布局高度**：外壳交给顶级页的底部留白就是这条栏的高度，显隐时不变。
 * 进详情页、开面板时栏收起，顶级页的页面区保持原样，返回时也不用重新排一遍（栏收起时让出高度的话，
 * 顶级页拿到的留白会变成 0，回来后最后几行就压在栏下面）。
 *
 * [destinations] 由 `topLevelDestinationsFor(features)` 决定（专注模式 4 个、完整模式 5 个），本组件不自行筛除。
 * 点哪一项都交给 [onSelect]（`AppNavigator.selectTab`）：点当前项做什么由导航层定，这里不分支。
 */
@Composable
fun TopLevelNavigationBar(
    currentTab: Route,
    destinations: List<TopLevelDestination>,
    isVisible: Boolean,
    onSelect: (Route) -> Unit,
    unreadCount: Int = 0,
) {
    // 0 = 在原位，1 = 整条移到屏幕底边以下；位移走 M3 的快速空间弹簧（栏只是让位，不该慢慢晃上来）
    val hidden by animateFloatAsState(
        targetValue = if (isVisible) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "TopLevelNavigationBarHidden",
    )
    NavigationBar(
        modifier = Modifier
            // 弹簧回弹不越过原位：栏贴着屏幕底边，往上多出去会露出底下的空白
            .graphicsLayer { translationY = size.height * hidden.coerceIn(0f, 1f) }
            // 语义写在位移之后，位置随栏一起移出屏幕；收起后读屏不停在屏幕外的导航项上
            .then(if (isVisible) Modifier else Modifier.semantics { hideFromAccessibility() })
            .testTag("TopLevelNavigationBar"),
    ) {
        destinations.forEachIndexed { index, destination ->
            val selected = destination.route == currentTab
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination.route) },
                icon = { destination.Icon(selected, unreadCount) },
                label = { TopLevelLabel(stringResource(destination.title)) },
                modifier = with(destination) { Modifier.unreadSemantics(selected, unreadCount) }.testTag("TopLevelNavigationBarItem$index"),
            )
        }
    }
}
