package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.res.R

/**
 * 错误块：消息 + 重试，全 App 唯一画法（只读页、楼层评论区、条目详情与角色人物页各区块共用；
 * 区块里的紧凑样式见 [compact]）。
 *
 * 两档几何由 [compact] 决定，调用方只用 [modifier] 管外部布局（铺满宽度、居中等）：
 * - 默认：整页 / 独立块，四周 32dp，消息沿用 [LocalTextStyle]，按钮上方空 8dp；
 * - [compact] = true：嵌在长页面区块里，水平 16dp、垂直 12dp，消息 bodyMedium，按钮紧贴消息。
 *
 * M3 没有「错误块」这一组件或版式规范。M3 规范内的只有：消息用 `colorScheme.error` 色彩角色、
 * 按钮用 TextButton、bodyMedium 字阶本身；32dp / 8dp / 16dp / 12dp 这几个间距、以及哪一档用
 * 哪个字阶，都是我的设计决定（不在 M3 中）。
 */
@Composable
fun RetryError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val padding = if (compact) PaddingValues(horizontal = 16.dp, vertical = 12.dp) else PaddingValues(32.dp)
    Column(modifier.padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = message,
            style = if (compact) MaterialTheme.typography.bodyMedium else LocalTextStyle.current,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = if (compact) 0.dp else 8.dp)) {
            Text(stringResource(R.string.retry))
        }
    }
}

/** 以失败原因画同一个错误块（文案按当前语言取，见 [text]）。 */
@Composable
fun RetryError(
    failure: Failure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) = RetryError(failure.text(), onRetry, modifier, compact)
