package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color

/**
 * 异步滚动面板（会进子页的满高面板一律用它，门禁见 tools/guards/check_sheets.py）：第一帧即占满可用高度，首开半展开，
 * 数据变化不移动锚点。跳子页时不关面板，返回原样还在、高度不变；真正关闭后重开为新周期。
 * 内容自己负责滚动，不能在外壳再加一层滚动。满高布局是产品设计决定，非 M3 高度 token。
 *
 * 画在所在页的面板层里（[PageModalSheet]），不是独立窗口，所以不需要围绕「窗口压在所有页面之上、
 * 返回要重建」做任何时序处理。[contentKey] 变化（如换了一集）视为新面板。只能在导航项里用。
 * [hideRequested] 同 [PageModalSheet]：置真让面板先滑下去，滑完再回调 [onDismiss]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollableModalSheet(
    onDismiss: () -> Unit,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentKey: Any? = null,
    hideRequested: Boolean = false,
    /** 面板开着时提示条画在面板上（[PageModalSheet] 的 snackbar）。 */
    snackbar: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    key(contentKey) {
        PageModalSheet(
            onDismiss = onDismiss,
            fullHeight = true,
            containerColor = containerColor,
            hideRequested = hideRequested,
            snackbar = snackbar,
            content = content,
        )
    }
}
