package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 一群人的名单面板（作品「谁在看」、楼层「谁贴了」），名单面板只有这一种框架：满高面板（[ScrollableModalSheet]，底色
 * `surfaceContainer`）→ 标题（[SheetTitleRow]）→ 一排横滑的筛选 chip（[filters]，放 [SelectableChip]）→ 人物区（[content]，
 * 占满剩下的高度，放 `ContentGrid(GridCell.Person)` 与它的加载、空、失败态）。chip 与格子画什么由调用方给。
 * [contentKey] 换了就是另一个面板（同 [ScrollableModalSheet]）。
 */
@Composable
fun PeopleListSheet(
    title: String,
    onDismiss: () -> Unit,
    filters: @Composable RowScope.() -> Unit,
    contentKey: Any? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    ScrollableModalSheet(
        onDismiss = onDismiss,
        contentKey = contentKey,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SheetTitleRow(title)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ChogumiDimens.ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = filters,
        )
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter, content = content)
    }
}
