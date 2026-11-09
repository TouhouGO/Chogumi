package io.github.touhougo.chogumi.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.SettingsSection
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import org.koin.compose.viewmodel.koinViewModel

/** 「网络」分类页：直连开关。打开前先确认（实验性功能）；关掉不问。 */
@Composable
internal fun NetworkSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: NetworkSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    NetworkSettingsContent(uiState, viewModel, viewModel.failures, onBack, modifier)
}

/** 「网络」分类页的无状态内容层。 */
@Composable
internal fun NetworkSettingsContent(
    state: NetworkSettingsUiState,
    actions: NetworkSettingsActions,
    failures: FailureNotices,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    SettingsListPage(
        title = stringResource(SettingsSection.NETWORK.title),
        failures = failures,
        onBack = onBack,
        modifier = modifier,
    ) {
        settingsGroups(
            listOf(
                SettingsGroup(
                    key = "route",
                    title = null,
                    rows = listOf<SettingsRow>(
                        { index, count ->
                            SwitchRow(
                                index = index,
                                count = count,
                                title = stringResource(R.string.network_direct),
                                checked = state.direct,
                                onCheckedChange = { on -> if (on) confirming = true else actions.onDirectChange(false) },
                                details = { Text(stringResource(R.string.network_direct_details)) },
                            )
                        },
                    ),
                ),
            ),
        )
    }
    if (confirming) {
        ConfirmDialog(
            target = Unit,
            confirmLabel = stringResource(R.string.network_direct_confirm),
            onConfirm = { actions.onDirectChange(true) },
            onDismiss = { confirming = false },
            title = stringResource(R.string.network_direct_confirm_title),
            text = stringResource(R.string.network_direct_confirm_text),
        )
    }
}
