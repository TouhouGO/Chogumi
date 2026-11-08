package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/**
 * 骨架占位的颜色（全仓唯一）：[block] 给色块（[SkeletonBox]：封面、书脊、方块），[bar] 给字条（[SkeletonText]）。
 * 平时两者都是 `surfaceContainerHighest`，占位安静地等内容来；[SkeletonSketch] 里换成主题的强调色，骨架当示意图用。
 */
@Immutable
internal class SkeletonColors(val block: Color, val bar: Color)

private val LocalSkeletonColors = staticCompositionLocalOf<SkeletonColors?> { null }

internal val skeletonColors: SkeletonColors
    @Composable get() = LocalSkeletonColors.current
        ?: MaterialTheme.colorScheme.surfaceContainerHighest.let { SkeletonColors(block = it, bar = it) }

/**
 * 把 [content] 里的骨架画成示意图：色块取 `primary`，字条取 `outline`。用在「一种画法缩小了是什么样」的缩略图上
 * （探索页模块的样式选项）：骨架与真内容同一个框，换了颜色就是那种版式的示意，不必另画一份。
 * 两个都是主题的颜色角色，深浅色主题里在 `surfaceContainer`、`secondaryContainer` 上都清楚（设计决定，not in M3）。
 */
@Composable
fun SkeletonSketch(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalSkeletonColors provides SkeletonColors(block = scheme.primary, bar = scheme.outline), content = content)
}

/** 骨架块静态占位背景。 */
@Composable
fun Modifier.skeletonFill(shape: Shape = RectangleShape): Modifier = background(skeletonColors.block, shape)

/**
 * 基础骨架块占位组件。
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
) {
    Box(modifier = modifier.skeletonFill(shape = shape))
}

/**
 * 「真组件当骨架」的唯一写法：[content] 用样例数据照常排版，但不可见、读屏读不到，上面盖一块同大的 [SkeletonBox]。
 * 占位因此与真组件同大，放大字号、换屏宽时一起变，不必猜 dp（docs/code-standards.md「骨架与真内容共用一个框架」）。
 * 盖着的那层接住触摸：隐形的图表点了不会弹出气泡，隐形的横滑拖不动，隐形的封面点不进去。
 * 色块的圆角默认 `shapes.small`；整个就是一个形状的组件（按钮）传它自己的形状，占位与真组件同一个轮廓。
 */
@Composable
fun SkeletonStandIn(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        Box(Modifier.alpha(0f).clearAndSetSemantics {}) { content() }
        SkeletonBox(Modifier.matchParentSize().pointerInput(Unit) {}, shape)
    }
}
