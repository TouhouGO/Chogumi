package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.SubjectType

/**
 * 收藏状态角标，全 App 唯一画法：海报右上角「我」的状态、别人主页海报左上角资料主人的状态、吐槽楼层里发言人的状态。
 *
 * [tinted] 决定用不用状态色：「我」的状态传 true，用这种状态的浅底色（[colors] 的容器色与其上的前景，和长按环、待提交胶囊同色）；
 * 别人的状态传 false，用中性色（[neutralMarkColors] 的容器色与其上的前景）。状态色在全 App 只表示「我」的收藏，
 * 别人主页的海报上两种状态同时出现，靠这一点分清是谁的。
 *
 * [labeled] 决定画字还是画图标：
 * - 压在封面上（`false`）只画状态图标（[iconRes] 的实心款，和收藏面板、长按环、待提交胶囊同一套），读屏念状态名。
 *   圆形容器 24dp、图标 16dp，是设计决定（not in M3：M3 没有只放图标的状态角标，比例取 icon button 的图标 / 容器）。
 * - 跟在文字行里（`true`，吐槽楼层的发言人名后）画状态名（[TextPill]）。文案随条目类型分动词（书籍「在读」、音乐「在听」……），映射统一在 [labelResFor]。
 */
@Composable
fun CollectionTypeBadge(
    type: CollectionType,
    subjectType: SubjectType,
    tinted: Boolean,
    labeled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = if (tinted) type.colors() else neutralMarkColors(MaterialTheme.colorScheme)
    val label = stringResource(type.labelResFor(subjectType))
    if (labeled) {
        TextPill(label, colors.container, colors.onContainer, modifier)
    } else {
        Surface(color = colors.container, contentColor = colors.onContainer, shape = CircleShape, modifier = modifier) {
            Box(Modifier.size(CoverBadgeSize), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(type.iconRes(filled = true)),
                    contentDescription = label,
                    modifier = Modifier.size(CoverBadgeIconSize),
                )
            }
        }
    }
}

private val CoverBadgeSize = 24.dp
private val CoverBadgeIconSize = 16.dp
