package io.github.touhougo.chogumi.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.UpdateCheckInterval
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.SettingsSection
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import org.koin.compose.viewmodel.koinViewModel

/** 「更新」分类页：当前版本与「立即检查」；接收预发布版（对两种检查都起作用）；自动检查更新的开关，开着时还有检查间隔。 */
@Composable
internal fun UpdateSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: UpdateSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) { versionNameOf(context) }
    UpdateSettingsContent(uiState, viewModel, viewModel.failures, version, onBack, modifier)
}

/** 「更新」分类页的无状态内容层：状态与事件都从外部传入。 */
@Composable
fun UpdateSettingsContent(
    state: UpdateSettingsUiState,
    actions: UpdateSettingsActions,
    failures: FailureNotices,
    /** 装着的这一版的版本名。 */
    installedVersion: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsListPage(
        title = stringResource(SettingsSection.UPDATES.title),
        failures = failures,
        onBack = onBack,
        modifier = modifier,
    ) {
        settingsGroups(
            listOf(
                SettingsGroup(
                    key = "version",
                    title = null,
                    rows = listOf<SettingsRow>(
                        { index, count -> ValueRow(index, count, stringResource(R.string.settings_update_current_version), installedVersion) },
                        { index, count -> CheckNowRow(index, count, state.manualCheck, actions::checkNow) },
                    ),
                ),
                SettingsGroup(
                    key = "channel",
                    title = null,
                    rows = listOf<SettingsRow>(
                        { index, count ->
                            SwitchRow(
                                index = index,
                                count = count,
                                title = stringResource(R.string.settings_update_prerelease),
                                checked = state.prerelease,
                                onCheckedChange = actions::onPrereleaseChange,
                            )
                        },
                    ),
                ),
                SettingsGroup(
                    key = "auto",
                    title = null,
                    rows = listOfNotNull<SettingsRow>(
                        { index, count ->
                            SwitchRow(
                                index = index,
                                count = count,
                                title = stringResource(R.string.settings_update_auto_check),
                                checked = state.autoCheck,
                                onCheckedChange = actions::onAutoCheckChange,
                                details = { Text(stringResource(R.string.settings_update_auto_check_details)) },
                            )
                        },
                        if (state.autoCheck) {
                            { index, count ->
                                ChoiceRow(
                                    index = index,
                                    count = count,
                                    title = stringResource(R.string.settings_update_interval),
                                    options = UpdateCheckInterval.entries,
                                    selected = state.interval,
                                    label = { stringResource(it.label) },
                                    onSelect = actions::onIntervalChange,
                                )
                            }
                        } else {
                            null
                        },
                    ),
                ),
            ),
        )
    }
}

/** 检查间隔在设置里的名字。 */
private val UpdateCheckInterval.label: Int
    get() = when (this) {
        UpdateCheckInterval.EVERY_LAUNCH -> R.string.settings_update_interval_every_launch
        UpdateCheckInterval.DAILY -> R.string.settings_update_interval_daily
        UpdateCheckInterval.WEEKLY -> R.string.settings_update_interval_weekly
    }

/** 一行只读的设置：标题，尾部是它的值。 */
@Composable
private fun ValueRow(index: Int, count: Int, title: String, value: String) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        trailingContent = { Text(value) },
    ) { Text(title) }
}

/** 「立即检查」：整行可点，查的时候不可点；尾部说进行到哪（还没点时空着）。 */
@Composable
private fun CheckNowRow(index: Int, count: Int, check: ManualUpdateCheck, onClick: () -> Unit) {
    val status = when (check) {
        ManualUpdateCheck.IDLE -> null
        ManualUpdateCheck.CHECKING -> R.string.settings_update_checking
        ManualUpdateCheck.UP_TO_DATE -> R.string.settings_update_up_to_date
    }
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        enabled = check != ManualUpdateCheck.CHECKING,
        modifier = Modifier.fillMaxWidth(),
        trailingContent = status?.let { { Text(stringResource(it)) } },
    ) { Text(stringResource(R.string.settings_update_check_now)) }
}
