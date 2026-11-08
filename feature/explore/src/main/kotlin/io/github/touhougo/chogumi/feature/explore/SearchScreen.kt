package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.ui.collection.PosterMarkHost
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 搜索页外壳：只取 Koin VM、收集状态，然后把 state/actions 交给 [SearchContent]。
 * 不承载任何业务分流——条件判定在 reducer/model，展示在 SearchContent 及其子组件。
 */
@Composable
fun SearchScreen(
    criteria: SearchCriteria?,
    title: String?,
    openFilters: Boolean,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onSubjectClick: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SearchViewModel = koinViewModel { parametersOf(criteria, title, openFilters) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    FailureNoticeEffect(viewModel.failures, snackbarHostState)
    // 结果海报能长按标记
    PosterMarkHost(state.posterMarks, viewModel.posterMarks, isLoggedIn, onLogin, snackbarHostState, modifier.fillMaxSize()) {
        SearchContent(
            state = state,
            actions = viewModel,
            onSubjectClick = onSubjectClick,
            onBack = onBack,
            currentYear = today.year,
        )
        // 收藏面板开着时提示条画在面板上
        if (!state.posterMarks.sheet.isShown) {
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}
