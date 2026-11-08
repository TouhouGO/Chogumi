package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 内容区不确定时长加载：首次加载已有骨架屏时优先骨架屏，没有才显示本组件。
 * 加载更多时放在新内容将出现的页脚；错误态显示错误与重试，空态显示空内容提示，均不显示加载指示器。
 * 按钮内加载保持在按钮内，用 [LoadingIndicator] 的紧凑尺寸和按钮内容色。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContentLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(modifier = modifier, color = color)
}
