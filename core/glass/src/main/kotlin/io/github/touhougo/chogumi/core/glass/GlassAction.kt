package io.github.touhougo.chogumi.core.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 一屏唯一的主操作（[GlassMaterial.Tinted]）：玻璃效果开着时替代页面的 extended FAB，放在 Scaffold 的 `floatingActionButton` 槽里，
 * 位置与 FAB 一样由 Scaffold 管。胶囊形，高与 M3 extended FAB 一样 56dp；按下时玻璃连同内容浮起、跟着手指被拉过去，没有水波纹。
 * [content] 就是 FAB 里原来的那一行（图标 + 文字），颜色由这里给（主色容器上的颜色）。
 */
@Composable
fun GlassActionButton(
    layer: GlassLayer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val touch = rememberGlassTouch()
    Box(
        modifier
            .height(GlassActionHeight)
            .defaultMinSize(minWidth = GlassActionMinWidth)
            .glass(layer, CircleShape, GlassMaterial.Tinted, touch)
            .glassTouch(touch)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = GlassActionPadding),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.glassContent(touch, GlassLift)) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimaryContainer, content = content)
        }
    }
}

/** 还没有选项时 [GlassTabs] 的占位（整页骨架）：同高、同边距的一块空胶囊。 */
@Composable
fun GlassTabsPlaceholder(layer: GlassLayer, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).glass(layer, CircleShape, GlassMaterial.Labeled))
}

/** M3 extended FAB 的容器高与左右留白（component-tokens ExtendedFabSmall：56dp、16dp），最小宽同 Compose 的 extended FAB（80dp）。 */
private val GlassActionHeight = 56.dp
private val GlassActionMinWidth = 80.dp
private val GlassActionPadding = 16.dp
