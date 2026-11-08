package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.LocalTopReset
import io.github.touhougo.chogumi.core.ui.theme.LocalAppMotion
import kotlinx.coroutines.launch

/** 「回到顶部」：列表滚离第一项后出现。M3 FabBaseline 56、圆角 16；原型 48 不在 FAB 尺寸阶梯上，取 M3 的 56。 */
@Composable
fun ScrollToTopFab(listState: LazyListState) {
    val scope = rememberCoroutineScope()
    val motion = LocalAppMotion.current
    val reset = LocalTopReset.current
    val scrolledAway by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    if (scrolledAway) {
        FloatingActionButton(
            onClick = {
                scope.launch {
                    motion.scrollToItem(listState, 0)
                    reset()
                }
            },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(painterResource(R.drawable.arrow_upward_24), contentDescription = stringResource(R.string.topic_scroll_to_top))
        }
    }
}
