package io.github.touhougo.chogumi.core.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import io.github.touhougo.chogumi.core.ui.theme.isDark
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 玻璃圆钮：顶上的返回、⋮ 这类只有图标的动作（[GlassMaterial.Clear]）。玻璃 44dp，触控区 48dp；按下时玻璃连同图标浮起、
 * 放大一圈，松手落回（[GlassTouch]），没有水波纹。
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
 * 玻璃胶囊里的单选：底栏与作品页 tab 共用的唯一实现。胶囊本身不动；选中的那一格上面压着一块放大镜（[GlassLens]）。
 *
 * - 落着：放大镜是一块淡淡的底色，透过它看到的那一格是主色，标出选中了哪一格。
 * - 手指一按上胶囊：放大镜浮起、变大到比胶囊还高，变成一颗透明的玻璃气泡，滑到手指下面；气泡边缘折射、带高光与阴影，
 *   透过它看到的那一格放大一点、显成主色。按着左右拖，气泡跟着手指走，滑得快时顺着走的方向略拉长；松手落回最近的一格并选中它。
 *   只点一下就是普通的点选。
 * - 弹簧取主题的 expressive 空间规格（系统关掉动画时瞬时）。
 *
 * 气泡里看到的东西来自三层：页面（[layer]）、胶囊画出来的样子（胶囊玻璃另录一份）、一份看不见的主色格子（每格按浮起量放大）。
 * 位置与浮起只在排版、绘制阶段读，拖动时不重组。[cell] 画每一格的内容，颜色由这里给。读屏与键盘照常经每一格的 `selectable` 选中。
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
    val lens = remember { GlassLens(selectedIndex.toFloat()) }
    val spec = LocalAppMotion.current.expressive.fastSpatialSpec<Float>()
    LaunchedEffect(selectedIndex) { lens.position.animateTo(selectedIndex.toFloat(), spec) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val capsule = rememberLayerBackdrop()
    val accent = rememberLayerBackdrop()
    val count = options.size
    val currentOptions by rememberUpdatedState(options)
    val currentOnSelect by rememberUpdatedState(onSelect)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            // 拖着选：放在所有格子的外面，先于格子看到手指（只观察，点一下仍由每格的 selectable 处理）
            .pointerInput(count) {
                val inset = GlassTabsInset.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val cellWidth = (size.width - inset * 2) / count
                    fun cellAt(x: Float) = ((x - inset) / cellWidth - 0.5f).coerceIn(0f, (count - 1).toFloat())
                    scope.launch { lens.press.animateTo(1f, spec) }
                    scope.launch { lens.position.animateTo(cellAt(down.position.x), spec) }
                    var dragged = false
                    var x = down.position.x
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        x = change.position.x
                        if (abs(x - down.position.x) > viewConfiguration.touchSlop) dragged = true
                        if (dragged) scope.launch { lens.position.animateTo(cellAt(x), spec) }
                    }
                    val landed = cellAt(x).roundToInt()
                    scope.launch { lens.press.animateTo(0f, spec) }
                    scope.launch { lens.position.animateTo(landed.toFloat(), spec) }
                    if (dragged) currentOptions.getOrNull(landed)?.let(currentOnSelect)
                }
            },
    ) {
        // 胶囊：不动；画出来的样子另录一份，气泡从那里采样
        Box(Modifier.matchParentSize().glass(layer, CircleShape, GlassMaterial.Labeled, export = capsule))
        // 主色的一份格子：看不见，只录给气泡，气泡下面的那一格显成主色、按浮起量放大
        Row(
            Modifier
                .matchParentSize()
                .padding(GlassTabsInset)
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(accent),
        ) {
            options.forEach { option ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            val magnify = 1f + LensMagnify * lens.press.value.coerceIn(0f, 1f)
                            scaleX = magnify
                            scaleY = magnify
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides colors.primary) { cell(option, option == selected) }
                }
            }
        }
        Row(Modifier.matchParentSize().padding(GlassTabsInset)) {
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
                    CompositionLocalProvider(LocalContentColor provides colors.onSurface) { cell(option, isSelected) }
                }
            }
        }
        // 气泡画在格子上面，不接手指（不挡点击）
        GlassLensDrop(
            state = lens,
            backdrop = rememberCombinedBackdrop(layer.backdrop, capsule, accent),
            count = count,
            modifier = Modifier.matchParentSize().padding(GlassTabsInset),
        )
    }
}

/** 胶囊里那块放大镜的状态：[position] 停在第几格（拖动时在两格之间），[press] 浮起了几成（0 = 落着，1 = 气泡）。 */
@Stable
private class GlassLens(initial: Float) {
    val position = Animatable(initial)
    val press = Animatable(0f)
}

/** 一格宽的放大镜，按 [state] 摆位置、浮起成气泡；它看到的是 [backdrop]。 */
@Composable
private fun GlassLensDrop(state: GlassLens, backdrop: Backdrop, count: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.isDark()
    // 落着时的那块底色：在带字玻璃上淡淡地压一层，透过它的那一格是主色
    val rest = colors.onSurface.copy(alpha = if (dark) 0.14f else 0.08f)
    val highlight = if (dark) LensDarkHighlight else LensLightHighlight
    Box(
        modifier.layout { measurable, constraints ->
            val cellWidth = constraints.maxWidth / count
            val placeable = measurable.measure(Constraints.fixed(cellWidth, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place((state.position.value * cellWidth).roundToInt(), 0)
            }
        }.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                val p = state.press.value.coerceIn(0f, 1f)
                if (p > 0f) lens(LensRefractionHeight.toPx() * p, LensRefractionAmount.toPx() * p, chromaticAberration = true)
            },
            highlight = { highlight.copy(alpha = highlight.alpha * state.press.value.coerceIn(0f, 1f)) },
            shadow = { LensShadow.copy(alpha = state.press.value.coerceIn(0f, 1f)) },
            layerBlock = {
                // 浮起时长成气泡（比胶囊还高）；滑得快时顺着走的方向略拉长、竖向收一点
                val grow = 1f + (LensGrow - 1f) * state.press.value
                val stretch = (abs(state.position.velocity) * LensStretchPerVelocity).coerceAtMost(LensStretchMax)
                scaleX = grow * (1f + stretch)
                scaleY = grow * (1f - stretch / 2f)
            },
            onDrawSurface = { drawRect(rest, alpha = (1f - state.press.value).coerceIn(0f, 1f)) },
        ),
    )
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
/** 气泡比落着时大几成、透过它的那一格放大几成；边缘折射的带宽与强度（Android 13 起）。 */
private const val LensGrow = 1.36f
private const val LensMagnify = 0.2f
private val LensRefractionHeight = 10.dp
private val LensRefractionAmount = 14.dp

/** 每单位速度（格 / 秒）拉长几成，最多拉长几成。 */
private const val LensStretchPerVelocity = 0.04f
private const val LensStretchMax = 0.25f

private val LensLightHighlight = Highlight(alpha = 1f)
private val LensDarkHighlight = Highlight(alpha = 0.6f)
private val LensShadow = Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.16f))
