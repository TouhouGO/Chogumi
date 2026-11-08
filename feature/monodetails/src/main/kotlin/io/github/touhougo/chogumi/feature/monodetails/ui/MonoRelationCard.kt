package io.github.touhougo.chogumi.feature.monodetails.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.MonoRelationBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.AvatarRowSkeleton
import io.github.touhougo.chogumi.core.ui.composables.CircleAvatar
import io.github.touhougo.chogumi.core.ui.composables.ListImageAvatarSize
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem

/** 关联人物 / 角色一行：头像 + 名字 + 关系（完整关联列表页用，第 [index] / [count] 项）。 */
@Composable
internal fun MonoRelationCard(
    relation: MonoRelationBo,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MonoEntityRow(
        name = displayNameOf(relation),
        caption = relation.relation,
        image = relation.image,
        index = index,
        count = count,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * 关联子页与人物出演角色子页共用的实体行；布局只在这一处维护。
 * 与作品详情人物 tab 的角色列表同一套：分段列表项 [SegmentedItem] + 56 头像 + `bodyLarge` 名字 +
 * `bodySmall` 副行 + 尾部箭头（40 头像、`bodySmall` 名字的卡片太矮）。
 */
@Composable
internal fun MonoEntityRow(
    name: String,
    caption: String,
    image: String?,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        modifier = modifier,
        leadingContent = { CircleAvatar(url = image, description = null, size = ListImageAvatarSize) },
        trailingContent = {
            Icon(
                painter = painterResource(R.drawable.chevron_right_24),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        supportingContent = caption.takeIf { it.isNotBlank() }?.let { text ->
            { Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 实体行的占位（骨架用）：与 [MonoEntityRow] 同尺寸头像。 */
@Composable
internal fun MonoEntityRowSkeleton(index: Int, count: Int, modifier: Modifier = Modifier) =
    AvatarRowSkeleton(index, count, ListImageAvatarSize, modifier)
