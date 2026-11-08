package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 尺子的拖动内核（全 App 一份：章节的格子尺 [TileRuler] 与探索的年代尺共用）：一格一样宽的横排，横着拖，对准的那一格就是值，
 * 松手吸到一格上（[flingBehavior]）。由 [rememberRulerTrack] 取得，[state] 交给尺子自己的 `LazyRow`。
 *
 * 「对准」的参照点由尺子的 `contentPadding` 定：第 0 格在滚动 0 时正好对准（格子尺两边留半屏、对准正中；年代尺左边留 16、对准第一格）。
 */
@Stable
class RulerTrack internal constructor(
    val state: LazyListState,
    val flingBehavior: FlingBehavior,
    /** 一格（含格距）的像素宽。 */
    internal val stepPx: Int,
) {
    /**
     * 尺子正自己滑向调用方给的值（不是手在拖）：途经的格不报。用计数不用开关：连点加号时新一段动画先开始、
     * 旧一段后收尾，计数不会被旧的那段清零。
     */
    internal var moving by mutableIntStateOf(0)

    /** 此刻对准的那一格：拖动、甩动途中每帧跟着变（读数用它，取数据别用它，见 [settled]）。 */
    val aimed: Int by derivedStateOf { (scrolledPx().toFloat() / stepPx).roundToInt() }

    /**
     * 停稳了：不在滚动，而且正好停在一格上（docs/code-standards.md「没在滚」不等于「停稳了」：手刚抬起、甩动还没接手的那一刻
     * 也不在滚动，却停在两格之间）。按它换下面的内容、去取数据。
     */
    val settled: Boolean by derivedStateOf {
        !state.isScrollInProgress && abs(scrolledPx() - aimed * stepPx) <= SettledSlackPx
    }

    /** 列表滚过了多少像素：每格一样宽，第一格（含格距）的整数倍加上第一格滚出去的部分。 */
    internal fun scrolledPx(): Int = state.firstVisibleItemIndex * stepPx + state.firstVisibleItemScrollOffset
}

/**
 * 取一份尺子的拖动内核。一格宽 [cell]、格距 [gap]（与尺子 `LazyRow` 的项宽、`spacedBy` 一致），吸附在 [snapPosition]。
 *
 * 值 [value] 由调用方持有：拖动、甩动途中对准的格变了经 [onValueChange] 报出，每过一格轻震一下；调用方改了值（加减按钮、输入、
 * 点某一格）尺子滑过去，隔着不止一屏就直接跳。手正拖着或甩着时不跟调用方的值：那时的值是尺子自己报出去又传回来的，
 * 晚一帧到，跟上去就会把尺子往回拽（docs/code-standards.md「报出去的值晚一帧回来」）。
 * [onFollowingFinger] 报出「手正拖着或甩着」：这时值每帧都在变，读数不该翻动。
 */
@Composable
fun rememberRulerTrack(
    value: Int,
    last: Int,
    cell: Dp,
    gap: Dp,
    snapPosition: SnapPosition,
    onValueChange: (Int) -> Unit,
    onFollowingFinger: (Boolean) -> Unit = {},
): RulerTrack {
    val density = LocalDensity.current
    val stepPx = with(density) { cell.roundToPx() + gap.roundToPx() }
    val shown = value.coerceIn(0, last)
    val current by rememberUpdatedState(shown)
    val change by rememberUpdatedState(onValueChange)
    val following by rememberUpdatedState(onFollowingFinger)
    val haptics = LocalHapticFeedback.current
    val state = rememberLazyListState(initialFirstVisibleItemIndex = shown)
    val fling = rememberSnapFlingBehavior(state, snapPosition)
    val track = remember(state, fling, stepPx) { RulerTrack(state, fling, stepPx) }

    LaunchedEffect(track) { snapshotFlow { state.isScrollInProgress && track.moving == 0 }.collect { following(it) } }
    // 手拖、甩动途中对准的换了一格：报出去，每过一格轻震一下
    LaunchedEffect(track, last) {
        snapshotFlow { track.aimed.coerceIn(0, last) }.collect { index ->
            if (track.moving > 0 || index == current) return@collect
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            change(index)
        }
    }
    // 调用方改了值：滑过去（几何走 spatial），隔着不止一屏就直接跳
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(shown, track) {
        if (track.aimed == shown || (state.isScrollInProgress && track.moving == 0)) return@LaunchedEffect
        track.moving++
        try {
            if (abs(shown - track.aimed) > state.layoutInfo.visibleItemsInfo.size) {
                state.scrollToItem(shown)
            } else {
                state.animateScrollBy((shown * stepPx - track.scrolledPx()).toFloat(), spatial)
            }
        } finally {
            track.moving--
        }
    }
    return track
}

/** 「停在一格上」容许的像素误差：吸附按 dp 算的留白取整，可能差 1 像素。 */
private const val SettledSlackPx = 1
