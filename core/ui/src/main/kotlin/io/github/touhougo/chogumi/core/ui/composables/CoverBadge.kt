package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 压在图片上的文字角标（封面的关系名、定位、职位、分数，帖子里的「长图」）。全 App 只此一种，调用方只决定贴哪个角
 * （`align` 后加 [CoverBadgeInset] 的边距）。
 *
 * - 色调底：`secondaryContainer` / `onSecondaryContainer`，实色不加 alpha。角标压在明暗各异的位图上，
 *   这对角色的对比度按实色算，掺进底下的图就不再有保证（tokens.md「Contrast levels」：小字最低 4.5:1）。
 *   选这对角色是我的设计决定（not in M3）：M3 的 badge 只给数量与未读用。
 * - 圆角 8dp，与 M3 large badge 的 8dp 相同（feedback.md Badges），用 `shapes.small`。
 * - 字阶 `labelMedium`，左右 6dp、上下 2dp（我的设计决定，not in M3）。
 */
@Composable
fun CoverBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = CoverBadgeTextPadding, vertical = 2.dp),
        )
    }
}

/** 角标离图片边缘的距离（我的设计决定，not in M3）。 */
val CoverBadgeInset = 6.dp

/** 角标文字距角标边的左右距离（我的设计决定，not in M3）。 */
val CoverBadgeTextPadding = 6.dp
