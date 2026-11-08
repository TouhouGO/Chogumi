package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.util.lerp

/**
 * 会变形的形状：四个角各自从此刻的样子以 [spec] 走到 [target]，每帧把当下的样子作为一个形状给出。
 *
 * 形状随状态变（按下、选中、在组里的位置、与相邻格连成一段）时，由这里持有每个角的过渡，交给组件的永远是此刻的形状。
 * 不把几组官方形状直接交给组件：官方按「形状对象」记住形变动画，对象一换就从新形状起步，圆角当场跳过去。
 * 连通按钮组（[ConnectedChoiceGroup]）与章节方格共用这一份。
 */
@Composable
internal fun rememberMorphingShape(target: CornerBasedShape, spec: FiniteAnimationSpec<Float>): RoundedCornerShape =
    RoundedCornerShape(
        topStart = rememberMorphingCorner(target.topStart, spec),
        topEnd = rememberMorphingCorner(target.topEnd, spec),
        bottomEnd = rememberMorphingCorner(target.bottomEnd, spec),
        bottomStart = rememberMorphingCorner(target.bottomStart, spec),
    )

@Composable
private fun rememberMorphingCorner(target: CornerSize, spec: FiniteAnimationSpec<Float>): CornerSize {
    val corner = remember { MorphingCorner(target) }
    LaunchedEffect(target) { corner.moveTo(target, spec) }
    return corner.current
}

/** 一个角的过渡：从 [from] 到 [to]，进度由弹簧推进；途中换目标时从此刻的样子接着走，不回到起点。 */
private class MorphingCorner(initial: CornerSize) {
    private var from: CornerSize = initial
    private var to: CornerSize = initial
    private val progress = Animatable(1f)

    /** 此刻的角：走完就是目标本身，途中是两端按进度插值（读进度 = 每帧重组一次）。 */
    val current: CornerSize get() = progress.value.let { fraction -> if (fraction == 1f) to else LerpCornerSize(from, to, fraction) }

    suspend fun moveTo(target: CornerSize, spec: FiniteAnimationSpec<Float>) {
        if (target == to) return
        from = current
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
}

/** 两个角尺寸之间按 [fraction] 插值：各自按形状尺寸换成像素再插（百分比与 dp 混着也成立）；弹簧过冲时不画出负半径。 */
private data class LerpCornerSize(val from: CornerSize, val to: CornerSize, val fraction: Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float =
        lerp(from.toPx(shapeSize, density), to.toPx(shapeSize, density), fraction).coerceAtLeast(0f)
}
