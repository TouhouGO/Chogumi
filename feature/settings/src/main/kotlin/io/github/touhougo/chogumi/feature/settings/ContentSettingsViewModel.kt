package io.github.touhougo.chogumi.feature.settings

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.NsfwPreference
import io.github.touhougo.chogumi.core.data.repository.NsfwPreferenceRepository
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 「内容过滤」分类页的状态：NSFW 开关（屏蔽名单是各自的页）。 */
data class ContentSettingsUiState(
    /** NSFW 开关四态：真源在服务端，界面必须区分"拿不准"与"关"。 */
    val nsfw: NsfwSwitchState = NsfwSwitchState.LoggedOut,
)

/** 「内容过滤」分类页的界面事件。 */
interface ContentSettingsActions {
    fun onNsfwChange(value: Boolean)

    /** 加载失败后的重试入口。 */
    fun retryNsfw()
}

/** 「内容过滤」分类页的 ViewModel：NSFW 开关读写服务端的账号设置。 */
class ContentSettingsViewModel(
    private val nsfwPreferenceRepository: NsfwPreferenceRepository,
) : StateViewModel<ContentSettingsUiState>(ContentSettingsUiState()), ContentSettingsActions {

    /** NSFW 开关读写失败（如被服务端拒绝）的提示。 */
    val failures = FailureNotices()

    /** 读写共用的会话所有者与轮次；换号或新写让旧回调失效，令牌刷新不改变所有者。 */
    private var nsfwSession: AccountSession? = null
    private var nsfwRound = 0L
    private var nsfwLoad: Job? = null

    /**
     * 切换 NSFW 开关：PATCH 服务端，成功后以响应体写缓存。
     *
     * 在途 `pending` 与失败回滚各在一处 `updateState` 里原子提交：
     * 失败时还原为服务端旧值并提示原因，而不是把开关留在用户刚拨到的位置 ——
     * 服务端没接受，界面就不能假装接受了。
     */
    override fun onNsfwChange(value: Boolean) {
        val session = nsfwSession ?: return
        val current = currentState.nsfw
        if (current !is NsfwSwitchState.Editable || current.pending) return
        val round = ++nsfwRound
        nsfwLoad?.cancel()
        updateState { it.copy(nsfw = current.copy(pending = true)) }
        viewModelScope.launch {
            val result = nsfwPreferenceRepository.setShowNsfw(value, session)
            if (!ownsNsfw(session, round) || (result as? Outcome.Failed)?.failure == Failure.SessionChanged) return@launch
            when (result) {
                is Outcome.Success -> settleNsfw(session, round, result.value.toState())
                is Outcome.Failed -> {
                    // 403 已在缓存里记下禁用；缓存读失败仍结束在途态，只保留同一会话的已确认值。
                    val restored = outcomeOf { nsfwPreferenceRepository.cached(session) }
                    if (!ownsNsfw(session, round) || (restored as? Outcome.Failed)?.failure == Failure.SessionChanged) return@launch
                    val next = when (restored) {
                        is Outcome.Success -> restored.value?.toState() ?: current.copy(pending = false)
                        is Outcome.Failed -> current.copy(pending = false)
                    }
                    settleNsfw(session, round, next)
                    failures.post((restored as? Outcome.Failed)?.failure ?: result.failure, retry = ::retryNsfw)
                }
            }
        }
    }

    /** 加载失败后的重试入口。 */
    override fun retryNsfw() {
        if ((currentState.nsfw as? NsfwSwitchState.Editable)?.pending == true) return
        loadNsfw()
    }

    /**
     * 进入这一页即刷新（三个刷新时机之一；另两个由 `NsfwPreferenceSynchronizer`
     * 的单一观察点覆盖：登录完成与 App 启动已登录）。
     */
    init {
        // 同一数据源给出无凭据的会话代数；中间的登出拍即使被合并，同号重登也使旧读写作废。
        follow(failures, { nsfwPreferenceRepository.sessions }) { session ->
            if (session != nsfwSession) {
                nsfwSession = session
                ++nsfwRound
                nsfwLoad?.cancel()
                updateState {
                    it.copy(nsfw = if (session.authenticated) NsfwSwitchState.Unavailable(loading = true) else NsfwSwitchState.LoggedOut)
                }
                if (session.authenticated) loadNsfw()
            }
        }
    }

    /** 新读取作废同一所有者的上一轮；写在途时不发读取，换号已在会话入口清掉旧 pending。 */
    private fun loadNsfw() {
        val session = nsfwSession?.takeIf { it.authenticated } ?: return
        val round = ++nsfwRound
        nsfwLoad?.cancel()
        val shown = currentState.nsfw
        if (shown is NsfwSwitchState.Unavailable) settleNsfw(session, round, NsfwSwitchState.Unavailable(loading = true))
        nsfwLoad = viewModelScope.launch {
            val cached = when (val result = outcomeOf { nsfwPreferenceRepository.cached(session) }) {
                is Outcome.Success -> result.value
                is Outcome.Failed -> {
                    failNsfw(session, round, result.failure)
                    return@launch
                }
            }
            if (!ownsNsfw(session, round)) return@launch
            cached?.let { settleNsfw(session, round, it.toState()) }
            when (val result = nsfwPreferenceRepository.refresh(session)) {
                is Outcome.Success -> settleNsfw(session, round, result.value.toState())
                is Outcome.Failed -> failNsfw(session, round, result.failure)
            }
        }
    }

    /** 每一处结果发布共用这一个裁决，旧写不能取消或恢复新会话的读取。 */
    private fun ownsNsfw(session: AccountSession, round: Long): Boolean = nsfwSession == session && nsfwRound == round

    /** 读写的结果只落在它所属的会话与轮次；不以任意 pending 拦住另一账号的结果。 */
    private fun settleNsfw(session: AccountSession, round: Long, next: NsfwSwitchState) {
        if (ownsNsfw(session, round)) updateState { it.copy(nsfw = next) }
    }

    /** 无内容的失败留在开关失败态；有内容则保留值并通知，重试仍走同一读取入口。 */
    private fun failNsfw(session: AccountSession, round: Long, failure: Failure) {
        if (!ownsNsfw(session, round) || failure == Failure.SessionChanged) return
        when (currentState.nsfw) {
            is NsfwSwitchState.Editable, is NsfwSwitchState.Locked -> failures.post(failure, retry = ::retryNsfw)
            else -> settleNsfw(session, round, NsfwSwitchState.Unavailable(loading = false, failureReason = failure))
        }
    }

    private fun NsfwPreference.toState(): NsfwSwitchState =
        if (canSetNsfwSubject) NsfwSwitchState.Editable(this) else NsfwSwitchState.Locked(this)
}
