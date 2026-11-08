package io.github.touhougo.chogumi.core.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import io.github.touhougo.chogumi.core.ui.theme.isDark
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 玻璃圆钮：顶上的返回、⋮ 这类只有图标的动作（[GlassMaterial.Clear]）。玻璃 44dp，触控区 48dp；按下时玻璃连同图标浮起、
 * 跟着手指被拉过去，松手弹回（[GlassTouch]），没有水波纹。
 */
@Composable
fun GlassIconButton(
    layer: GlassLayer,
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touch = rememberGlassTouch()
    Box(
        modifier
            .size(GlassTouchTarget)
            .glassTouch(touch)
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(GlassButtonSize).glass(layer, CircleShape, GlassMaterial.Clear, touch))
        Icon(painter, contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.glassContent(touch, GlassLift))
    }
}

/**
 * 顶边：内容滚到顶上时，状态栏、顶栏与钉住的 tab 后面的那一块模糊（滚动边缘效果）。从上沿到 [height] 减 [fade] 处是实的：
 * 内容在这里被模糊、压上一层页面底色，只透出颜色和轮廓，字读不出来；最后 [fade] 一段淡到完全透明，内容进出顶边时没有硬边。
 * 标题与圆钮写在它上面，钉住的 tab 胶囊浮在它上面（调用处把它画在 tab 下面）。
 * [visible] 是出现了几成（0 = 完全透明，1 = 全出来），只在绘制阶段读；为 0 时整层透明，不做模糊。
 * 它不是一块有边的玻璃：没有折射、高光与阴影，只有模糊和一层页面底色。
 */
@Composable
fun GlassTopEdge(layer: GlassLayer, visible: () -> Float, height: Dp, fade: Dp, modifier: Modifier = Modifier) {
    val surface = MaterialTheme.colorScheme.surface
    val solid = ((height - fade) / height).coerceIn(0f, 1f)
    val tint = remember(surface, solid) {
        Brush.verticalGradient(0f to surface.copy(alpha = EdgeTint), solid to surface.copy(alpha = EdgeTint), 1f to surface.copy(alpha = 0f))
    }
    val mask = remember(solid) { Brush.verticalGradient(0f to Color.Black, solid to Color.Black, 1f to Color.Transparent) }
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
                onDrawFront = { drawRect(mask, blendMode = BlendMode.DstIn) },
            ),
    )
}

/**
 * 玻璃胶囊里的单选：底栏与作品页 tab 共用的唯一实现。选中项是胶囊里的一滴（M3 导航指示器的颜色）。
 *
 * 跟手：手指一按上胶囊，整条胶囊连同内容浮起一点、被手指拉着走（[GlassTouch]），那滴也浮起（变大、变清、带上阴影）
 * 并滑到手指下面那一格；按着左右拖，那滴跟着手指走，松手落在手指下面那一格并选中它。只点一下就是选中那一格。
 * 换选中项时那滴途中按速度拉长、到位回弹（主题的 expressive 空间弹簧，系统关掉动画时瞬时）。
 * 那滴只是一块颜色，不再采样一次背景：玻璃里不叠玻璃，也省一次模糊。
 *
 * 位置、浮起只在绘制阶段读，拖动时不重组。[cell] 画每一格的内容，`selected` 时颜色由这里给（主色）。
 * 读屏与键盘照常经每一格的 `selectable` 选中。
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
    val dropLift = remember { Animatable(0f) }
    val spec = LocalAppMotion.current.expressive.fastSpatialSpec<Float>()
    LaunchedEffect(selectedIndex) { position.animateTo(selectedIndex.toFloat(), spec) }
    val scope = rememberCoroutineScope()
    val touch = rememberGlassTouch()
    val colors = MaterialTheme.colorScheme
    val dark = colors.isDark()
    // 选中的那滴取 M3 导航指示器的颜色（secondaryContainer），在带字玻璃上一眼看得出选中了哪一格；浮起时变清一些
    val drop = colors.secondaryContainer.copy(alpha = if (dark) 0.78f else 0.88f)
    val dropEdge = if (dark) Color.White.copy(alpha = 0.30f) else Color.White
    val count = options.size
    val currentOptions by rememberUpdatedState(options)
    val currentOnSelect by rememberUpdatedState(onSelect)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .glass(layer, CircleShape, GlassMaterial.Labeled, touch, liftBy = TabsLift)
            .glassTouch(touch),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .glassContent(touch, TabsLift)
                .padding(GlassTabsInset),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val cellWidth = size.width / count
                        // 弹簧会过冲：落回时短暂小于 0，颜色的透明度不能出界
                        val lifted = dropLift.value.coerceIn(0f, 1.2f)
                        // 拉长量跟着速度走：滑得越快越长，停下时回到一格宽；浮起时四周再大出一圈
                        val stretch = (abs(position.velocity) * cellWidth * DropStretchPerVelocity).coerceAtMost(cellWidth * DropStretchMax)
                        val grow = DropLiftGrow.toPx() * lifted
                        val width = cellWidth + stretch + grow * 2
                        val left = (position.value * cellWidth - stretch / 2f - grow).coerceIn(-grow, size.width - width + grow)
                        val top = -grow
                        val dropHeight = size.height + grow * 2
                        val radius = CornerRadius(dropHeight / 2f)
                        if (lifted > 0f) {
                            drawRoundRect(
                                Color.Black.copy(alpha = DropShadowAlpha * lifted),
                                Offset(left, top + DropShadowOffset.toPx() * lifted),
                                Size(width, dropHeight),
                                radius,
                            )
                        }
                        drawRoundRect(drop.copy(alpha = drop.alpha * (1f - DropLiftClear * lifted)), Offset(left, top), Size(width, dropHeight), radius)
                        drawRoundRect(dropEdge, Offset(left, top), Size(width, dropHeight), radius, style = Stroke(DropEdge.toPx() * (1f + lifted)))
                    }
                    // 拖着选：那滴跟着手指，松手选中手指下面那一格（只观察手势，点一下仍由每格的 selectable 处理）
                    .pointerInput(count) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val cellWidth = size.width.toFloat() / count
                            fun cellAt(x: Float) = (x / cellWidth - 0.5f).coerceIn(0f, (count - 1).toFloat())
                            scope.launch { dropLift.animateTo(1f, spec) }
                            scope.launch { position.animateTo(cellAt(down.position.x), spec) }
                            var dragged = false
                            var x = down.position.x
                            while (true) {
                                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                x = change.position.x
                                if (abs(x - down.position.x) > viewConfiguration.touchSlop) dragged = true
                                if (dragged) scope.launch { position.snapTo(cellAt(x)) }
                            }
                            val landed = cellAt(x).roundToInt()
                            scope.launch { dropLift.animateTo(0f, spec) }
                            scope.launch { position.animateTo(landed.toFloat(), spec) }
                            if (dragged) currentOptions.getOrNull(landed)?.let(currentOnSelect)
                        }
                    },
            )
            Row(Modifier.fillMaxSize()) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(cellModifier(option, isSelected))
                            .selectable(
                                selected = isSelected,
                                interactionSource = null,
                                indication = null,
                                role = Role.Tab,
                                onClick = { onSelect(option) },
                            ),
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
}

/** 玻璃胶囊 tab 的高度（带字的胶囊）与它外面留的边距：作品页把内容的上内边距按它算。 */
val GlassTabsHeight = 48.dp
val GlassTabsMargin = 8.dp

private val GlassButtonSize = 44.dp
private val GlassTouchTarget = 48.dp
private val GlassTabsInset = 4.dp
/** 顶边的模糊半径与实的那一段压的页面底色：字读不出来，封面的颜色还透得出来。 */
private val EdgeBlur = 24.dp
private const val EdgeTint = 0.86f
private val DropEdge = 1.dp
/** 胶囊被按住时短边多出的量（比圆钮小：它横跨整屏）。 */
private val TabsLift = 3.dp

/** 那滴浮起时四周大出的量、变清几成、阴影的浓淡与下移。 */
private val DropLiftGrow = 4.dp
private const val DropLiftClear = 0.35f
private const val DropShadowAlpha = 0.10f
private val DropShadowOffset = 3.dp

/** 每单位速度（格 / 秒）拉长几成格宽，最多拉长几成。 */
private const val DropStretchPerVelocity = 0.06f
private const val DropStretchMax = 0.6f
