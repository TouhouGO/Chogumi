package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.Dp

/**
 * 列表页的首屏骨架（全仓列表页首屏的唯一画法）：按真实列表的 [contentPadding] 与行间距 [itemGap]，
 * 竖排 [rows] 行由调用方给出的行骨架 [row]（第 index / count 行）。行骨架与真实行走同一个组件框架
 * （多为 [SegmentedItem]），内容到了在骨架上原位淡入（[SkeletonCrossfade]）。
 *
 * 讨论版、评论、目录、日志、关联人物等列表页首屏都不许整屏居中一个加载圈，
 * 契约见 `PageLoadingSkeletonContractTest`。
 *
 * 列表头几项是筛选行这类页头时，用 [header] 画它们的占位（排在第一行之前，间距同行间距）。
 *
 * 行数取能铺满一屏的量（设计决定，not in M3）；骨架不滚动，超出屏幕的部分裁掉。
 */
@Composable
fun ListPageSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    itemGap: Dp = ListItemDefaults.SegmentedGap,
    rows: Int = LIST_PAGE_SKELETON_ROWS,
    header: (@Composable () -> Unit)? = null,
    row: @Composable (index: Int, count: Int) -> Unit,
) {
    Box(modifier.fillMaxSize().clipToBounds()) {
        Column(
            modifier = Modifier
                .wrapContentHeight(Alignment.Top, unbounded = true)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(itemGap),
        ) {
            header?.invoke()
            repeat(rows) { row(it, rows) }
        }
    }
}

private const val LIST_PAGE_SKELETON_ROWS = 8
