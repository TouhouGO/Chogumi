package io.github.touhougo.chogumi.core.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlin.math.abs
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 手指在一块玻璃上：按下时玻璃浮起（[lift]，0 = 落着，1 = 浮起），手指按着挪动时玻璃被拉向手指（[pull]，带阻尼，越拉越拉不动），
 * 沿拉的方向拉长、横向收窄一点；松手弹回原位。规格取主题的 expressive 空间弹簧（会过冲，像水滴；系统关掉动画时瞬时）。
 *
 * 只观察手势、不消费：点击、选中照常由调用处的 `clickable` / `selectable` 处理，按下的反馈就是玻璃本身，不叠水波纹。
 * 浮起与拉动只在绘制阶段读（[transform]），按下与挪动都不重组。
 */
@Stable
class GlassTouch internal constructor(
    private val scope: CoroutineScope,
    private val spec: FiniteAnimationSpec<Float>,
    private val offsetSpec: FiniteAnimationSpec<Offset>,
) {
    internal val lift = Animatable(0f)
    internal val pull = Animatable(Offset.Zero, Offset.VectorConverter)

    internal fun press() {
        scope.launch { lift.animateTo(1f, spec) }
    }

    internal fun drag(fromDown: Offset, reach: Float) {
        // 阻尼：拉得越远越拉不动，最多被拉出 reach
        val damped = Offset(reach * tanh(fromDown.x * PullRatio / reach), reach * tanh(fromDown.y * PullRatio / reach))
        scope.launch { pull.snapTo(damped) }
    }

    internal fun release() {
        scope.launch { lift.animateTo(0f, spec) }
        scope.launch { pull.animateTo(Offset.Zero, offsetSpec) }
    }

    /**
     * 浮起与拉动落到一层上：玻璃本身（[glass] 的 `layerBlock`）与玻璃上的内容（[glassContent]）用同一个变换，一起浮、一起走。
     * [liftBy] 是浮起时短边多出的量。
     */
    internal fun GraphicsLayerScope.transform(liftBy: Dp) {
        val reach = GlassReach.toPx()
        val p = pull.value
        val grow = 1f + lift.value * (liftBy.toPx() / size.minDimension.coerceAtLeast(1f))
        val alongX = abs(p.x) / reach * StretchMax
        val alongY = abs(p.y) / reach * StretchMax
        scaleX = grow * (1f + alongX - alongY / 2f)
        scaleY = grow * (1f + alongY - alongX / 2f)
        translationX = p.x
        translationY = p.y
    }
}

@Composable
fun rememberGlassTouch(): GlassTouch {
    val scope = rememberCoroutineScope()
    val motion = LocalAppMotion.current.expressive
    val spec = motion.fastSpatialSpec<Float>()
    val offsetSpec = motion.fastSpatialSpec<Offset>()
    return remember(scope, spec, offsetSpec) { GlassTouch(scope, spec, offsetSpec) }
}

/** 让 [touch] 跟着这个节点上的手指：按下浮起、挪动拉过去、松开或取消弹回。只观察，不消费手势。 */
fun Modifier.glassTouch(touch: GlassTouch): Modifier = pointerInput(touch) {
    val reach = GlassReach.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        touch.press()
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            touch.drag(change.position - down.position, reach)
        }
        touch.release()
    }
}

/** 玻璃上的内容（图标、文字、选中的那滴）跟着 [touch] 与玻璃一起浮起、被拉动；[liftBy] 与那块玻璃的相同。 */
fun Modifier.glassContent(touch: GlassTouch, liftBy: Dp): Modifier = graphicsLayer { with(touch) { transform(liftBy) } }

/** 浮起时短边多出的量：圆钮、主操作这类小块。 */
internal val GlassLift = 8.dp

/** 手指最多把玻璃拉出多远。 */
private val GlassReach = 10.dp

/** 手指挪动的距离按这个比例拉动玻璃（再经阻尼）。 */
private const val PullRatio = 0.4f

/** 被拉到最远时沿拉的方向拉长几成。 */
private const val StretchMax = 0.08f
