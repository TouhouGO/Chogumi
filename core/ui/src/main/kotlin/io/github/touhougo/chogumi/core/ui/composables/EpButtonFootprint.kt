package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/** M3 大号徽标的高（`component-tokens.md` Badge.LargeSize）；字号放大时徽标随标签行高变高。 */
private val LargeBadgeHeight = 16.dp

/**
 * 徽标带：EP 按钮触控区上下各留的这一条，让待提交的 `+N` 落在本组件**自己的边界里**。
 *
 * 徽标骑在容器上沿（中线对齐容器顶边，见 [EpButton]）：伸出容器 = 徽标高的一半，触控区本来就在容器上下
 * 各留了一截（48dp 触控区 − 40dp 容器），不够的部分由这条带补足。上下对称，容器在占地里始终居中，
 * 所在行的垂直居中不受影响。
 */
@Composable
internal fun epBadgeBand(): Dp {
    val badgeHeight = maxOf(LargeBadgeHeight, with(LocalDensity.current) { epBadgeTextStyle().lineHeight.toDp() })
    return (badgeHeight / 2 - epTouchInset()).coerceAtLeast(0.dp)
}

/** 官方触控区在容器上下各多出的一截（`minimumInteractiveComponentSize` 把 40dp 容器补到 48dp）。 */
@Composable
internal fun epTouchInset(): Dp =
    ((LocalMinimumInteractiveComponentSize.current - ButtonDefaults.MinHeight) / 2).coerceAtLeast(0.dp)

/** `+N` 徽标的字：M3 大号徽标的 label small，徽标属于强调样式。 */
@Composable
internal fun epBadgeTextStyle() = MaterialTheme.typography.labelSmallEmphasized

/** 官方触控区的高：40dp 容器由 `minimumInteractiveComponentSize` 补到 48dp。 */
@Composable
internal fun epTouchHeight(): Dp = maxOf(LocalMinimumInteractiveComponentSize.current, ButtonDefaults.MinHeight)

/**
 * EP 按钮的**整块占地**——唯一出处：
 * - 宽：写目标时下限 [ChogumiDimens.EpButtonMinWidth]；紧凑形态（列表行）**所有状态一样宽**，都是 [epCompactWidth]；
 * - 高：官方触控区（[epTouchHeight]）上下再各加一条徽标带（[epBadgeBand]）。
 *
 * 真按钮（[EpButton] 的布局按同一口径算）、加载占位（[EpButtonPlaceholder]）、列表行里的 EP 槽位（见 [CollectionListItem]）
 * 都从这里取尺寸：占位与真按钮一样大，按钮一出现、状态一变（可点 → 等待 → 完结），整行都不会被撑开或挤窄；
 * 待提交的徽标画在这块占地里，外层的淡入淡出（离屏合成按边界裁切）、轮播的绘制层都切不到它。
 *
 * 高是**最小高度**而不是外加内边距：槽位里装的真按钮已经含徽标带，槽位只保证空着时也占这么高。
 */
@Composable
internal fun Modifier.epButtonFootprint(showTarget: Boolean): Modifier =
    then(if (showTarget) Modifier.widthIn(min = ChogumiDimens.EpButtonMinWidth) else Modifier.width(epCompactWidth))
        .heightIn(min = epTouchHeight() + epBadgeBand() * 2)

/**
 * 紧凑形态（列表行）的宽：M3 小号图标按钮的宽款（`component-tokens.md` SmallIconButton：40 高、图标 24、左右各 14）。
 * 「+」、完结的对勾、等待的倒计时环都是这个宽，列表右侧一列因此对齐成一条竖线。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val epCompactWidth: Dp
    get() = IconButtonDefaults.smallContainerSize(IconButtonDefaults.IconButtonWidthOption.Wide).width
