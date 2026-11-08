package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 事实 chip（原型 `.fact`，只展示不可点）：角色 / 人物页头部与目录页头部共用。
 *
 * 最小高 32（字大时随行高长高）、圆角 8（= `CornerSmall`）、左右 12、图字间距 6、图标 18；`surfaceContainer` 底、
 * 字 `onSurface`、图标 `onSurfaceVariant`（设计决定，not in M3，原型值）。
 *
 * @param icon 前置图标；null 不画。
 */
@Composable
fun FactChip(text: String, @DrawableRes icon: Int?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = FactChipHeight)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(
                painter = painterResource(it),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 事实 chip 的占位（骨架用）：照常排版一个不可见的 [FactChip]（样例文字约四个字），所以字号放大时与真实 chip 一起长高。 */
@Composable
fun FactChipSkeleton(modifier: Modifier = Modifier) {
    SkeletonStandIn(modifier) { FactChip(text = FACT_CHIP_SAMPLE, icon = null) }
}

/** 事实 chip 最小高 32（同 M3 chip 容器高，`component-tokens.md` Chips）。 */
private val FactChipHeight = 32.dp
private const val FACT_CHIP_SAMPLE = "0000"
