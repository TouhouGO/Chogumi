package io.github.touhougo.chogumi.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.netroute.NetRoute
import io.github.touhougo.chogumi.core.netroute.RouteMode
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 「网络」分类页的状态：直连开没开（设置里存的线路）。 */
data class NetworkSettingsUiState(val direct: Boolean = false)

/** 「网络」分类页的界面事件。 */
interface NetworkSettingsActions {
    /** 开（已确认过）或关直连；核心起不来时开关留在关、发到 `failures`。 */
    fun onDirectChange(value: Boolean)
}

class NetworkSettingsViewModel(
    private val netRoute: NetRoute,
) : StateViewModel<NetworkSettingsUiState>(NetworkSettingsUiState()), NetworkSettingsActions {

    /** 核心起不来（打开时，或选着直连而核心没起来时，可重试）、设置写不进的提示。 */
    val failures = FailureNotices()

    override fun onDirectChange(value: Boolean) {
        viewModelScope.launch {
            netRoute.setMode(if (value) RouteMode.DIRECT else RouteMode.SYSTEM)
                .failureOrNull()?.let { failures.post(R.string.network_direct_failed) }
        }
    }

    init {
        follow(failures, { netRoute.mode.storageAsLoad() }) { mode -> updateState { it.copy(direct = mode == RouteMode.DIRECT) } }
        netRoute.directUnavailable
            .filter { it }
            .onEach { failures.post(R.string.network_direct_failed, retry = { onDirectChange(true) }) }
            .launchIn(viewModelScope)
    }
}
