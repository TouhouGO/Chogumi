package io.github.touhougo.chogumi.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.PreferencesRepository
import io.github.touhougo.chogumi.core.data.update.AppUpdates
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.model.UpdateCheckInterval
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 「立即检查」那一行的尾部：还没点、正在查、查完已是最新。查到新版时回到 [IDLE]，新版由外壳的提示框说。 */
enum class ManualUpdateCheck { IDLE, CHECKING, UP_TO_DATE }

/** 「更新」分类页的状态：接收预发布版、自动检查的开关与间隔（本地偏好），「立即检查」进行到哪。 */
data class UpdateSettingsUiState(
    val prerelease: Boolean = false,
    val autoCheck: Boolean = true,
    val interval: UpdateCheckInterval = UpdateCheckInterval.DEFAULT,
    val manualCheck: ManualUpdateCheck = ManualUpdateCheck.IDLE,
)

/** 「更新」分类页的界面事件（失败提示不在这里：走 ViewModel 的 `failures`）。 */
interface UpdateSettingsActions {
    /** 接收预发布版：打开时记下之后立刻按新通道查一次（同「立即检查」）。 */
    fun onPrereleaseChange(value: Boolean)

    fun onAutoCheckChange(value: Boolean)

    fun onIntervalChange(value: UpdateCheckInterval)

    /** 立即去查一次：有新版由外壳弹提示框，已是最新写在行尾，取不到发到 `failures`（可重试）。 */
    fun checkNow()
}

/** 「更新」分类页的 ViewModel：开关与间隔只写偏好、状态只从偏好来；「立即检查」（与打开预发布通道后的那一次）经 [AppUpdates.checkNow]。 */
class UpdateSettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val appUpdates: AppUpdates,
) : StateViewModel<UpdateSettingsUiState>(UpdateSettingsUiState()), UpdateSettingsActions {

    /** 偏好读写失败、「立即检查」取不到的提示。 */
    val failures = FailureNotices()

    override fun onPrereleaseChange(value: Boolean) {
        viewModelScope.launch {
            val failure = storageOutcomeOf { preferencesRepository.setUpdatePrerelease(value) }.failureOrNull()
            if (failure != null) failures.post(failure, retry = { onPrereleaseChange(value) }) else if (value) checkNow()
        }
    }

    override fun onAutoCheckChange(value: Boolean) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setUpdateCheck(value) }
    }

    override fun onIntervalChange(value: UpdateCheckInterval) {
        viewModelScope.launchWrite(failures) { preferencesRepository.setUpdateCheckInterval(value) }
    }

    /**
     * 正在查时又要查（查的时候打开了预发布通道）：记下，这一轮结束后再查一次。正在跑的那一轮是按旧设置取的，
     * 它的结果不算数，不丢掉这一次请求。
     */
    private var recheck = false

    override fun checkNow() {
        if (currentState.manualCheck == ManualUpdateCheck.CHECKING) {
            recheck = true
            return
        }
        updateState { it.copy(manualCheck = ManualUpdateCheck.CHECKING) }
        viewModelScope.launch {
            val result = appUpdates.checkNow()
            if (recheck) {
                recheck = false
                updateState { it.copy(manualCheck = ManualUpdateCheck.IDLE) }
                checkNow()
                return@launch
            }
            val next = if (result is Outcome.Success && result.value == null) ManualUpdateCheck.UP_TO_DATE else ManualUpdateCheck.IDLE
            updateState { it.copy(manualCheck = next) }
            if (result is Outcome.Failed) failures.post(result.failure, retry = ::checkNow)
        }
    }

    init {
        follow(failures, {
            combine(preferencesRepository.updatePrerelease, preferencesRepository.updateCheck, preferencesRepository.updateCheckInterval, ::Triple)
                .storageAsLoad()
        }) { (prerelease, autoCheck, interval) -> updateState { it.copy(prerelease = prerelease, autoCheck = autoCheck, interval = interval) } }
    }
}
