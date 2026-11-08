package io.github.touhougo.chogumi.shell

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.update.AppUpdates
import io.github.touhougo.chogumi.core.data.update.UpdateDownload
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.AppRelease
import java.io.File
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 外壳上的更新提示框此刻画什么：什么也不画，提示有新版，下载中，下载失败；下好了交给系统安装器。 */
sealed interface AppUpdatePrompt {
    data object None : AppUpdatePrompt

    data class Offer(val release: AppRelease) : AppUpdatePrompt

    data class Downloading(val release: AppRelease, val written: Long, val total: Long?) : AppUpdatePrompt

    data class Ready(val release: AppRelease, val file: File) : AppUpdatePrompt

    data class Failed(val release: AppRelease, val failure: Failure) : AppUpdatePrompt
}

/** 下载有进展时画下载，否则画要提示的那一版（[AppUpdates] 开始下载时已清掉提示）。 */
internal fun appUpdatePromptOf(offer: AppRelease?, download: UpdateDownload): AppUpdatePrompt = when (download) {
    is UpdateDownload.Running -> AppUpdatePrompt.Downloading(download.release, download.written, download.total)
    is UpdateDownload.Ready -> AppUpdatePrompt.Ready(download.release, download.file)
    is UpdateDownload.Failed -> AppUpdatePrompt.Failed(download.release, download.failure)
    UpdateDownload.Idle -> offer?.let(AppUpdatePrompt::Offer) ?: AppUpdatePrompt.None
}

/** 外壳的更新提示框：状态只从 [AppUpdates] 来，每个选择原样交给它。 */
class AppUpdateViewModel(private val appUpdates: AppUpdates) : StateViewModel<AppUpdatePrompt>(AppUpdatePrompt.None) {

    /** 点遮罩或返回：这次先关。 */
    fun dismiss() = appUpdates.dismiss()

    fun ignore(release: AppRelease) = appUpdates.ignore(release)

    fun stopPrompting() = appUpdates.stopChecking()

    /** 下载，或下载失败后重试。 */
    fun download(release: AppRelease) = appUpdates.startDownload(release)

    /** 取消下载、在失败框上关掉或改用浏览器、已交给系统安装器：都回到什么也没有。 */
    fun closeDownload() = appUpdates.closeDownload()

    init {
        viewModelScope.launch {
            combine(appUpdates.offer, appUpdates.download, ::appUpdatePromptOf).collect { prompt -> updateState { prompt } }
        }
    }
}
