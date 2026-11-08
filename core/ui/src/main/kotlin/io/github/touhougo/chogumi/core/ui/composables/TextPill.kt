package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 跟在文字行里的文字小牌，全 App 唯一画法：圆角 8dp（M3 shape small），字 labelSmallEmphasized，左右 6dp，
 * 高度跟着字号走（原型 `.sbadge`）。吐槽楼层发言人的收藏状态、楼层头部的楼主标记、好友小牌（[FriendMark]）都用它，只换色。
 */
@Composable
fun TextPill(text: String, containerColor: Color, contentColor: Color, modifier: Modifier = Modifier) {
    Surface(color = containerColor, contentColor = contentColor, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmallEmphasized,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}
