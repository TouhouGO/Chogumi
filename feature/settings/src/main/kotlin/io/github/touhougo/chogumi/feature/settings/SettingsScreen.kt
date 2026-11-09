package io.github.touhougo.chogumi.feature.settings

import android.content.pm.ApplicationInfo
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.netroute.directRouteSupported
import io.github.touhougo.chogumi.core.ui.common.navigation.SettingsSection
import io.github.touhougo.chogumi.core.ui.composables.ConfirmDialog
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.FailureNoticeEffect
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.SectionHeader
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens
import org.koin.compose.viewmodel.koinViewModel

/**
 * 设置的一级菜单：自己的资料（登录了才有，点了由 [onProfile] 去个人资料页），各分类（[SettingsSection]，点了由 [onSection] 去那一页），图片缓存，关于（[onAbout]），
 * 调试区（可调试构建），退出登录（登录了才有）。
 */
@Composable
fun SettingsScreen(
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onSection: (SettingsSection) -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 看的是 App 进程的 FLAG_DEBUGGABLE：feature 模块自己的 BuildConfig 不是 app 的构建类型。
    val context = LocalContext.current
    val debuggable = remember(context) { (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    SettingsContent(
        state = uiState,
        actions = viewModel,
        failures = viewModel.failures,
        modifier = modifier,
        onBack = onBack,
        onProfile = onProfile,
        onSection = onSection,
        onAbout = onAbout,
        isLoggedIn = isLoggedIn,
        showDebugTools = debuggable,
        networkSection = directRouteSupported,
    )
}

/** 一级菜单的无状态内容层：状态与事件都从外部传入，退出登录的确认可以直接测。 */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    failures: FailureNotices,
    onBack: () -> Unit,
    onProfile: () -> Unit,
    onSection: (SettingsSection) -> Unit,
    onAbout: () -> Unit,
    isLoggedIn: Boolean,
    /** 调试区（按链接跳转、上次崩溃），只在可调试构建里显示。 */
    showDebugTools: Boolean,
    /** 「网络」分类：设备支持直连时才有（core:netroute `directRouteSupported`）。 */
    networkSection: Boolean,
    modifier: Modifier = Modifier,
) {
    // 退出登录先确认：点错一下就要重新走一遍登录（验证码、浏览器授权）。
    var confirmingLogout by rememberSaveable { mutableStateOf(false) }
    // 清图片缓存也先确认：点错一下，几百 MB 的图要重新下载。
    var confirmingClearCache by rememberSaveable { mutableStateOf(false) }
    SettingsListPage(
        title = stringResource(R.string.settings),
        failures = failures,
        onBack = onBack,
        modifier = modifier,
    ) {
        settingsGroups(
            listOfNotNull(
                if (isLoggedIn) {
                    SettingsGroup(
                        key = "profile",
                        title = null,
                        rows = listOf<SettingsRow>({ index, count -> ProfileEntryRow(index, count, state.me, onProfile) }),
                    )
                } else {
                    null
                },
                SettingsGroup(
                    key = "sections",
                    title = null,
                    rows = SettingsSection.entries.filter { it != SettingsSection.NETWORK || networkSection }.map<SettingsSection, SettingsRow> { section ->
                        { index, count ->
                            LinkRow(index, count, stringResource(section.title), section.icon) { onSection(section) }
                        }
                    },
                ),
                SettingsGroup(
                    key = "storage",
                    title = null,
                    rows = listOf<SettingsRow>(
                        { index, count -> ImageCacheRow(index, count, state.imageCacheBytes) { confirmingClearCache = true } },
                        { index, count -> LinkRow(index, count, stringResource(R.string.about), R.drawable.info_24, onAbout) },
                    ),
                ),
                if (showDebugTools) {
                    SettingsGroup(
                        key = "debug",
                        title = null,
                        rows = listOf<SettingsRow>(
                            { index, count -> DisplayRow(index, count) { DebugLinkEntry() } },
                            { index, count -> DisplayRow(index, count) { DebugCrashEntry() } },
                        ),
                    )
                } else {
                    null
                },
            ),
        )
        // 游客没有会话可退，不该看到"退出登录"。
        if (isLoggedIn) {
            item(key = "logout") {
                OutlinedButton(
                    onClick = { confirmingLogout = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    Text(stringResource(R.string.logout))
                }
            }
        }
    }

    if (confirmingLogout) {
        ConfirmDialog(
            target = Unit,
            confirmLabel = stringResource(R.string.logout_confirm_action),
            onConfirm = { actions.logout() },
            onDismiss = { confirmingLogout = false },
            title = stringResource(R.string.logout_confirm_title),
            text = stringResource(R.string.logout_confirm_body),
            destructive = true,
        )
    }
    if (confirmingClearCache) {
        ConfirmDialog(
            target = Unit,
            confirmLabel = stringResource(R.string.settings_image_cache_clear_action),
            onConfirm = { actions.clearImageCache() },
            onDismiss = { confirmingClearCache = false },
            title = stringResource(R.string.settings_image_cache_clear_title),
            text = stringResource(R.string.settings_image_cache_clear_body),
        )
    }
}

/**
 * 设置的一个分类页（二级页）。分类与它的内容只在这里对应一次：外观、首页与进度、评分与社区是本地偏好，共用
 * [PreferenceSettingsScreen]；内容过滤读写服务端的账号设置，是 [ContentSettingsScreen]；更新要查 GitHub 上的正式版，
 * 是 [UpdateSettingsScreen]。
 */
@Composable
fun SettingsPageScreen(
    section: SettingsSection,
    isLoggedIn: Boolean,
    onBack: () -> Unit,
    onMutedGroups: () -> Unit,
    onBlockedUsers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (section) {
        SettingsSection.APPEARANCE ->
            PreferenceSettingsScreen(section, onBack, { state, actions -> appearanceGroups(state, actions) }, modifier)
        SettingsSection.HOME_PROGRESS ->
            PreferenceSettingsScreen(section, onBack, { state, actions -> homeProgressGroups(state, actions) }, modifier)
        SettingsSection.RATINGS_COMMUNITY ->
            PreferenceSettingsScreen(section, onBack, { state, actions -> ratingsCommunityGroups(state, actions) }, modifier)
        SettingsSection.CONTENT_FILTER ->
            ContentSettingsScreen(isLoggedIn, onBack, onMutedGroups, onBlockedUsers, modifier)
        SettingsSection.UPDATES -> UpdateSettingsScreen(onBack, modifier)
        SettingsSection.NETWORK -> NetworkSettingsScreen(onBack, modifier)
    }
}

/**
 * 设置的每一页（一级菜单与各分类）共用的框架：顶栏、失败提示、分段列表的边距与间距。
 * 页面上开着会失败的面板时，把同一个 [snackbar] 交给面板、[snackbarShown] 置 false：提示条画在面板上（`PageModalSheet`）。
 */
@Composable
internal fun SettingsListPage(
    title: String,
    failures: FailureNotices,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    snackbarShown: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    FailureNoticeEffect(failures, snackbar)
    PageScaffold(
        title = title,
        onBack = onBack,
        snackbarHost = { if (snackbarShown) SnackbarHost(snackbar) },
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag(SettingsListTag),
            contentPadding = PaddingValues(
                start = ChogumiDimens.ScreenPadding,
                end = ChogumiDimens.ScreenPadding,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            content = content,
        )
    }
}

/** 设置页一行：[index] / [count] 是它在所属组里的位置，分段的圆角由它决定。 */
internal typealias SettingsRow = @Composable (index: Int, count: Int) -> Unit

/** 一组设置：组名（[title] 为 null 时没有组名）在上，各行连成一段分段列表。 */
internal class SettingsGroup(val key: String, val title: String?, val rows: List<SettingsRow>)

/**
 * 一页的几组设置。有组名的组以组名开头；没有组名的组与上一组隔一段间隔，页面的第一组前面什么也不放。
 * 每行一个 lazy 项，行在屏幕外时不参与组合。
 */
internal fun LazyListScope.settingsGroups(groups: List<SettingsGroup>) {
    groups.forEachIndexed { position, group ->
        when {
            group.title != null -> item(key = "${group.key}-title") { SectionHeader(group.title) }
            position > 0 -> item(key = "${group.key}-title") { Spacer(Modifier.height(16.dp)) }
        }
        items(group.rows.size, key = { "${group.key}-$it" }) { index -> group.rows[index](index, group.rows.size) }
    }
}

/** 设置列表在仪器测试里的定位标记：行是惰性的，测试先滚到目标行再操作。 */
const val SettingsListTag = "settings_list"

/**
 * 一行开关设置。依据 M3 Expressive `references/components/input.md` §Switch：
 * - 二值设置用 Switch，开关即时生效、无需保存，在行尾；标签文字在开关**外侧**；
 * - Disabled 是合法状态；
 * - 整行是一个可切换节点（读屏念「标题，开关，已开」，点标题也能切），Switch 自己只负责画。
 * [details] 画在标题下面：状态原因（如 NSFW 不可用），或功能说明（只有专注模式与直连两行，理由在 `ExperienceModeRow` 与「网络」页）。[switchTag] 供仪器测试精确定位（页面上有多个开关）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SwitchRow(
    index: Int,
    count: Int,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTag: String? = null,
    enabled: Boolean = true,
    details: @Composable (() -> Unit)? = null,
) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = { onCheckedChange(!checked) },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            }
            .then(if (switchTag != null) Modifier.testTag(switchTag) else Modifier),
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        details = details,
    ) { Text(title) }
}

/**
 * 一行互斥单选设置：依据 M3 Expressive `references/components/buttons.md` §Button groups，互斥选项首选
 * connected button group（segmented button 已不再推荐），走 core:ui 的 [ConnectedChoiceGroup]
 * （文字单选分段的唯一出处：形状、配色、按压弹性、小屏排法只此一处）。标题在上，分段按钮在下。
 */
@Composable
internal fun <T> ChoiceRow(
    index: Int,
    count: Int,
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        details = {
            ConnectedChoiceGroup(
                options = options,
                selected = selected,
                label = label,
                onSelect = onSelect,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).fillMaxWidth(),
            )
        },
    ) { Text(title) }
}

/** 一行去下一页的设置：[icon]（一级菜单的行才有）、标题，尾部箭头；整行可点。 */
@Composable
internal fun LinkRow(index: Int, count: Int, title: String, @DrawableRes icon: Int? = null, onClick: () -> Unit) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = icon?.let { { Icon(painterResource(it), contentDescription = null) } },
        trailingContent = { Icon(painterResource(R.drawable.chevron_right_24), contentDescription = null) },
    ) { Text(title) }
}

/**
 * 图片缓存一行：标题，尾部是量出来的占用（系统的短文件大小格式，跟随语言）；还没量到时尾部空着，不写零。
 * 整行可点，点了先确认再清。
 */
@Composable
private fun ImageCacheRow(index: Int, count: Int, bytes: Long?, onClick: () -> Unit) {
    val context = LocalContext.current
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = { Icon(painterResource(R.drawable.image_24), contentDescription = null) },
        trailingContent = bytes?.let { { Text(Formatter.formatShortFileSize(context, it)) } },
    ) { Text(stringResource(R.string.settings_image_cache)) }
}

/** 一行只展示内容的设置（调试工具自带标题与控件）。 */
@Composable
private fun DisplayRow(index: Int, count: Int, content: @Composable () -> Unit) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}
