package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * 网格的滚动位置跟着列表的「顺序」走：[order] 是这份列表重新排序的计数（改条件重排、新一轮搜索……），
 * 它变了，位置就换成一份从顶部开始的新的——列表「重排后回到顶部」全 App 只此一种写法。
 *
 * - 计数与位置一起存档、恢复时核对：从子页返回、旋转、左右翻页里离屏的页再出现时，计数没变就停在原处；
 *   离屏期间计数变了（在别的页改了条件，各页一起重排），再出现时从顶部开始。只把计数当 `rememberSaveable`
 *   的输入不够：恢复存档时不比输入，离屏页会拿回重排前的位置。
 * - 换的是一份新状态：新顺序第一次测量就取第 0 项，不先按旧锚点摆一帧再跳（懒列表默认按 key 锚定原先的
 *   首个可见项，重排到首位的条目会落在视口上方）。
 */
@Composable
fun rememberOrderedGridState(order: Long): LazyGridState =
    rememberSaveable(order, saver = orderedGridSaver(order)) { LazyGridState() }

/** 存「计数 + 首个可见项 + 偏移」；恢复时计数对不上就不恢复（新建一份从顶部开始的），见 [keyedListSaver]。 */
private fun orderedGridSaver(order: Long): Saver<LazyGridState, Any> = keyedListSaver(
    key = order,
    save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
    restore = { saved -> LazyGridState(saved[0] as Int, saved[1] as Int) },
)
