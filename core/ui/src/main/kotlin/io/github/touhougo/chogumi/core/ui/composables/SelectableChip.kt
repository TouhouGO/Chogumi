package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import io.github.touhougo.chogumi.core.res.R

/** 选中时出现的勾选图标的 testTag（仪器测试据此断言「选中带勾、未选中不带」）。 */
const val SelectableChipCheckTag = "selectable_chip_check"

/**
 * 可选中的 filter chip。选中时左端出现勾选图标（input.md:481「filter chip 按下后标签起始边追加勾」），
 * 文字用 emphasized（typography.md:53-67「selected states」）。
 *
 * [unselectedLeadingIcon] 是未选中状态下可选的前置图标资源（如条目类型图标）；选中时依然统一展示勾选图标。
 * [trailingIcon] 是排序档的方向箭头等拖尾图标（input.md:403「aligned to the end side」）。
 * [enabled] 为 false 时走 M3 FilterChip 默认的禁用态（如时间范围里跨年时的月份）。
 *
 * **全仓可选中 chip 的唯一实现**：选中的勾、字重、动效只在这里；功能页不得直接用裸 `FilterChip`
 * （例外只有主题色那排：前置位是色块而不是图标，见 `ThemeColorSettings`）。
 *
 * 选中过渡的动效取自 `MaterialTheme.motionScheme`（tokens.md「Motion springs」）：
 * chip 宽度的变化是尺寸变化，走 **spatial**；勾选图标的淡入淡出是透明度变化，走 **effects**（阻尼 1.0，不过冲）；
 * 两者都是小组件，取 **fast**（SKILL.md「Spring speed」：Fast = small components）。
 * 尺寸动画只放在前置图标槽里，chip 本体不套 `animateContentSize`：spatial 弹簧会过冲，
 * 外层 `animateContentSize` 会把过冲的宽度当成自己的尺寸并按它裁剪，相邻 chip 回弹时右边框被裁掉、
 * 邻居被挤到它上面（首页「继续」里取消选中「全部」时盖住左边 chip 的根因）。
 */
@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    unselectedLeadingIcon: Int? = null,
    trailingIcon: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val iconFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val widthSpring = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    // 前置图标的唯一决策：选中是勾，否则是调用方给的类型图标，都没有则为 null。
    val leadingIconRes = if (selected) R.drawable.check_24 else unselectedLeadingIcon
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = {
            Text(
                label,
                style = if (selected) MaterialTheme.typography.labelLargeEmphasized
                else MaterialTheme.typography.labelLarge,
            )
        },
        leadingIcon = {
            // 槽位常驻：图标在槽内淡入淡出（effects），槽的尺寸变化走同一根 spatial 弹簧，不会在选中那一帧跳变。
            AnimatedContent(
                targetState = leadingIconRes,
                transitionSpec = {
                    (fadeIn(iconFade) togetherWith fadeOut(iconFade))
                        .using(SizeTransform(clip = false) { _, _ -> widthSpring })
                },
                label = "SelectableChipLeadingIcon",
            ) { iconRes ->
                if (iconRes == null) {
                    Spacer(Modifier)
                } else {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier
                            .size(FilterChipDefaults.IconSize)
                            .then(if (iconRes == R.drawable.check_24) Modifier.testTag(SelectableChipCheckTag) else Modifier),
                    )
                }
            }
        },
        trailingIcon = trailingIcon?.let {
            {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        },
        modifier = modifier,
    )
}
