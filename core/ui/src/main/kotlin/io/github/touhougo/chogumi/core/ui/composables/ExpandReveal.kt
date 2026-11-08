package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 随条件出现、收起的一块，推着下面的内容走（全 App 一种写法）：章节方格的看完卡片、收藏面板的提示行与进度区用它。
 * 规格见 [revealEnter]。
 */
@Composable
fun ExpandReveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, modifier = modifier, enter = revealEnter(), exit = revealExit()) { content() }
}

/**
 * 出现：高度从 0 展开、同时淡入，把下方内容推开，不一帧内蹦出来（M3 `transitions.md`）。高度是几何变化，走主题的 expressive
 * default spatial（`motion.md`：一个产品的动效基本只用一套方案；default 给占据部分屏幕的区块，fast 的回弹对整块内容过大）；
 * 不透明度走 default effects（不过冲）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun revealEnter(): EnterTransition =
    expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec())

/** 收起：与 [revealEnter] 对称。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun revealExit(): ExitTransition =
    shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec())
