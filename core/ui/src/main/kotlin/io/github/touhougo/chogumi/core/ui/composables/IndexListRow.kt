package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R

/** 列表项里的用户头像直径 40 = M3 List 的 `ItemLeadingAvatarSize`（component-tokens.md）：谁在看、评论、讨论版、目录共用。 */
val ListAvatarSize = 40.dp

/**
 * 收录某作品 / 某角色 / 某人物的目录一行：创建者头像 + 标题 + 创建者 + 尾部条目数（[CountLabel]），
 * 作品社区的目录子页与角色 / 人物页的关联目录面板共用（第 [position] / [count] 项）。
 * 条目数固定在行尾：接在创建者名字后面的话，名字长短不一，数字左右参差；做法同帖子列表的回复数。
 * 私密目录在标题后面带一把锁（旧站同样）；标题太长时截标题，锁留着。
 */
@Composable
fun IndexListRow(
    index: IndexBo,
    position: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val creator = index.creator?.let(::displayNameOf).orEmpty()
    SegmentedItem(
        index = position,
        count = count,
        onClick = onClick,
        modifier = modifier,
        leadingContent = { CircleAvatar(index.creator?.avatarUrl, description = null, size = ListAvatarSize) },
        trailingContent = {
            CountLabel(
                icon = R.drawable.list_alt_24,
                count = index.total,
                description = pluralStringResource(R.plurals.index_items_count, index.total, index.total),
            )
        },
        supportingContent = creator.takeIf { it.isNotBlank() }?.let { name ->
            { Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                index.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (index.isPrivate) {
                Icon(
                    painterResource(R.drawable.lock_24),
                    contentDescription = stringResource(R.string.index_private),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(PrivateIconSize),
                )
            }
        }
    }
}

/** 标题后锁图标的边长：与 `bodyLarge` 一行字差不多高（设计决定，not in M3）。 */
private val PrivateIconSize = 16.dp

/** 目录行的占位（骨架用）：与 [IndexListRow] 同尺寸头像。 */
@Composable
fun IndexListRowSkeleton(index: Int, count: Int, modifier: Modifier = Modifier) =
    AvatarRowSkeleton(index, count, ListAvatarSize, modifier)

/**
 * 「圆头像 + 一行标题 + 一行副行」列表行的占位，全仓唯一一份（目录行、角色 / 人物行、作品人物 tab 共用）：
 * 与真实行同一个 [SegmentedItem] 框架，头像边长 [avatarSize] 由调用方传真实行的同一个常量，内容出现时行高不变。
 * 两段字条的宽度是设计决定（not in M3）。
 */
@Composable
fun AvatarRowSkeleton(index: Int, count: Int, avatarSize: Dp, modifier: Modifier = Modifier) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = modifier,
        leadingContent = { SkeletonBox(Modifier.size(avatarSize), shape = CircleShape) },
        supportingContent = { SkeletonText(MaterialTheme.typography.bodySmall, widthFraction = 0.3f) },
    ) {
        SkeletonText(MaterialTheme.typography.bodyLarge, widthFraction = 0.5f)
    }
}
