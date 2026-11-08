package io.github.touhougo.chogumi.core.ui.chart

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import io.github.touhougo.chogumi.core.ui.composables.RollingText

/**
 * 大数字：一个数，下面一行它是什么（「21862 人打分」）。数取 displayMedium 的强调版（M3：卡片的主数字可用 display，
 * 强调版加字重不改字号），变化时经 [RollingText] 只翻变了的那几位，[order] 是这个数的大小（定翻动方向）；
 * 名称取 labelLarge、onSurfaceVariant。条目统计的评分用它，生涯的集数、时长、看完率也走这里。
 */
@Composable
fun BigNumber(value: String, order: Int, name: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        RollingText(
            text = value,
            order = order,
            style = MaterialTheme.typography.displayMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
