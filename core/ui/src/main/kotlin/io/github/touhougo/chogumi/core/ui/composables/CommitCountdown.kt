package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import io.github.touhougo.chogumi.core.model.commitClockNanos
import io.github.touhougo.chogumi.core.model.commitRemainingFraction
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 离 [commitAtNanos] 这一轮提交还剩几成（[windowNanos] 是这种待提交的整段窗口）——待提交倒计时的**唯一画法来源**：
 * 底边细条（[commitCountdownBar]：EP 按钮、海报的待提交胶囊）、章节方格「看到这里」那一格的环都只读它。
 *
 * 每帧按真实时间现算（[commitRemainingFraction]）：帧只负责「该重画了」，剩多少由时钟决定，系统动画倍率改不了它，
 * 走完的那一刻就是真正提交的那一刻。离开再回来接着走、不从头重来；提交时刻顺延（连点、改选）时随之回满。
 * 只在画的时候读（`drawBehind` 之类），每帧只重画、不重组。
 */
@Composable
internal fun rememberCommitRemaining(commitAtNanos: Long?, windowNanos: Long): FloatState {
    val remaining = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(commitAtNanos, windowNanos) {
        val commitAt = commitAtNanos ?: return@LaunchedEffect
        do {
            remaining.floatValue = commitRemainingFraction(commitAt, commitClockNanos(), windowNanos)
            withFrameNanos { }
        } while (remaining.floatValue > 0f)
    }
    return remaining
}

/**
 * 待提交的底边细条（全 App 唯一画法）：高 [ChogumiDimens.CommitBarHeight]、[color] 实色，从起始边量起，长度是 [remaining]
 * （[rememberCommitRemaining]）。画在所在容器的底边，由容器的形状裁切；[remaining] 为 null 时不画（没有待提交）。
 */
internal fun Modifier.commitCountdownBar(remaining: FloatState?, color: Color): Modifier = drawBehind {
    if (remaining == null) return@drawBehind
    val barHeight = ChogumiDimens.CommitBarHeight.toPx()
    val length = size.width * remaining.floatValue
    val start = if (layoutDirection == LayoutDirection.Rtl) size.width - length else 0f
    drawRect(
        color = color,
        topLeft = Offset(start, size.height - barHeight),
        size = Size(length, barHeight),
    )
}
