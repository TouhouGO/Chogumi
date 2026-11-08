package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.touhougo.chogumi.core.model.formatAbbreviatedCount
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.Gated

/**
 * 顶栏右上角的「开关 + 人数」按钮（全 App 唯一画法：目录页、角色 / 人物页的收藏，小组页的加入共用）。
 * 图标 + 缩写人数（[formatAbbreviatedCount]）合成一个按钮，不另占一行「N 人收藏」「N 位成员」。
 * 有底色才看得出能点：未开 tonal、已开（已收藏、已加入）filled；尺寸是 M3 small button（高 40，图标 18，图字间距与内边距取 [ButtonDefaults]）。
 *
 * [visible]：人数真正到了才为 true。骨架阶段不画（画占位的「0」的话，数据到了会硬跳成真实人数）；
 * 由 false 变 true 时按 [SkeletonCrossfade] 同一条 `fastEffectsSpec` 淡入，与正文从骨架淡入同步。
 * 首次组合时就已为 true（如从子页返回）直接显示，不再淡入一遍。
 *
 * [onToggle] 已过登录门：未登录照样可点，点了去登录（不给点了没反应的禁用按钮）。
 *
 * @param collectLabel 未开时读屏念的动作（「收藏」「收藏目录」「加入小组」）。
 * @param collectedLabel 已开时读屏念的状态，默认「已收藏」。
 * @param countLabel 读屏念的完整人数（不缩写），默认「N 人收藏」。
 */
@Composable
fun CollectCountButton(
    visible: Boolean,
    collected: Boolean,
    count: Int,
    @DrawableRes icon: Int,
    @DrawableRes collectedIcon: Int,
    collectLabel: String,
    onToggle: Gated<() -> Unit>,
    loading: Boolean = false,
    collectedLabel: String = stringResource(R.string.collected),
    countLabel: String = stringResource(R.string.meta_collections, count),
) {
    val appear = remember { MutableTransitionState(visible) }.apply { targetState = visible }
    val stateLabel = if (collected) collectedLabel else collectLabel
    val onClick = onToggle.action
    val description = stringResource(R.string.collect_action_desc, stateLabel, countLabel)
    val modifier = Modifier.clearAndSetSemantics { contentDescription = description }
    val padding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MinHeight, hasStartIcon = true)
    val countText = formatAbbreviatedCount(count, LocalConfiguration.current.locales[0])
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            ContentLoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
        } else {
            Icon(
                painter = painterResource(if (collected) collectedIcon else icon),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
        }
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(countText)
    }
    val clickable = !loading
    AnimatedVisibility(
        visibleState = appear,
        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit = ExitTransition.None,
    ) {
        if (collected) {
            Button(onClick = onClick, enabled = clickable, contentPadding = padding, modifier = modifier, content = content)
        } else {
            FilledTonalButton(onClick = onClick, enabled = clickable, contentPadding = padding, modifier = modifier, content = content)
        }
    }
}
