package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import kotlin.math.roundToInt

/**
 * 十格评分条（原型 `.rating`）：1–10 分十个格子连成一条，点到或拖到第几格就是几分，再点一下当前分就清掉（0 = 未评分）。
 * 与章节方格同一种表达：低于当前分的格是 primary container，当前分那一格 primary 实色、变圆并放大一点，其余是空位；
 * 两头是圆的，格间距同连接按钮组。整条高 48dp：每一格都够得上最小触控区。
 *
 * 对读屏是一个滑块：念 [description] 与 [stateDescription]（「8 分，力荐」），能直接设值。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RatingTiles(
    value: Int,
    onValueChange: (Int) -> Unit,
    description: String,
    stateDescription: String,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    var width by remember { mutableIntStateOf(0) }
    val rtl by rememberUpdatedState(LocalLayoutDirection.current == LayoutDirection.Rtl)
    fun valueAt(x: Float): Int = ratingTileAt(x, width, rtl, fallback = current)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ChogumiDimens.MinTouchTarget)
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val tapped = valueAt(offset.x)
                    change(if (tapped == current) 0 else tapped)
                }
            }
            .pointerInput(Unit) {
                // 这一次拖动报到哪一格了：在手势里自己记。[current] 要等下一帧重组才跟上，一帧里连来几个触摸事件时
                // 拿它判重会把同一格连报几次、连震几下
                var reported = 0
                fun dragTo(to: Int) {
                    if (to == reported) return
                    reported = to
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    change(to)
                }
                detectHorizontalDragGestures(
                    onDragStart = {
                        reported = current
                        dragTo(valueAt(it.x))
                    },
                ) { pointer, _ ->
                    pointer.consume()
                    dragTo(valueAt(pointer.position.x))
                }
            }
            .clearAndSetSemantics {
                contentDescription = description
                this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..MAX_RATING.toFloat(), steps = MAX_RATING - 1)
                setProgress { target ->
                    change(target.roundToInt().coerceIn(0, MAX_RATING))
                    true
                }
            },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (score in 1..MAX_RATING) {
            RatingTile(
                score = score,
                kind = when {
                    score == value -> RatingTileKind.SELECTED
                    score < value -> RatingTileKind.BELOW
                    else -> RatingTileKind.EMPTY
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 手指在第几格：按整条的宽度均分（格间距很窄，算进靠起点那一格）；从右到左的布局里第 1 格在右边，横坐标先镜像。
 * 还没量出宽度时返回 [fallback]。
 */
internal fun ratingTileAt(x: Float, width: Int, rtl: Boolean, fallback: Int): Int {
    if (width == 0) return fallback
    val fromStart = if (rtl) width - x else x
    return ((fromStart / width) * MAX_RATING).toInt().coerceIn(0, MAX_RATING - 1) + 1
}

private enum class RatingTileKind { EMPTY, BELOW, SELECTED }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RatingTile(score: Int, kind: RatingTileKind, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val motion = MaterialTheme.motionScheme
    val full = CornerSize(50)
    val inner = shapes.extraSmall.topStart
    val target: CornerBasedShape = when {
        kind == RatingTileKind.SELECTED -> shapes.extraSmall.copy(all = full)
        else -> shapes.extraSmall.copy(
            topStart = if (score == 1) full else inner,
            bottomStart = if (score == 1) full else inner,
            topEnd = if (score == MAX_RATING) full else inner,
            bottomEnd = if (score == MAX_RATING) full else inner,
        )
    }
    val shape = rememberMorphingShape(target, motion.fastSpatialSpec())
    val container by animateColorAsState(
        when (kind) {
            RatingTileKind.SELECTED -> colors.primary
            RatingTileKind.BELOW -> colors.primaryContainer
            RatingTileKind.EMPTY -> colors.surfaceContainerHighest
        },
        motion.fastEffectsSpec(),
        label = "ratingTileContainer",
    )
    val content by animateColorAsState(
        when (kind) {
            RatingTileKind.SELECTED -> colors.onPrimary
            RatingTileKind.BELOW -> colors.onPrimaryContainer
            RatingTileKind.EMPTY -> colors.onSurfaceVariant
        },
        motion.fastEffectsSpec(),
        label = "ratingTileContent",
    )
    val scale by animateFloatAsState(if (kind == RatingTileKind.SELECTED) SELECTED_SCALE else 1f, motion.fastSpatialSpec(), label = "ratingTileScale")
    Box(
        modifier = modifier
            // 放大后的那一格正好是整条的高：上下不出自己的边界
            .fillMaxHeight(1f / SELECTED_SCALE)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // 两头那格放大时朝里长：整条的外缘不动，不出自己的边界
                transformOrigin = when (score) {
                    1 -> TransformOrigin(0f, 0.5f)
                    MAX_RATING -> TransformOrigin(1f, 0.5f)
                    else -> TransformOrigin.Center
                }
            }
            .clip(shape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = score.toString(),
            style = if (kind == RatingTileKind.SELECTED) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
        )
    }
}

/** 满分（bgm.tv 评分 1–10，0 是未评分）。 */
private const val MAX_RATING = 10

/** 当前分那一格放大多少（设计稿 `.rating .r.c{transform:scale(1.06)}`，not in M3）。 */
private const val SELECTED_SCALE = 1.06f
