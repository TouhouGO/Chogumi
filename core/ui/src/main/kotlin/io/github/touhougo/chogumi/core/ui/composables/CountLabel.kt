package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 列表行里的「图标 + 数字」计数（帖子的回复数、目录的条目数共用）：图标 16、间距 4、`labelMedium`。
 * 读屏念 [description]（如「12 条回复」），不念图标 + 裸数字。几何是设计决定（not in M3）。
 *
 * 数字占固定宽（[RESERVED_DIGITS] 位等宽数字，`tnum`）并右对齐：位数不同的行，图标的左缘与数字的右缘
 * 都在同一条竖线上；否则 47 与 128 的图标左右错开。超过这个位数才往左撑开。
 */
@Composable
fun CountLabel(@DrawableRes icon: Int, count: Int, description: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val numberWidth = remember(style, density) {
        with(density) { measurer.measure("0".repeat(RESERVED_DIGITS), style).size.width.toDp() }
    }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(count.toString(), style = style, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = numberWidth))
    }
}

/** 数字预留的位数：回复数、目录条目数绝大多数不超过 3 位；预留太多，个位数前会空出一大截（设计决定，not in M3）。 */
private const val RESERVED_DIGITS = 3
