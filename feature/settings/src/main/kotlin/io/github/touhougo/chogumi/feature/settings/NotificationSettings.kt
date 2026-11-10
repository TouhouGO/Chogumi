package io.github.touhougo.chogumi.feature.settings

import android.content.Context
import android.text.format.DateFormat
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.model.AiringReminderMode
import io.github.touhougo.chogumi.core.model.CommunityCheckInterval
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SystemNotices
import io.github.touhougo.chogumi.core.ui.common.navigation.SettingsSection
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.TimeSelectDialog
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.koin.compose.viewmodel.koinViewModel

/** 系统那一边此刻的样子：App 能不能发通知，电池优化放没放过这个 App。页面每次回到前台时重读（用户可能刚从系统设置回来）。 */
data class SystemNoticeState(val allowed: Boolean, val backgroundExempt: Boolean) {
    companion object {
        fun of(context: Context) = SystemNoticeState(SystemNotices.allowed(context), SystemNotices.backgroundExempt(context))
    }
}

/** 一个提醒开关：社区提醒或放送提醒。 */
enum class NoticeSwitch { COMMUNITY, AIRING }

/**
 * 「通知」分类页：社区提醒（开关、检查间隔）、放送提醒（开关、提醒方式、每天定时的时刻），任一开着时再有通知类别与后台运行。
 *
 * 打开开关时先过系统：Android 13 起没给通知权限就弹系统的权限框，给了才算打开；系统里关掉了通知（或权限拒绝过两次、系统不再弹框）
 * 就记下打开、去系统的通知设置页，回来时开关跟着系统亮。第一次打开任何一个时，电池优化没放过就弹一次系统的「后台运行」对话框。
 */
@Composable
internal fun NotificationSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: NotificationSettingsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var system by remember { mutableStateOf(SystemNoticeState.of(context)) }
    LifecycleResumeEffect(context) {
        system = SystemNoticeState.of(context)
        onPauseOrDispose { }
    }
    // 弹权限框时要打开的是哪一个：框回来之后接着打开它（进程重建后也还记得）
    var asking by rememberSaveable { mutableStateOf<NoticeSwitch?>(null) }

    fun enable(switch: NoticeSwitch) {
        when (switch) {
            NoticeSwitch.COMMUNITY -> viewModel.onCommunityChange(true)
            NoticeSwitch.AIRING -> viewModel.onAiringChange(true)
        }
        if (!uiState.backgroundPrompted && !SystemNotices.backgroundExempt(context)) {
            viewModel.onBackgroundPrompted()
            SystemNotices.requestBackgroundExemption(context)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val switch = asking ?: return@rememberLauncherForActivityResult
        asking = null
        system = SystemNoticeState.of(context)
        when {
            granted -> enable(switch)
            // 系统不再弹框（拒绝过两次）：只剩系统设置这一条路
            activity?.shouldShowRequestPermissionRationale(SystemNotices.PERMISSION) == false -> {
                enable(switch)
                SystemNotices.openNotificationSettings(context)
            }
        }
    }

    NotificationSettingsContent(
        state = uiState,
        system = system,
        actions = viewModel,
        failures = viewModel.failures,
        onSwitch = { switch, on ->
            when {
                !on -> when (switch) {
                    NoticeSwitch.COMMUNITY -> viewModel.onCommunityChange(false)
                    NoticeSwitch.AIRING -> viewModel.onAiringChange(false)
                }
                system.allowed -> enable(switch)
                SystemNotices.permissionNeeded(context) -> {
                    asking = switch
                    permission.launch(SystemNotices.PERMISSION)
                }
                else -> {
                    enable(switch)
                    SystemNotices.openNotificationSettings(context)
                }
            }
        },
        onCategories = { SystemNotices.openNotificationSettings(context) },
        onBackground = {
            if (system.backgroundExempt) SystemNotices.openBackgroundSettings(context) else SystemNotices.requestBackgroundExemption(context)
        },
        onBack = onBack,
        modifier = modifier,
    )
}

/** 「通知」分类页的无状态内容层：状态与事件都从外部传入，系统那一边（[system]）也是。 */
@Composable
fun NotificationSettingsContent(
    state: NotificationSettingsUiState,
    system: SystemNoticeState,
    actions: NotificationSettingsActions,
    failures: FailureNotices,
    /** 用户拨了一个开关：打开时先过系统的权限（见 [NotificationSettingsScreen]）。 */
    onSwitch: (NoticeSwitch, Boolean) -> Unit,
    onCategories: () -> Unit,
    onBackground: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    // 开关亮不亮：设置开着、系统也让发；社区提醒在专注模式下不亮也不能拨
    val communityOn = settings.community && system.allowed && state.communityAvailable
    val airingOn = settings.airing && system.allowed
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    SettingsListPage(
        title = stringResource(SettingsSection.NOTIFICATIONS.title),
        failures = failures,
        onBack = onBack,
        modifier = modifier,
    ) {
        settingsGroups(
            listOfNotNull(
                SettingsGroup(
                    key = "community",
                    title = null,
                    rows = listOfNotNull<SettingsRow>(
                        { index, count ->
                            val reason = when {
                                !state.communityAvailable -> R.string.notify_focus_mode_on
                                settings.community && !system.allowed -> R.string.notify_system_off
                                else -> null
                            }
                            SwitchRow(
                                index = index,
                                count = count,
                                title = stringResource(R.string.notify_community),
                                checked = communityOn,
                                onCheckedChange = { onSwitch(NoticeSwitch.COMMUNITY, it) },
                                switchTag = CommunitySwitchTag,
                                enabled = state.communityAvailable,
                                details = reason?.let { { Text(stringResource(it)) } },
                            )
                        },
                        if (communityOn) {
                            { index, count ->
                                ChoiceRow(
                                    index = index,
                                    count = count,
                                    title = stringResource(R.string.notify_community_interval),
                                    options = CommunityCheckInterval.entries,
                                    selected = settings.interval,
                                    label = { stringResource(it.label) },
                                    onSelect = actions::onIntervalChange,
                                )
                            }
                        } else {
                            null
                        },
                    ),
                ),
                SettingsGroup(
                    key = "airing",
                    title = null,
                    rows = listOfNotNull<SettingsRow>(
                        { index, count ->
                            SwitchRow(
                                index = index,
                                count = count,
                                title = stringResource(R.string.notify_airing),
                                checked = airingOn,
                                onCheckedChange = { onSwitch(NoticeSwitch.AIRING, it) },
                                switchTag = AiringSwitchTag,
                                details = if (settings.airing && !system.allowed) {
                                    { Text(stringResource(R.string.notify_system_off)) }
                                } else {
                                    null
                                },
                            )
                        },
                        if (airingOn) {
                            { index, count ->
                                ChoiceRow(
                                    index = index,
                                    count = count,
                                    title = stringResource(R.string.notify_airing_mode),
                                    options = AiringReminderMode.entries,
                                    selected = settings.airingMode,
                                    label = { stringResource(it.label) },
                                    onSelect = actions::onAiringModeChange,
                                )
                            }
                        } else {
                            null
                        },
                        if (airingOn && settings.airingMode == AiringReminderMode.DAILY) {
                            { index, count -> DailyTimeRow(index, count, settings.dailyMinute) { pickingTime = true } }
                        } else {
                            null
                        },
                    ),
                ),
                if (communityOn || airingOn) {
                    SettingsGroup(
                        key = "system",
                        title = null,
                        rows = listOfNotNull<SettingsRow>(
                            if (SystemNotices.channelsSupported) {
                                { index, count -> LinkRow(index, count, stringResource(R.string.notify_categories), onClick = onCategories) }
                            } else {
                                null
                            },
                            { index, count -> BackgroundRow(index, count, system.backgroundExempt, onBackground) },
                        ),
                    )
                } else {
                    null
                },
            ),
        )
    }
    if (pickingTime) {
        TimeSelectDialog(
            minuteOfDay = settings.dailyMinute,
            onConfirm = actions::onDailyMinuteChange,
            onDismiss = { pickingTime = false },
        )
    }
}

/** 「提醒时间」：尾部是选好的时刻（按系统的 12 / 24 小时制），整行点开选时间的对话框。 */
@Composable
private fun DailyTimeRow(index: Int, count: Int, minuteOfDay: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    val time = remember(minuteOfDay, context) { timeLabelOf(context, minuteOfDay) }
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        trailingContent = { Text(time) },
    ) { Text(stringResource(R.string.notify_airing_time)) }
}

/** 「后台运行」：电池优化没放过时标题下写原因、点了弹系统的对话框；放过了就是去厂商设置的一行。 */
@Composable
private fun BackgroundRow(index: Int, count: Int, exempt: Boolean, onClick: () -> Unit) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        trailingContent = { Icon(painterResource(R.drawable.chevron_right_24), contentDescription = null) },
        details = if (exempt) null else ({ Text(stringResource(R.string.notify_background_restricted)) }),
    ) { Text(stringResource(R.string.notify_background)) }
}

/** 一天里的时刻按系统的 12 / 24 小时制写（「09:00」「上午9:00」）。 */
private fun timeLabelOf(context: Context, minuteOfDay: Int): String {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
    return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

/** 检查间隔在设置里的名字。 */
private val CommunityCheckInterval.label: Int
    get() = when (this) {
        CommunityCheckInterval.MINUTES_15 -> R.string.notify_interval_15m
        CommunityCheckInterval.MINUTES_30 -> R.string.notify_interval_30m
        CommunityCheckInterval.HOUR_1 -> R.string.notify_interval_1h
        CommunityCheckInterval.HOURS_3 -> R.string.notify_interval_3h
    }

/** 提醒方式在设置里的名字。 */
private val AiringReminderMode.label: Int
    get() = when (this) {
        AiringReminderMode.AT_AIRING -> R.string.notify_airing_at_airing
        AiringReminderMode.DAILY -> R.string.notify_airing_daily
    }

/** 测试定位两个开关。 */
const val CommunitySwitchTag = "notify_community_switch"
const val AiringSwitchTag = "notify_airing_switch"
