package io.github.touhougo.chogumi.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.model.AiringReminderMode
import io.github.touhougo.chogumi.core.model.CommunityCheckInterval
import io.github.touhougo.chogumi.core.model.NotificationSettings
import kotlinx.coroutines.flow.combine

/**
 * 「通知」分类页的状态：两类提醒的设置（本地偏好），社区功能开没开（专注模式下社区提醒置灰），第一次打开时问没问过「后台运行」。
 * 系统那一边（通知开没开、电池优化放没放）不在这里：页面每次回到前台时从系统读（`SystemNotices`）。
 */
data class NotificationSettingsUiState(
    val settings: NotificationSettings = NotificationSettings(),
    val communityAvailable: Boolean = true,
    val backgroundPrompted: Boolean = true,
)

/** 「通知」分类页的界面事件（失败提示走 ViewModel 的 `failures`）。 */
interface NotificationSettingsActions {
    fun onCommunityChange(value: Boolean)

    fun onIntervalChange(value: CommunityCheckInterval)

    fun onAiringChange(value: Boolean)

    fun onAiringModeChange(value: AiringReminderMode)

    fun onDailyMinuteChange(value: Int)

    /** 第一次打开提醒时弹过了系统的「后台运行」对话框：之后不再自动弹。 */
    fun onBackgroundPrompted()
}

/**
 * 「通知」分类页的 ViewModel：只写偏好、状态只从偏好来。开关变了之后排后台检查与闹钟的是应用级的观察（feature:community 的社区提醒、
 * feature:home 的放送提醒），不在这里。
 */
class NotificationSettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
) : StateViewModel<NotificationSettingsUiState>(NotificationSettingsUiState()), NotificationSettingsActions {

    /** 偏好读写失败的提示。 */
    val failures = FailureNotices()

    override fun onCommunityChange(value: Boolean) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setCommunityNotifications(value) }
    }

    override fun onIntervalChange(value: CommunityCheckInterval) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setCommunityCheckInterval(value) }
    }

    override fun onAiringChange(value: Boolean) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setAiringNotifications(value) }
    }

    override fun onAiringModeChange(value: AiringReminderMode) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setAiringReminderMode(value) }
    }

    override fun onDailyMinuteChange(value: Int) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setAiringDailyMinute(value) }
    }

    override fun onBackgroundPrompted() {
        viewModelScope.launchWrite(failures) { preferencesRepository.setBackgroundPrompted() }
    }

    init {
        follow(failures, {
            combine(
                preferencesRepository.notificationSettings,
                preferencesRepository.appFeatures,
                preferencesRepository.backgroundPrompted,
            ) { settings, features, prompted ->
                NotificationSettingsUiState(settings, communityAvailable = features.community, backgroundPrompted = prompted)
            }.storageAsLoad()
        }) { value -> updateState { value } }
    }
}
