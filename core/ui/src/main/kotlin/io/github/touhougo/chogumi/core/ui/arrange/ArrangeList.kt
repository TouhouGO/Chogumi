package io.github.touhougo.chogumi.core.ui.arrange

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.containerColor
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.ReorderableList
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem

/**
 * 长按进入整理：按住时有一下触感；读屏用长按动作。只旁听标题这一块：标题行尾的箭头按钮自己消费了按下，长按箭头不算。
 */
@Composable
fun arrangeOnLongPress(onArrange: () -> Unit, label: String): Modifier {
    val haptic = LocalHapticFeedback.current
    val arrange by rememberUpdatedState(onArrange)
    return Modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    arrange()
                },
            )
        }
        .semantics { onLongClick(label) { arrange(); true } }
}

/** 通用整理列表，拖拽和左滑只由 ReorderableList 处理；调用方仅提供领域内容与持久化动作。 */
@Composable
fun <T> ArrangeList(
    rows: List<T>,
    key: (T) -> String,
    title: @Composable (T) -> String,
    detail: @Composable (T) -> List<String>,
    leading: @Composable (T) -> Unit,
    onMove: (List<String>) -> Unit,
    onRemove: (T) -> Unit,
    onEdit: (T) -> Unit,
    empty: String,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        EmptyLine(empty, modifier.testTag("${tagPrefix}_empty"))
        return
    }
    ReorderableList(
        items = rows, key = key, onMove = onMove, onRemove = onRemove,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        modifier = modifier,
    ) { row ->
        SegmentedItem(
            index = segmentIndex, count = segmentCount, onClick = { onEdit(row) },
            containerColor = containerColor,
            leadingContent = { leading(row) },
            trailingContent = {
                Box(Modifier.size(48.dp).dragHandle().testTag("${tagPrefix}_handle_${key(row)}"), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.drag_indicator_24), stringResource(R.string.reorder_drag_handle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            supportingContent = { MetaText(detail(row), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.testTag("${tagPrefix}_row_${key(row)}"),
        ) { Text(title(row), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
