package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.res.R

/**
 * 探索页整理货架时的顶栏：占住搜索栏的位置，标题「整理货架」，右边「添加货架」与「完成」（页面动作放顶栏右侧；完成是填充按钮，
 * 与面板标题行右端的确认同一个位置与强调）。手势引导里整理的示意画的也是它。
 */
@Composable
fun ArrangeBar(onAdd: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier,
    title: String = stringResource(R.string.explore_arrange_title),
    addLabel: String = stringResource(R.string.explore_arrange_add), tagPrefix: String = "explore_arrange") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 8.dp)
            .testTag("${tagPrefix}_bar"),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAdd, modifier = Modifier.testTag("${tagPrefix}_add")) {
            Icon(painterResource(R.drawable.add_24), contentDescription = addLabel)
        }
        FilledIconButton(onClick = onDone, modifier = Modifier.testTag("${tagPrefix}_done")) {
            Icon(painterResource(R.drawable.check_24), contentDescription = stringResource(R.string.explore_arrange_done))
        }
    }
}
