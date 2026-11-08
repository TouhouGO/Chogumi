package io.github.touhougo.chogumi.core.ui.common.navigation

import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * 页面还在进场（转场没走完）时接住返回手势，松手后按普通返回退出，不走预测性返回。
 *
 * 预测性返回的拖动预览从「两页都已到位」算起：打断一张还在滑进来的页时，两页会当帧跳到预览的起点，
 * 松手后离开的页又整张消失。普通返回从两页此刻的位置接着往回滑，进场与退场连成一段。
 * 进场走完之后这里不再接，返回照常交给 `NavDisplay` 的预测性返回。
 *
 * 只在这一页是「刚压进来、还没完整出现过」的最上面一页（[isTopPage]）时才接：
 * - 「刚压进来」是进场状态从 [EnterExitState.PreEnter] 走向 [EnterExitState.Visible]。取消过一次预测性返回之后，留在屏幕上的那一页
 *   是从 [EnterExitState.PostExit] 回到 [EnterExitState.Visible]：转场库不会把这个起点改回 Visible，它会一直停在那里。
 *   按「起点不是 Visible」判进场，这一页之后的每一次返回手势都会被接走，再也没有预览；
 * - 「最上面」排除预测性返回预览里露出来的下一页：它的进场状态也是 PreEnter → Visible，但它不是被压进来的，
 *   取消后回弹的那一小段里它还在，这时接走手势同样会让下一次返回没有预览。
 * tab 根部（[isTopPage] 为假）不接，交给 [AppNavigator] 的处理。
 *
 * 读 `LocalNavAnimatedContentScope` 只为知道这一页是否还在进场，不碰面板的显隐（门禁 check_sheets.py）。
 */
@Composable
internal fun <T : Any> rememberEnteringBackNavEntryDecorator(isTopPage: (contentKey: Any) -> Boolean, goBack: () -> Unit): NavEntryDecorator<T> = remember {
    NavEntryDecorator { entry ->
        val transition = LocalNavAnimatedContentScope.current.transition
        val entering = transition.targetState == EnterExitState.Visible && transition.currentState == EnterExitState.PreEnter
        val state = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = state,
            // 手势已经由这里接住的，拖动途中进场走完也接到松手为止
            isBackEnabled = (entering && isTopPage(entry.contentKey)) || state.transitionState is NavigationEventTransitionState.InProgress,
            onBackCompleted = goBack,
        )
        entry.Content()
    }
}
