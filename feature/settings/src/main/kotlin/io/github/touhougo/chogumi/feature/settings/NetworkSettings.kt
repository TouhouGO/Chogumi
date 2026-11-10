package io.github.touhougo.chogumi.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.netroute.CheckSite
import io.github.touhougo.chogumi.core.netroute.LOCAL_NETWORK_PERMISSION
import io.github.touhougo.chogumi.core.netroute.ProxyType
import io.github.touhougo.chogumi.core.netroute.RouteMode
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.SettingsSection
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.ui.composables.FormTextField
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SkeletonTextDigits
import io.github.touhougo.chogumi.core.ui.theme.raisedContainer
import org.koin.compose.viewmodel.koinViewModel

/**
 * 「网络」分类页，三块分开：直连（设备支持时；打开前先确认实验性功能，关掉不问）、代理（打开后才有类型、地址、端口与「连接」）、
 * 检查连接（按现在的线路测几个 bgm.tv 站点）。直连与代理互斥，打开一个另一个就关掉（开关在同一屏上翻过去，不另外提示）；
 * 两个都关是系统网络。
 */
@Composable
internal fun NetworkSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: NetworkSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    // 这一页里拒绝过局域网权限：系统不再弹框时（拒绝过两次），再点「连接」只剩系统设置里本应用的那一页
    var deniedOnce by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.onProxyConnect()
        } else {
            deniedOnce = true
            viewModel.onLocalNetworkDenied()
        }
    }
    val connect = {
        when {
            !viewModel.localNetworkMissing() -> viewModel.onProxyConnect()
            deniedOnce && activity?.shouldShowRequestPermissionRationale(LOCAL_NETWORK_PERMISSION) == false -> openAppDetails(context)
            else -> permission.launch(LOCAL_NETWORK_PERMISSION)
        }
    }
    NetworkSettingsContent(uiState, viewModel, viewModel.failures, onBack, onConnect = connect, modifier = modifier)
}

/** 系统设置里本应用的那一页（权限在那里打开）。 */
private fun openAppDetails(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** 「网络」分类页的无状态内容层。 */
@Composable
internal fun NetworkSettingsContent(
    state: NetworkSettingsUiState,
    actions: NetworkSettingsActions,
    failures: FailureNotices,
    onBack: () -> Unit,
    /** 点「连接」：页面先看要不要局域网权限，再交给 [NetworkSettingsActions.onProxyConnect]。 */
    onConnect: () -> Unit,
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
            listOfNotNull(
                if (state.directSupported) {
                    SettingsGroup(
                        key = "direct",
                        title = null,
                        rows = listOf<SettingsRow>(
                            { index, count ->
                                SwitchRow(
                                    index = index,
                                    count = count,
                                    title = stringResource(R.string.network_direct),
                                    checked = state.mode == RouteMode.DIRECT,
                                    onCheckedChange = { on -> if (on) confirming = true else actions.onDirectChange(false) },
                                    switchTag = DirectSwitchTag,
                                    details = { Text(stringResource(R.string.network_direct_details)) },
                                )
                            },
                        ),
                    )
                } else {
                    null
                },
                SettingsGroup(key = "proxy", title = null, rows = proxyRows(state, actions, onConnect)),
                SettingsGroup(
                    key = "check",
                    title = null,
                    rows = listOf<SettingsRow>(
                        { index, count -> CheckHeaderRow(index, count, state, actions) },
                        { index, count -> CheckTilesRow(index, count, state.check) },
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

/** 代理那一块：开关；打开时下面是类型、地址与端口、状态行与「连接」。 */
private fun proxyRows(state: NetworkSettingsUiState, actions: NetworkSettingsActions, onConnect: () -> Unit): List<SettingsRow> {
    val switch: SettingsRow = { index, count ->
        SwitchRow(
            index = index,
            count = count,
            title = stringResource(R.string.network_proxy),
            checked = state.mode == RouteMode.PROXY,
            onCheckedChange = actions::onProxyChange,
            switchTag = ProxySwitchTag,
            details = { Text(stringResource(R.string.network_proxy_details)) },
        )
    }
    if (state.mode != RouteMode.PROXY) return listOf(switch)
    return listOf(
        switch,
        { index, count ->
            ChoiceRow(
                index = index,
                count = count,
                title = stringResource(R.string.network_proxy_type),
                options = ProxyType.entries,
                selected = state.draft.type,
                label = { stringResource(if (it == ProxyType.HTTP) R.string.network_proxy_type_http else R.string.network_proxy_type_socks5) },
                onSelect = actions::onProxyType,
            )
        },
        { index, count -> ProxyFieldsRow(index, count, state, actions, onConnect) },
        { index, count -> ProxyConnectRow(index, count, state, onConnect) },
    )
}

/** 地址与端口并排：地址只收主机，端口只收数字（过滤在 ViewModel，规则在 core:netroute）。 */
@Composable
private fun ProxyFieldsRow(index: Int, count: Int, state: NetworkSettingsUiState, actions: NetworkSettingsActions, onConnect: () -> Unit) {
    val failed = state.connecting == ProxyConnecting.FAILED
    SegmentedItem(index = index, count = count, onClick = null, modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FormTextField(
                value = state.draft.host,
                onValueChange = actions::onProxyHost,
                label = stringResource(R.string.network_proxy_host),
                placeholder = "127.0.0.1",
                isError = failed,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            FormTextField(
                value = state.draft.port,
                onValueChange = actions::onProxyPort,
                label = stringResource(R.string.network_proxy_port),
                placeholder = "1080",
                isError = failed,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (state.canConnect) onConnect() }),
                modifier = Modifier.width(PortFieldWidth),
            )
        }
    }
}

/** 状态行与「连接」：在连、点不了（没填好、或就是正在用的那一组）时按钮不可点。 */
@Composable
private fun ProxyConnectRow(index: Int, count: Int, state: NetworkSettingsUiState, onConnect: () -> Unit) {
    val status = state.proxyStatus
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        trailingContent = {
            Button(onClick = onConnect, enabled = state.canConnect) { Text(stringResource(R.string.network_proxy_connect)) }
        },
    ) {
        Text(
            stringResource(
                when (status) {
                    ProxyStatus.CONNECTING -> R.string.network_proxy_connecting
                    ProxyStatus.UNREACHABLE -> R.string.network_proxy_unreachable
                    ProxyStatus.NO_LOCAL_NETWORK -> R.string.network_proxy_no_local_network
                    ProxyStatus.CONNECTED -> R.string.network_proxy_connected
                    ProxyStatus.CHANGED -> R.string.network_proxy_changed
                    ProxyStatus.FILL_IN -> R.string.network_proxy_fill
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = when (status) {
                ProxyStatus.UNREACHABLE, ProxyStatus.NO_LOCAL_NETWORK -> MaterialTheme.colorScheme.error
                ProxyStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 「检查连接」的标题行：说明用的是哪条线路，尾部是开始 / 再测一次（测着的时候不可点）。 */
@Composable
private fun CheckHeaderRow(index: Int, count: Int, state: NetworkSettingsUiState, actions: NetworkSettingsActions) {
    val route = stringResource(
        when (state.mode) {
            RouteMode.SYSTEM -> R.string.network_route_system
            RouteMode.DIRECT -> R.string.network_direct
            RouteMode.PROXY -> R.string.network_proxy
        },
    )
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        trailingContent = {
            FilledTonalButton(onClick = actions::onCheck, enabled = !state.checking) {
                Text(stringResource(if (state.check == null) R.string.network_check_start else R.string.network_check_again))
            }
        },
        details = { Text(stringResource(R.string.network_check_details, route)) },
    ) { Text(stringResource(R.string.network_check)) }
}

/** 四个站点两两一排（格数属于内容：应用连的就是这四个主机），结果在原位填上，测着时是与结果同高的占位。 */
@Composable
private fun CheckTilesRow(index: Int, count: Int, check: Map<CheckSite, CheckResult>?) {
    SegmentedItem(index = index, count = count, onClick = null, modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(TileGap), modifier = Modifier.fillMaxWidth()) {
            listOf(CheckSite.WEB to CheckSite.API, CheckSite.NEXT to CheckSite.IMAGES).forEach { (left, right) ->
                Row(horizontalArrangement = Arrangement.spacedBy(TileGap), modifier = Modifier.fillMaxWidth()) {
                    CheckTile(left, check?.get(left), Modifier.weight(1f))
                    CheckTile(right, check?.get(right), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CheckTile(site: CheckSite, result: CheckResult?, modifier: Modifier = Modifier) {
    val valueStyle = MaterialTheme.typography.titleLarge
    Column(
        modifier
            .background(MaterialTheme.colorScheme.raisedContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(stringResource(site.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (result) {
            null -> Text(stringResource(R.string.network_check_untested), style = valueStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CheckResult.Pending -> SkeletonText(style = valueStyle, sample = SkeletonTextDigits, widthFraction = 0.5f)
            is CheckResult.Millis -> Text(stringResource(R.string.network_check_millis, result.value), style = valueStyle)
            CheckResult.Unreachable -> Text(stringResource(R.string.network_check_unreachable), style = valueStyle, color = MaterialTheme.colorScheme.error)
        }
        Text(
            site.host,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CheckSite.label: Int
    get() = when (this) {
        CheckSite.WEB -> R.string.network_check_web
        CheckSite.API -> R.string.network_check_api
        CheckSite.NEXT -> R.string.network_check_next
        CheckSite.IMAGES -> R.string.network_check_images
    }

/** 端口框宽：五位数字加框内标签，380dp 字号 1.1 也放得下（设计决定，not in M3）。 */
private val PortFieldWidth = 112.dp

/** 四格之间的间距，同筛选格（设计决定，not in M3）。 */
private val TileGap = 8.dp

/** 仪器测试定位两个开关。 */
internal const val DirectSwitchTag = "network_direct_switch"
internal const val ProxySwitchTag = "network_proxy_switch"
