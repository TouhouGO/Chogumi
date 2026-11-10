package io.github.touhougo.chogumi.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.util.storageAsLoad
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.netroute.CheckSite
import io.github.touhougo.chogumi.core.netroute.NetRoute
import io.github.touhougo.chogumi.core.netroute.ProxyConnect
import io.github.touhougo.chogumi.core.netroute.ProxyServer
import io.github.touhougo.chogumi.core.netroute.ProxyType
import io.github.touhougo.chogumi.core.netroute.RouteCheck
import io.github.touhougo.chogumi.core.netroute.RouteMode
import io.github.touhougo.chogumi.core.netroute.proxyHostInput
import io.github.touhougo.chogumi.core.netroute.proxyPortInput
import io.github.touhougo.chogumi.core.netroute.proxyServerOf
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 代理输入框里的那一组（还没连上之前只是草稿）。 */
data class ProxyDraft(val type: ProxyType = ProxyType.SOCKS5, val host: String = "", val port: String = "") {
    /** 填好了的代理；没填好时为 null。 */
    val server: ProxyServer? get() = proxyServerOf(type, host, port)
}

/** 「连接」这一下的进度：在连、连不上（输入改了就回到空闲），其余是空闲。 */
enum class ProxyConnecting { IDLE, BUSY, FAILED }

/** 「检查连接」一格：还在测、连上了（毫秒）、连不上。 */
sealed interface CheckResult {
    data object Pending : CheckResult

    data class Millis(val value: Long) : CheckResult

    data object Unreachable : CheckResult
}

/** 「网络」分类页的状态。 */
data class NetworkSettingsUiState(
    /** 设置里存的线路（直连与代理互斥，都不开是系统网络）。 */
    val mode: RouteMode = RouteMode.SYSTEM,
    /** 设备能不能用直连：不能时没有直连那一块。 */
    val directSupported: Boolean = false,
    val draft: ProxyDraft = ProxyDraft(),
    /** 正在用的代理（已连上）。 */
    val active: ProxyServer? = null,
    val connecting: ProxyConnecting = ProxyConnecting.IDLE,
    /** 这一轮「检查连接」的结果，按站点；还没测过、或线路换了之后为 null。 */
    val check: Map<CheckSite, CheckResult>? = null,
) {
    /** 「连接」能不能点：不在连、填好了、而且和正在用的那一组不一样（连不上之后可以再点）。 */
    val canConnect: Boolean
        get() = connecting != ProxyConnecting.BUSY && draft.server != null &&
            (draft.server != active || connecting == ProxyConnecting.FAILED)

    /** 代理那一块的状态行。 */
    val proxyStatus: ProxyStatus
        get() = when {
            connecting == ProxyConnecting.BUSY -> ProxyStatus.CONNECTING
            connecting == ProxyConnecting.FAILED -> ProxyStatus.UNREACHABLE
            active != null && draft.server == active -> ProxyStatus.CONNECTED
            active != null -> ProxyStatus.CHANGED
            else -> ProxyStatus.FILL_IN
        }

    /** 「检查连接」正在测。 */
    val checking: Boolean get() = check?.values?.any { it == CheckResult.Pending } == true
}

/** 代理那一块状态行说的事。 */
enum class ProxyStatus { CONNECTING, UNREACHABLE, CONNECTED, CHANGED, FILL_IN }

/** 「网络」分类页的界面事件。 */
interface NetworkSettingsActions {
    /** 开（已确认过）或关直连；核心起不来时开关留在关、发到 `failures`。打开时代理随之关掉。 */
    fun onDirectChange(value: Boolean)

    /** 开或关代理；打开时直连随之关掉，连上过的那一组带出来并自动连一次。 */
    fun onProxyChange(value: Boolean)

    fun onProxyType(type: ProxyType)

    fun onProxyHost(text: String)

    fun onProxyPort(text: String)

    /** 经填好的那一组试连 bgm.tv，连上了才用它。 */
    fun onProxyConnect()

    /** 按现在的线路测一轮几个站点。 */
    fun onCheck()
}

class NetworkSettingsViewModel(
    private val netRoute: NetRoute,
    private val routeCheck: RouteCheck,
) : StateViewModel<NetworkSettingsUiState>(NetworkSettingsUiState(directSupported = netRoute.directSupported)),
    NetworkSettingsActions {

    /** 核心起不来（打开时，或选着直连而核心没起来时，可重试）、设置写不进的提示。 */
    val failures = FailureNotices()

    private var connectJob: Job? = null
    private var checkJobs: List<Job> = emptyList()

    /** 存下的那一组（连上过的）；草稿还空着时第一次读到就带进输入框。 */
    private var saved: ProxyServer? = null

    override fun onDirectChange(value: Boolean) = switchTo(if (value) RouteMode.DIRECT else RouteMode.SYSTEM, R.string.network_direct_failed)

    override fun onProxyChange(value: Boolean) {
        if (!value) {
            updateState { it.copy(draft = saved?.let(::draftOf) ?: it.draft) }
            switchTo(RouteMode.SYSTEM, null)
            return
        }
        viewModelScope.launch {
            val switched = netRoute.setMode(RouteMode.PROXY)
            switched.failureOrNull()?.let { failures.post(it); return@launch }
            saved?.let { server ->
                updateState { it.copy(draft = draftOf(server)) }
                onProxyConnect()
            }
        }
    }

    override fun onProxyType(type: ProxyType) = editDraft { it.copy(type = type) }

    override fun onProxyHost(text: String) = editDraft { it.copy(host = proxyHostInput(text)) }

    override fun onProxyPort(text: String) = editDraft { it.copy(port = proxyPortInput(text)) }

    override fun onProxyConnect() {
        if (!currentState.canConnect) return
        val server = currentState.draft.server ?: return
        updateState { it.copy(connecting = ProxyConnecting.BUSY) }
        connectJob = viewModelScope.launch {
            val result = netRoute.connectProxy(server)
            updateState { it.copy(connecting = if (result is ProxyConnect.Unreachable) ProxyConnecting.FAILED else ProxyConnecting.IDLE) }
            if (result is ProxyConnect.NotSaved) failures.post(result.failure)
        }
    }

    override fun onCheck() {
        if (currentState.checking) return
        checkJobs.forEach { it.cancel() }
        routeCheck.startRound()
        updateState { it.copy(check = CheckSite.entries.associateWith { CheckResult.Pending }) }
        checkJobs = CheckSite.entries.map { site ->
            viewModelScope.launch {
                val result = when (val measured = routeCheck.measure(site)) {
                    is Outcome.Success -> CheckResult.Millis(measured.value)
                    is Outcome.Failed -> CheckResult.Unreachable
                }
                updateState { state -> state.copy(check = state.check?.plus(site to result)) }
            }
        }
    }

    private fun editDraft(change: (ProxyDraft) -> ProxyDraft) = updateState {
        it.copy(draft = change(it.draft), connecting = if (it.connecting == ProxyConnecting.FAILED) ProxyConnecting.IDLE else it.connecting)
    }

    /** 换到代理以外的线路：在途的「连接」作废（它的结果不会再用），状态回到空闲，再打开代理时能重新连。 */
    private fun switchTo(mode: RouteMode, failedText: Int?) {
        connectJob?.cancel()
        updateState { it.copy(connecting = ProxyConnecting.IDLE) }
        viewModelScope.launch {
            val failure = netRoute.setMode(mode).failureOrNull() ?: return@launch
            if (failedText != null) failures.post(failedText) else failures.post(failure)
        }
    }

    init {
        follow(failures, { netRoute.mode.storageAsLoad() }) { mode -> updateState { it.copy(mode = mode) } }
        follow(failures, { netRoute.proxy.storageAsLoad() }) { server ->
            saved = server
            if (server != null && currentState.draft == ProxyDraft()) updateState { it.copy(draft = draftOf(server)) }
        }
        netRoute.activeProxy.onEach { active -> updateState { it.copy(active = active) } }.launchIn(viewModelScope)
        // 线路换了（开关直连或代理、连上另一组代理），上一轮的结果不再说明现在的线路
        combine(netRoute.mode, netRoute.activeProxy) { mode, active -> mode to active }
            .distinctUntilChanged()
            .drop(1)
            .onEach {
                checkJobs.forEach { job -> job.cancel() }
                updateState { state -> state.copy(check = null) }
            }
            .launchIn(viewModelScope)
        netRoute.directUnavailable
            .filter { it }
            .onEach { failures.post(R.string.network_direct_failed, retry = { onDirectChange(true) }) }
            .launchIn(viewModelScope)
    }
}

private fun draftOf(server: ProxyServer) = ProxyDraft(server.type, server.host, server.port.toString())
