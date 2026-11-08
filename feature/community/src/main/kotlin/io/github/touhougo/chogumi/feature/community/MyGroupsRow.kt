package io.github.touhougo.chogumi.feature.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.GroupSummaryBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.GroupIcon
import io.github.touhougo.chogumi.core.ui.composables.PressArea
import io.github.touhougo.chogumi.core.ui.composables.SkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.rememberTextLinesHeight

/**
 * 社区「小组」页顶部那一行（原型 `.pinrow`）：我加入的小组（置顶的在前、带图钉，顺序见 `pinnedFirst`），
 * 最后一格进全部小组。横排走 [CarouselRow]；上下各 8。
 *
 * [groups] 是排好序的那一行；我加入的还在取（[joined] 等待中）时画几格占位，取不到时只剩「全部小组」。
 */
@Composable
internal fun MyGroupsRow(
    joined: Load<List<GroupSummaryBo>>,
    groups: List<GroupSummaryBo>?,
    pinned: List<String>,
    onGroupClick: (String) -> Unit,
    onAllGroups: () -> Unit,
) {
    CarouselRow(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) {
        if (joined is Load.Pending) {
            items(PLACEHOLDER_CELLS) { GroupCellSkeleton() }
        }
        items(groups.orEmpty(), key = { it.name }) { group ->
            GroupCell(
                label = group.title,
                onClick = { onGroupClick(group.name) },
                badge = group.name in pinned,
            ) { GroupIcon(group.icon, CellIconSize, MaterialTheme.shapes.large) }
        }
        item(key = ALL_GROUPS_KEY) {
            GroupCell(label = stringResource(R.string.all_groups), onClick = onAllGroups, badge = false) {
                Box(
                    Modifier.size(CellIconSize).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.grid_view_24), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 一格（原型 `.pcell`）：宽 72，64 的圆角图标 + 名字（`bodySmall`，至多两行、居中），间距 6。
 * 置顶的图钉（22 的 `primary` 圆、14 的图标）压在图标右上角、往上探出 4：这 4 算在格子里（图标框高 68、图标贴底），不画出格子外。
 */
@Composable
private fun GroupCell(label: String, onClick: () -> Unit, badge: Boolean, icon: @Composable () -> Unit) {
    PressArea(onClick = onClick, shape = MaterialTheme.shapes.large, modifier = Modifier.width(CellWidth)) {
        CellFrame(
            icon = {
                Box(Modifier.size(width = CellWidth, height = CellIconSize + PinOverhang)) {
                    Box(Modifier.align(Alignment.BottomCenter)) { icon() }
                    if (badge) PinBadge(Modifier.align(Alignment.TopEnd))
                }
            },
            label = {
                val labelStyle = MaterialTheme.typography.bodySmall
                Text(
                    text = label,
                    style = labelStyle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.heightIn(min = rememberTextLinesHeight(labelStyle, GROUP_CELL_LABEL_LINES)),
                    maxLines = GROUP_CELL_LABEL_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

@Composable
private fun GroupCellSkeleton() {
    CellFrame(
        modifier = Modifier.width(CellWidth),
        icon = {
            Box(Modifier.size(width = CellWidth, height = CellIconSize + PinOverhang), contentAlignment = Alignment.BottomCenter) {
                SkeletonBox(Modifier.size(CellIconSize), shape = MaterialTheme.shapes.large)
            }
        },
        label = { SkeletonText(MaterialTheme.typography.bodySmall, widthFraction = 0.8f, lines = GROUP_CELL_LABEL_LINES) },
    )
}

/** 一格的排版：真实格子与占位共用。 */
@Composable
private fun CellFrame(icon: @Composable () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        icon()
        label()
    }
}

@Composable
private fun PinBadge(modifier: Modifier = Modifier) {
    Box(
        modifier.size(PinBadgeSize).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.push_pin_filled_24),
            contentDescription = stringResource(R.string.group_pinned),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(PinIconSize),
        )
    }
}

// 原型 `.pcell`：格宽 72、图标 64、图钉 22（图标 14）、往上探出 4。设计决定（not in M3）。
private val CellWidth = 72.dp
private val CellIconSize = 64.dp
private val PinBadgeSize = 22.dp
private val PinIconSize = 14.dp
private val PinOverhang = 4.dp

/** 我加入的小组还没到时画几格占位：一屏宽放得下的数（设计决定，not in M3）。 */
private const val PLACEHOLDER_CELLS = 4

private const val ALL_GROUPS_KEY = "all-groups"

/** 一格的名字固定占两行（真实格子与占位同高）。 */
private const val GROUP_CELL_LABEL_LINES = 2
