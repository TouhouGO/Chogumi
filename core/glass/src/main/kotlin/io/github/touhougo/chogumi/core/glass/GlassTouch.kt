package io.github.touhougo.chogumi.core.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 手指按在一块玻璃上：玻璃连同上面的内容整体浮起、放大一圈（[lift]，0 = 落着，1 = 浮起），松手落回。形状不变，不被拉扯。
 * 规格取主题的 expressive 空间弹簧（落回时略微过冲，像水滴；系统关掉动画时瞬时）。
 *
 * 只观察手势、不消费：点击照常由调用处的 `clickable` 处理，按下的反馈就是玻璃本身，不叠水波纹。
 * 浮起只在绘制阶段读（[transform]），按下不重组。
 */
@Stable
class GlassTouch internal constructor(private val scope: CoroutineScope, private val spec: FiniteAnimationSpec<Float>) {
    internal val lift = Animatable(0f)

    internal fun press() {
        scope.launch { lift.animateTo(1f, spec) }
    }

    internal fun release() {
        scope.launch { lift.animateTo(0f, spec) }
    }

    /** 浮起落到一层上：玻璃本身（[glass] 的 `layerBlock`）与玻璃上的内容（[glassContent]）同一个缩放。[liftBy] 是浮起时短边多出的量。 */
    internal fun GraphicsLayerScope.transform(liftBy: Dp) {
        val grow = 1f + lift.value * (liftBy.toPx() / size.minDimension.coerceAtLeast(1f))
        scaleX = grow
        scaleY = grow
    }
}

@Composable
fun rememberGlassTouch(): GlassTouch {
    val scope = rememberCoroutineScope()
    val spec = LocalAppMotion.current.expressive.fastSpatialSpec<Float>()
    return remember(scope, spec) { GlassTouch(scope, spec) }
}

/** 让 [touch] 跟着这个节点上的手指：按下浮起，松开或取消落回。只观察，不消费手势。 */
fun Modifier.glassTouch(touch: GlassTouch): Modifier = pointerInput(touch) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        touch.press()
        waitForUpOrCancellation(PointerEventPass.Initial)
        touch.release()
    }
}

/** 玻璃上的内容（图标、文字）跟着 [touch] 与玻璃一起浮起；[liftBy] 与那块玻璃的相同。 */
fun Modifier.glassContent(touch: GlassTouch, liftBy: Dp): Modifier = graphicsLayer { with(touch) { transform(liftBy) } }

/** 浮起时短边多出的量：圆钮、主操作这类小块。 */
internal val GlassLift = 8.dp
