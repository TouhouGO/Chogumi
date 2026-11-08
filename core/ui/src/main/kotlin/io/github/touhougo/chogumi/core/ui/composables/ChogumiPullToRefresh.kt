package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** 全应用下拉刷新唯一入口；[isRefreshing] 只表示用户主动下拉触发的请求，不包括首载。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChogumiPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            ExpressiveRefreshIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(indicatorPadding),
            )
        },
        content = content,
    )
}

/**
 * 官方带容器的形变加载指示器，拉动与松手后使用同一个实现。
 *
 * 收起时官方指示器把自己往上平移一个自身高度，只靠绘制裁剪藏起来，语义节点（一个进度条）却留在那里，
 * 叠在下拉区上方的内容上：分类 tab、页头。无障碍树会剔除被后画的节点完全盖住的节点，
 * 恰好落在指示器下面的文字（小屏上进度页的「动画」）就从读屏和 UI 测试里消失了。
 * 所以指示器只在拉出来或正在刷新时才有语义。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier,
) {
    val retracted by remember(state) { derivedStateOf { state.distanceFraction == 0f } }
    PullToRefreshDefaults.LoadingIndicator(
        state = state,
        isRefreshing = isRefreshing,
        modifier = if (retracted && !isRefreshing) modifier.clearAndSetSemantics {} else modifier,
    )
}
