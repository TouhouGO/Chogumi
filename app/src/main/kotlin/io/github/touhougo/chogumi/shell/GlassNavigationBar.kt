package io.github.touhougo.chogumi.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.touhougo.chogumi.core.glass.GlassBarMinimize
import io.github.touhougo.chogumi.core.glass.GlassLayer
import io.github.touhougo.chogumi.core.glass.GlassMaterial
import io.github.touhougo.chogumi.core.glass.GlassTabs
import io.github.touhougo.chogumi.core.glass.glass
import io.github.touhougo.chogumi.core.glass.glassContent
import io.github.touhougo.chogumi.core.glass.glassTouch
import io.github.touhougo.chogumi.core.glass.rememberGlassTouch
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.TopLevelDestination
import kotlin.math.roundToInt

/**
 * 玻璃效果开着时的 [TopLevelNavigationBar]（core:glass）：浮在内容上的一条玻璃胶囊，左右与底边留出边距，内容从它下面和四周滚过；
 * 选中项上压着一块放大镜，按下时浮成气泡、跟着手指走（[GlassTabs]）。其余规则与 M3 导航栏一样：只在顶级页显示，收起时整条滑出屏幕
 * 且不让出布局高度（外壳交给页面的底部留白就是这一条连边距的高度），项、未读徽标与读屏、测试标记都一样。
 *
 * 往下读时胶囊收成左下角一颗圆钮（[minimize]，照 iOS 26 的标签栏；设计决策，not in M3）：圆钮里是当前 tab 的图标，点它或往回滚就展开。
 * 收起过程中胶囊的宽度缩到一颗圆钮、格子淡出，圆钮淡入；全收起后胶囊不在组合里，空出来的地方点到的是下面的页面。换 tab 时展开。
 */
@Composable
internal fun GlassNavigationBar(
    glass: GlassLayer,
    minimize: GlassBarMinimize,
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
    // 0 = 整条胶囊，1 = 左下角一颗圆钮；只在排版、绘制阶段读，收起的过程中不重组，只在两头增删胶囊与圆钮
    val minimized = animateFloatAsState(
        targetValue = if (minimize.minimized) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "GlassNavigationBarMinimized",
    )
    val showTabs by remember { derivedStateOf { minimized.value < 1f } }
    val showCircle by remember { derivedStateOf { minimized.value > 0f } }
    LaunchedEffect(currentTab) { minimize.expand() }
    val current = destinations.firstOrNull { it.route == currentTab } ?: destinations.first()
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
        if (showTabs) {
            GlassTabs(
                layer = glass,
                options = destinations,
                selected = current,
                onSelect = { onSelect(it.route) },
                height = BarHeight,
                modifier = Modifier
                    // 收起时宽度缩到一颗圆钮（排版阶段读，不重组），格子在前一半淡出
                    .layout { measurable, constraints ->
                        val m = minimized.value.coerceIn(0f, 1f)
                        val width = lerp(constraints.maxWidth.toFloat(), BarHeight.toPx(), m).roundToInt()
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer { alpha = (1f - minimized.value * 2f).coerceIn(0f, 1f) },
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
        if (showCircle) {
            MinimizedBar(glass, current, unreadCount, alpha = { ((minimized.value - 0.5f) * 2f).coerceIn(0f, 1f) }, onExpand = minimize::expand)
        }
    }
}

/** 收起后的那颗圆钮：带字玻璃，当前 tab 的图标（主色、填充、带未读徽标），点它展开整条胶囊。按下浮起，没有水波纹。 */
@Composable
private fun MinimizedBar(glass: GlassLayer, current: TopLevelDestination, unreadCount: Int, alpha: () -> Float, onExpand: () -> Unit) {
    val touch = rememberGlassTouch()
    val title = stringResource(current.title)
    Box(
        Modifier
            .size(BarHeight)
            .graphicsLayer { this.alpha = alpha() }
            .glass(glass, CircleShape, GlassMaterial.Labeled, touch)
            .glassTouch(touch)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.navigation_bar_expand),
                onClick = onExpand,
            )
            .semantics { contentDescription = title }
            .testTag("TopLevelNavigationBarMinimized"),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
            Box(Modifier.glassContent(touch, BarIconLift)) { current.Icon(true, unreadCount) }
        }
    }
}

/** 胶囊高 64dp（M3 导航栏 80dp 去掉上下留白），左右与底边离屏幕边 16dp，上面给内容留 8dp。设计决策，not in M3。 */
private val BarHeight = 64.dp
private val BarMargin = 16.dp
private val BarTopGap = 8.dp

/** 圆钮按下时短边多出的量，与 core:glass 的圆钮一样。 */
private val BarIconLift = 8.dp
