package io.github.touhougo.chogumi.core.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 玻璃外框里内容区的上内边距：[GlassHeaderTabsFrame] 放进来，页面的列表把它加在 `contentPadding` 的上边，
 * 第一行内容停在 tab 胶囊下面，往上滚时从胶囊、顶边下面滚过。不在玻璃外框里时是 0。
 */
val LocalGlassContentTop = staticCompositionLocalOf { 0.dp }

/**
 * 顶级页铺到玻璃底栏下面时，底部要让出的高度（底栏胶囊连同它的边距与系统导航条）：列表加在 `contentPadding` 的下边，
 * 最后一行能滚到胶囊上面；FAB 与提示条放进 [AboveGlassBar]。由 [AboveBottomBar] 给出，玻璃关着、推进来的页面都是 0。
 */
val LocalGlassContentBottom = compositionLocalOf { 0.dp }

/**
 * 底栏之上的顶级页（首页、探索、进度、社区、我的）的外层，[bar] 是底栏的高度。
 * - 玻璃关着：页面让出底栏，和原来一样；
 * - 玻璃开着：玻璃底栏浮在页面上，页面铺到屏幕底，滚过底栏的内容在胶囊后面透出来；底部让出多少由页面按
 *   [LocalGlassContentBottom] 自己定。
 *
 * 两种情况下这段都记为已消费：底栏已经含系统导航条，页里的 Scaffold、imePadding 不再算第二遍。组合结构不随开关变，
 * 切换玻璃效果时页面的保存状态还在。
 */
@Composable
fun AboveBottomBar(bar: PaddingValues, content: @Composable (Modifier) -> Unit) {
    val glass = LocalGlass.current
    CompositionLocalProvider(LocalGlassContentBottom provides if (glass) bar.calculateBottomPadding() else 0.dp) {
        content((if (glass) Modifier else Modifier.padding(bar)).consumeWindowInsets(bar))
    }
}

/**
 * 浮在玻璃底栏之上的一层：铺满父级，底部让出 [LocalGlassContentBottom]。顶级页的 FAB 与提示条放在这里，
 * 位置规则照旧（`SelectionFabMenu` 自己贴右下角、提示条贴底居中），只是停在胶囊上面。玻璃关着时就是铺满父级的一层。
 */
@Composable
fun BoxScope.AboveGlassBar(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.matchParentSize().padding(bottom = LocalGlassContentBottom.current), content = content)
}
