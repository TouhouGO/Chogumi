package io.github.touhougo.chogumi.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.glass.GlassLayer
import io.github.touhougo.chogumi.core.glass.GlassTabs
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.TopLevelDestination

/**
 * 玻璃效果开着时的 [TopLevelNavigationBar]（core:glass）：浮在内容上的一条玻璃胶囊，左右与底边留出边距，内容从它下面和四周滚过；
 * 选中项是胶囊里滑动的一滴亮玻璃（[GlassTabs]）。其余规则与 M3 导航栏一样：只在顶级页显示，收起时整条滑出屏幕且不让出布局高度
 * （外壳交给页面的底部留白就是这一条连边距的高度），项、未读徽标与读屏、测试标记都一样。
 */
@Composable
internal fun GlassNavigationBar(
    glass: GlassLayer,
    currentTab: Route,
    destinations: List<TopLevelDestination>,
    isVisible: Boolean,
    onSelect: (Route) -> Unit,
    unreadCount: Int,
) {
    // 与 M3 导航栏同一条位移：0 = 在原位，1 = 整条移到屏幕底边以下
    val hidden by animateFloatAsState(
        targetValue = if (isVisible) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "GlassNavigationBarHidden",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val h = hidden.coerceIn(0f, 1f)
                translationY = size.height * h
                // 整条出了屏幕就不画：玻璃的模糊不在屏幕外白算
                alpha = if (h >= 1f) 0f else 1f
            }
            .then(if (isVisible) Modifier else Modifier.semantics { hideFromAccessibility() })
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = BarMargin, end = BarMargin, top = BarTopGap, bottom = BarMargin)
            .testTag("TopLevelNavigationBar"),
    ) {
        GlassTabs(
            layer = glass,
            options = destinations,
            selected = destinations.firstOrNull { it.route == currentTab } ?: destinations.first(),
            onSelect = { onSelect(it.route) },
            height = BarHeight,
            cellModifier = { destination, selected ->
                val index = destinations.indexOf(destination)
                with(destination) { Modifier.unreadSemantics(selected, unreadCount) }.testTag("TopLevelNavigationBarItem$index")
            },
        ) { destination, selected ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                destination.Icon(selected, unreadCount)
                TopLevelLabel(stringResource(destination.title))
            }
        }
    }
}

/** 胶囊高 64dp（M3 导航栏 80dp 去掉上下留白），左右与底边离屏幕边 16dp，上面给内容留 8dp。设计决策，not in M3。 */
private val BarHeight = 64.dp
private val BarMargin = 16.dp
private val BarTopGap = 8.dp
