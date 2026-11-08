package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.occurrenceKeys
import io.github.touhougo.chogumi.core.model.FactRow

/**
 * 键值资料面板（原型资料面板、截图 03 与 33）：标题下面是分段列表，
 * 每行「键（`labelMedium`、弱化）/ 值（`bodyMedium`）」。全仓的资料面板只此一处：
 * 作品详情的「ⓘ 资料」与人物、角色的「全部资料」都用它。
 *
 * 行由调用方的纯函数给出（如 `subjectInfoPartition`、`monoInfoRows`），这里只渲染；不折叠，全部列出。
 * 面板按内容高（[PageModalSheet] 非满高），资料长到顶边距时在面板里滚。
 */
@Composable
fun FactRowsSheet(
    title: String,
    rows: List<FactRow>,
    onDismiss: () -> Unit,
) {
    PageModalSheet(onDismiss = onDismiss, fullHeight = false) {
        SheetTitleRow(title)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            val keys = occurrenceKeys(rows) { it.key }
            itemsIndexed(rows, key = { index, _ -> keys[index] }) { index, row ->
                FactRowItem(row = row, index = index, count = rows.size)
            }
        }
    }
}

/** 键值资料的共用分段行：保留原文，不提供点击或人名拆分。 */
@Composable
fun FactRowItem(row: FactRow, index: Int, count: Int, modifier: Modifier = Modifier) {
    SegmentedItem(index = index, count = count, onClick = null, modifier = modifier.fillMaxWidth()) {
        Column {
            Text(
                text = row.key,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = row.value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
