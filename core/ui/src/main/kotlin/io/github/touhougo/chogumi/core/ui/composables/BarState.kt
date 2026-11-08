package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * 会让位的顶栏、会收起的头部用的 [TopAppBarState]（[PageScaffold]、[CollapsibleHeader]），重建前后认的是「收起了几成」。
 *
 * 恢复后的第一帧就按存下的像素摆好：从下一页返回时页面重新组合，顶栏若先全开一帧再收回，Scaffold 给内容的上边距跟着
 * 多出再缩回一个顶栏高，列表整体下移又上移；容器转换把列表里起点的这段位移当成滚动，直接加在飞回来的整页上，整页上跳一个顶栏高。
 *
 * 只存像素不够：改了显示大小（换了密度）Activity 重建，顶栏按新密度量出新的可收起高度，旧的像素偏移让收满的顶栏停在半截。
 * 所以同时存收起比例：量出的可收起高度与存下的不同时，按比例重新摆一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberBarState(): TopAppBarState {
    val saved = rememberSaveable(saver = SavedBarState.Saver) { SavedBarState(TopAppBarState(UNMEASURED_LIMIT, 0f, 0f), restoredFraction = 0f, restoredLimit = UNMEASURED_LIMIT) }
    LaunchedEffect(saved) {
        if (saved.restoredFraction == 0f) return@LaunchedEffect
        val limit = snapshotFlow { saved.state.heightOffsetLimit }.first { it != UNMEASURED_LIMIT }
        if (limit != saved.restoredLimit) saved.state.heightOffset = saved.restoredFraction * limit
    }
    return saved.state
}

/** 还没量出可收起高度时的上限（同 M3 的初值）。 */
private const val UNMEASURED_LIMIT = -Float.MAX_VALUE

@OptIn(ExperimentalMaterial3Api::class)
private class SavedBarState(val state: TopAppBarState, val restoredFraction: Float, val restoredLimit: Float) {
    companion object {
        val Saver = Saver<SavedBarState, List<Float>>(
            save = { listOf(it.state.collapsedFraction, it.state.contentOffset, it.state.heightOffsetLimit) },
            restore = { (fraction, contentOffset, limit) ->
                // 上限仍记为没量过：这一帧的量法会写入真实的上限，上面的 LaunchedEffect 据此判断要不要按比例重摆。
                // 全开就是 0：0 乘负的上限得 -0f，会被当成另一个值
                val offset = if (limit == UNMEASURED_LIMIT || fraction == 0f) 0f else fraction * limit
                SavedBarState(TopAppBarState(UNMEASURED_LIMIT, offset, contentOffset), restoredFraction = fraction, restoredLimit = limit)
            },
        )
    }
}
