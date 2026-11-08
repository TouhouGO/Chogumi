package io.github.touhougo.chogumi.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.UserRepository
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.model.UserBo
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 设置一级菜单的状态：最上面一行自己的资料，图片缓存的占用；分类行没有状态。 */
data class SettingsUiState(
    /** 自己的资料（头像与显示名），只在登录时画；游客是 [Load.Failed]，不发请求。 */
    val me: Load<UserBo> = Load.Pending,
    /** 图片缓存占的字节数：进页量一次、清理后再量一次；量到之前为 null，不画数字。 */
    val imageCacheBytes: Long? = null,
)

/** 设置一级菜单的界面事件（失败提示不在这里：走 ViewModel 的 `failures`）。 */
interface SettingsActions {
    /** 清掉缓存的图片。 */
    fun clearImageCache()

    fun logout()
}

/** 设置一级菜单的 ViewModel：自己的资料那一行、图片缓存与退出登录；各分类的设置在各自的页里。 */
class SettingsViewModel(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val imageCache: ImageCache,
) : StateViewModel<SettingsUiState>(SettingsUiState()), SettingsActions {

    /** 清缓存、登出失败、内容已显示时读资料失败的提示。 */
    val failures = FailureNotices()

    override fun logout() {
        viewModelScope.launchWrite(failures) { authRepository.logout() }
    }

    override fun clearImageCache() {
        showImageCache(clear = true)
    }

    /** 界面只画量出来的占用：清理后再量一次，不自己写零。失败时留着上一次量到的数，提示里可重试同一件事。 */
    private fun showImageCache(clear: Boolean = false) {
        viewModelScope.launchWrite(failures, retry = { showImageCache(clear) }) {
            if (clear) imageCache.clear()
            val bytes = imageCache.size()
            updateState { it.copy(imageCacheBytes = bytes) }
        }
    }

    private fun loadMe(refresh: Boolean) {
        launchSection("me", failures, { userRepository.getMe(refresh = refresh) }, { it.me }) { copy(me = it) }
    }

    init {
        loadMe(refresh = false)
        // 在个人资料页改成之后，这一行的头像与昵称跟着变
        viewModelScope.launch { userRepository.profileEdits.drop(1).collect { loadMe(refresh = true) } }
        // 图片缓存没有变化通知：进页量一次，之后只在自己清过之后再量。
        showImageCache()
    }
}
