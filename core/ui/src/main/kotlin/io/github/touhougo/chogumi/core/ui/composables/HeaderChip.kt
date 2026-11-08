package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * 详情页头部的一枚小标（作品页的「★ 7.2」「ⓘ 资料」，角色 / 人物页的「ⓘ 资料」），两页共用这一个。
 *
 * 最小高 28（大字号时随字高）、全圆角、左 8 / 右 10、图标 16、图字间距 4，`surfaceContainer` 底（设计决定，not in M3；
 * 圆角 = `CornerFull`）。字阶 `labelLarge`（原型 13px 不在 M3 字阶里，取最近的 14）。
 * [onClick] 为 null 时禁用：仍走可点 Surface 的布局（含 48dp 触控区），所以可点与否、骨架与真实，行高都一样。
 * [skeleton] 为 true 时照常排版（宽高与真实小标相同），但图标与文字不可见，胶囊画成占位闪光。
 */
@Composable
fun HeaderChip(
    @DrawableRes icon: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    skeleton: Boolean = false,
    label: @Composable () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Box(Modifier.heightIn(min = 28.dp)) {
            if (skeleton) SkeletonBox(Modifier.matchParentSize(), shape = CircleShape)
            Row(
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .padding(start = 8.dp, end = 10.dp)
                    .then(if (skeleton) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.labelLarge,
                    content = label,
                )
            }
        }
    }
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = modifier,
        shape = CircleShape,
        color = if (skeleton) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        content = content,
    )
}
