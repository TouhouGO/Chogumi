package io.github.touhougo.chogumi.core.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import io.github.touhougo.chogumi.core.ui.theme.isDark
import kotlin.math.abs

/**
 * 玻璃圆钮：顶上的返回、⋮ 这类只有图标的动作（[GlassMaterial.Clear]）。玻璃 44dp，触控区 48dp；按下时玻璃鼓起，没有水波纹。
 */
@Composable
fun GlassIconButton(
    layer: GlassLayer,
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberGlassPress(interaction)
    Box(
        modifier
            .size(GlassTouchTarget)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(GlassButtonSize).glass(layer, CircleShape, GlassMaterial.Clear, pressed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter, contentDescription, tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * 顶边：内容滚到顶上时，状态栏与顶栏后面的那一条渐隐模糊（滚动边缘效果）。上沿最实，往下淡到完全透明，标题直接写在它上面。
 * [visible] 是出现了几成（0 = 完全透明，1 = 全出来），只在绘制阶段读；为 0 时整层透明，不做模糊。
 * 它不是一块有边的玻璃：没有折射、高光与阴影，只有模糊和一层页面底色的渐变。
 */
@Composable
fun GlassTopEdge(layer: GlassLayer, visible: () -> Float, height: Dp, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val tint = remember(surface) { Brush.verticalGradient(0f to surface.copy(alpha = 0.82f), 1f to surface.copy(alpha = 0f)) }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawPlainBackdrop(
                backdrop = layer.backdrop,
                shape = { RectangleShape },
                effects = { blur(EdgeBlur.toPx()) },
                layerBlock = {
                    alpha = visible().coerceIn(0f, 1f)
                    // 渐隐的遮罩要在离屏层里合成，否则 DstIn 会把下面的页面一起挖掉
                    compositingStrategy = CompositingStrategy.Offscreen
                },
                onDrawSurface = { drawRect(tint) },
                onDrawFront = { drawRect(EdgeMask, blendMode = BlendMode.DstIn) },
            ),
    )
}

/**
 * 玻璃胶囊里的单选：底栏与作品页 tab 共用的唯一实现。选中项是胶囊里一滴更亮的玻璃，换选中项时那滴从旧位置滑到新位置，
 * 途中按速度拉长、到位回弹（主题的 expressive 空间弹簧，系统关掉动画时瞬时）；按住一格时那一格鼓起一圈亮边。
 * 那滴只是一块亮色，不再采样一次背景：玻璃里不叠玻璃，也省一次模糊。
 *
 * 位置只在绘制阶段读，滑动时不重组。[cell] 画每一格的内容，`selected` 时颜色由这里给（主色）。
 */
@Composable
fun <T> GlassTabs(
    layer: GlassLayer,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    cellModifier: @Composable (T, Boolean) -> Modifier = { _, _ -> Modifier },
    cell: @Composable (option: T, selected: Boolean) -> Unit,
) {
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    val position = remember { Animatable(selectedIndex.toFloat()) }
    val spec = LocalAppMotion.current.expressive.fastSpatialSpec<Float>()
    LaunchedEffect(selectedIndex) { position.animateTo(selectedIndex.toFloat(), spec) }
    val colors = MaterialTheme.colorScheme
    val dark = colors.isDark()
    val drop = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.62f)
    val dropEdge = if (dark) Color.White.copy(alpha = 0.30f) else Color.White
    val count = options.size
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .glass(layer, CircleShape, GlassMaterial.Labeled),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(GlassTabsInset)
                .drawBehind {
                    val cellWidth = size.width / count
                    // 拉长量跟着速度走：滑得越快越长，停下时回到一格宽
                    val stretch = (abs(position.velocity) * cellWidth * DropStretchPerVelocity).coerceAtMost(cellWidth * DropStretchMax)
                    val width = cellWidth + stretch
                    val left = (position.value * cellWidth - stretch / 2f).coerceIn(0f, size.width - width)
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(drop, Offset(left, 0f), Size(width, size.height), radius)
                    drawRoundRect(dropEdge, Offset(left, 0f), Size(width, size.height), radius, style = Stroke(DropEdge.toPx()))
                },
        )
        Row(Modifier.fillMaxSize().padding(GlassTabsInset)) {
            options.forEach { option ->
                val isSelected = option == selected
                val interaction = remember { MutableInteractionSource() }
                val pressed = rememberGlassPress(interaction)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(cellModifier(option, isSelected))
                        .selectable(
                            selected = isSelected,
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(option) },
                        )
                        .drawBehind {
                            val p = pressed()
                            if (p > 0f) {
                                val grow = DropPressGrow.toPx() * p
                                drawRoundRect(
                                    color = dropEdge.copy(alpha = dropEdge.alpha * p),
                                    topLeft = Offset(-grow, -grow),
                                    size = Size(size.width + grow * 2, size.height + grow * 2),
                                    cornerRadius = CornerRadius(size.height / 2f + grow),
                                    style = Stroke(DropEdge.toPx() * 1.5f),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides if (isSelected) colors.primary else colors.onSurface) {
                        cell(option, isSelected)
                    }
                }
            }
        }
    }
}

/** 玻璃胶囊 tab 的高度（带字的胶囊）与它外面留的边距：作品页把内容的上内边距按它算。 */
val GlassTabsHeight = 48.dp
val GlassTabsMargin = 8.dp

private val GlassButtonSize = 44.dp
private val GlassTouchTarget = 48.dp
private val GlassTabsInset = 4.dp
private val EdgeBlur = 12.dp
private val EdgeMask = Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent)
private val DropEdge = 1.dp
private val DropPressGrow = 3.dp

/** 每单位速度（格 / 秒）拉长几成格宽，最多拉长几成。 */
private const val DropStretchPerVelocity = 0.06f
private const val DropStretchMax = 0.6f
