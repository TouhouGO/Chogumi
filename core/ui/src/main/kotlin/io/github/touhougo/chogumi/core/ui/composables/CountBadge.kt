package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BadgeDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 数字徽标的文字——**唯一判定点**：不大于 0 不显示（null）；M3 large badge 的标签最多 4 个字符（含「+」，
 * feedback.md Badges），所以超过 999 写作「999+」。
 */
fun countBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 999 -> "999+"
    else -> count.toString()
}

/**
 * 数字徽标（M3 large badge：16dp 高、全圆角，宽随数字伸长，error / onError，feedback.md Badges「Keep the default color
 * mapping」）——全 App 计数徽标只有这一种画法：导航图标的未读数、进度页筛选入口的已选数、分类维度按钮上的已选数。
 * [count] 不大于 0 时什么都不画。压在封面上的计数（待看的已播未看集数）换成 tertiary 一对，传 [containerColor] / [contentColor]
 * （「还没看」是提示，不是告警；我的设计决定，not in M3）。
 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = BadgeDefaults.containerColor,
    contentColor: Color = contentColorFor(containerColor),
) {
    val text = countBadgeText(count) ?: return
    Badge(modifier, containerColor = containerColor, contentColor = contentColor) { Text(text) }
}

/** 图标挂数字徽标：徽标锚在图标包围盒的右上角（M3：「anchor badges inside the icon bounding box at the upper trailing edge」）。 */
@Composable
fun CountBadgedIcon(count: Int, modifier: Modifier = Modifier, icon: @Composable BoxScope.() -> Unit) {
    BadgedBox(badge = { CountBadge(count) }, modifier = modifier, content = icon)
}
