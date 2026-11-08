package io.github.touhougo.chogumi.feature.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.SettingsSection
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.text
import org.koin.compose.viewmodel.koinViewModel

/**
 * 「内容过滤」分类页：NSFW 开关，屏蔽的小组，屏蔽的用户（绝交名单，登录了才有这一行）。
 * [onMutedGroups] / [onBlockedUsers] 去各自的名单页。
 */
@Composable
internal fun ContentSettingsScreen(
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onMutedGroups: () -> Unit,
    onBlockedUsers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ContentSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ContentSettingsContent(
        state = uiState,
        actions = viewModel,
        failures = viewModel.failures,
        isLoggedIn = isLoggedIn,
        onBack = onBack,
        onMutedGroups = onMutedGroups,
        onBlockedUsers = onBlockedUsers,
        modifier = modifier,
    )
}

/**
 * 「内容过滤」分类页的无状态内容层：状态与事件都从外部传入，**失败提示**这条链因此可被仪器测试覆盖
 * （`koinViewModel()` 把状态源焊死在组合里，提示条是否真的弹出就无从断言）。
 */
@Composable
fun ContentSettingsContent(
    state: ContentSettingsUiState,
    actions: ContentSettingsActions,
    failures: FailureNotices,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onMutedGroups: () -> Unit,
    onBlockedUsers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mutedGroups = stringResource(R.string.muted_groups)
    val blockedUsers = stringResource(R.string.blocked_users)
    SettingsListPage(
        title = stringResource(SettingsSection.CONTENT_FILTER.title),
        failures = failures,
        onBack = onBack,
        modifier = modifier,
    ) {
        settingsGroups(
            listOf(
                SettingsGroup(
                    key = "content",
                    title = null,
                    rows = listOf<SettingsRow>(
                        { index, count -> NsfwRow(index, count, state.nsfw, actions::onNsfwChange, actions::retryNsfw) },
                        { index, count -> LinkRow(index, count, mutedGroups, onClick = onMutedGroups) },
                    ) + if (isLoggedIn) {
                        listOf<SettingsRow>({ index, count -> LinkRow(index, count, blockedUsers, onClick = onBlockedUsers) })
                    } else {
                        emptyList()
                    },
                ),
            ),
        )
    }
}

/**
 * NSFW 开关在仪器测试里的定位标记。
 *
 * 页面另有多个开关，`isToggleable()` 会同时命中，
 * 故用 tag 精确定位，不靠文本或遍历顺序。
 */
const val NsfwSwitchTag = "settings_nsfw_switch"

/**
 * NSFW 开关行 —— **四态**渲染。
 *
 * 开关值来自 bgm.tv 账号设置（本地只是缓存），故四种状态各有拒绝理由：
 * 未登录 / 拿不准（加载中或失败）/ 账号无权改 / 可改。前三者禁用并写明原因，
 * 只有可改态才允许拨动 —— 禁用态绝不能静默显示成"关"，那会让人以为已关闭。
 * 原因是状态，不是说明，所以只在不可改时出现；失败时附重试。
 *
 * 依据 M3 Expressive `references/components/input.md` §Switch：Disabled 为合法状态；
 * §9「Don't use a switch for a call to action」—— 不可用时配原因，不拿开关当按钮。
 */
@Composable
private fun NsfwRow(
    index: Int,
    count: Int,
    state: NsfwSwitchState,
    onCheckedChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val reason = when (state) {
        NsfwSwitchState.LoggedOut -> stringResource(R.string.settings_nsfw_logged_out)
        is NsfwSwitchState.Unavailable -> when {
            state.failureReason != null ->
                stringResource(R.string.settings_nsfw_load_failed, state.failureReason.text())
            else -> stringResource(R.string.settings_nsfw_loading)
        }
        is NsfwSwitchState.Locked -> stringResource(R.string.settings_nsfw_locked)
        is NsfwSwitchState.Editable -> null
    }
    SwitchRow(
        index = index,
        count = count,
        title = stringResource(R.string.settings_nsfw),
        checked = state.checked,
        onCheckedChange = onCheckedChange,
        switchTag = NsfwSwitchTag,
        enabled = state is NsfwSwitchState.Editable && !state.pending,
        details = reason?.let {
            {
                if (state is NsfwSwitchState.Unavailable && state.failureReason != null) {
                    RetryError(it, onRetry = onRetry, compact = true)
                } else {
                    Text(it)
                }
            }
        },
    )
}
