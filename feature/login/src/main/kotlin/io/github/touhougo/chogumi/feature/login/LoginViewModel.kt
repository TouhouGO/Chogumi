package io.github.touhougo.chogumi.feature.login

import androidx.lifecycle.viewModelScope
import io.github.touhougo.chogumi.core.common.notice.FailureNotices
import io.github.touhougo.chogumi.core.common.notice.NoticeAction
import io.github.touhougo.chogumi.core.common.viewmodel.StateViewModel
import io.github.touhougo.chogumi.core.data.repository.AuthRepository
import io.github.touhougo.chogumi.core.data.repository.LoginAttempt
import io.github.touhougo.chogumi.core.data.repository.PasswordCaptcha
import io.github.touhougo.chogumi.core.data.util.launchWrite
import io.github.touhougo.chogumi.core.data.util.loadOutcome
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.hiddenFailureOf
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.netroute.DirectRouteHint
import io.github.touhougo.chogumi.core.netroute.looksUnreachable
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 账号密码登录表单里填的东西。 */
data class PasswordForm(
    val email: String = "",
    val password: String = "",
    val captcha: String = "",
) {
    val complete: Boolean get() = email.isNotBlank() && password.isNotEmpty() && captcha.isNotBlank()
}

data class LoginUiState(
    val processing: Boolean = false,
    val manualToken: String = "",
    val form: PasswordForm = PasswordForm(),
    /** 当前这张验证码（连同它所属的那次登录）。 */
    val captcha: Load<PasswordCaptcha> = Load.Pending,
)

/**
 * 登录页的状态：全 App 只有一份（见 [LoginScreen]），各处的登录页是同一张验证码、同一份表单。
 * 没登录时备好一次登录（一张验证码）；登上了就丢掉这次登录与填过的字，登出后再备一次新的。
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    directRouteHint: DirectRouteHint,
) : StateViewModel<LoginUiState>(LoginUiState()) {

    /** 登录失败（账号密码或验证码不对、授权回调出错、token 无效）与输入为空的提示；连不上 bgm.tv 时是「试试直连」。 */
    val failures = FailureNotices()

    /** 连不上 bgm.tv 时提示不提示「试试直连」：设备支持、且现在走系统网络。 */
    private val directAvailable = directRouteHint.available().stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 在途的一次提交（账号密码或令牌）。[LoginUiState.processing] 由仓库异步回流、晚一帧，
     * 同一帧里点两下它还是 false，所以重复提交拦在这里。
     */
    private var loginJob: Job? = null

    private val submitting: Boolean get() = currentState.processing || loginJob?.isActive == true

    init {
        authRepository.loginProcessing
            .onEach { processing -> updateState { it.copy(processing = processing) } }
            .launchIn(viewModelScope)

        // 只提示本 ViewModel 在时发生的失败：订阅时已有的那条属于它之前的界面（Activity 结束过又重开），早已提示过。
        authRepository.loginError
            .drop(1)
            .filterNotNull()
            .onEach(::postFailure)
            .launchIn(viewModelScope)

        authRepository.isLoggedIn
            .onEach { loggedIn -> if (loggedIn) discardAttempt() else loadCaptcha(attempt = null) }
            .launchIn(viewModelScope)
    }

    private fun suggestsDirect(failure: Failure) = directAvailable.value && looksUnreachable(failure)

    /**
     * 验证码没取到：连不上 bgm.tv 时提示「试试直连」（框里有没有旧图都是）；否则只有旧图还在框里（框里看不出来，[hiddenFailureOf]）时
     * 说一声、带重试，没有图时失败画在框里。
     */
    private fun noticeCaptchaFailure(load: Load<PasswordCaptcha>, attempt: LoginAttempt?) {
        val failure = (load as? Load.Failed)?.failure ?: return
        when {
            suggestsDirect(failure) -> failures.post(R.string.login_unreachable, TryDirectRoute)
            currentState.captcha.hiddenFailureOf(load) != null -> failures.post(failure, retry = { loadCaptcha(attempt) })
        }
    }

    private fun postFailure(failure: Failure) {
        if (suggestsDirect(failure)) failures.post(R.string.login_unreachable, TryDirectRoute) else failures.post(failure)
    }

    /** 登上了：这次登录用完了，填过的字（含密码）不留到下次登出后。 */
    private fun discardAttempt() {
        cancelSection(CAPTCHA_SECTION)
        updateState { LoginUiState(processing = it.processing) }
    }

    fun onFormChange(form: PasswordForm) {
        updateState { it.copy(form = form) }
    }

    /** 点验证码图：换一张，接着用这次登录（这次登录还没开始成就重新开始）。 */
    fun onCaptchaRefresh() {
        loadCaptcha(attempt = currentState.captcha.valueOrNull()?.attempt)
    }

    /** 换一张验证码，填过的字作废。 */
    private fun loadCaptcha(attempt: LoginAttempt?) {
        updateState { it.copy(form = it.form.copy(captcha = "")) }
        // 验证码的失败提示只在这一处定（区块自己不发）：在结果落到页面状态之前，页面看到失败时提示已经在了
        launchSection(CAPTCHA_SECTION, failures = null, {
            loadOutcome { authRepository.passwordCaptcha(attempt) }.onEach { noticeCaptchaFailure(it, attempt) }
        }, { it.captcha }) {
            copy(captcha = it)
        }
    }

    fun onPasswordLogin() {
        val form = currentState.form
        val attempt = currentState.captcha.valueOrNull()?.attempt
        if (!form.complete || attempt == null || submitting) return
        loginJob = viewModelScope.launch {
            // 结果经 isLoggedIn / loginError 回流到 UI。没登录上时网站要的是新的一次登录、新的验证码。
            if (!authRepository.loginWithPassword(attempt, form.email.trim(), form.password, form.captcha.trim())) {
                loadCaptcha(attempt = null)
            }
        }
    }

    fun onBrowserLogin(onReady: (url: String) -> Unit) {
        viewModelScope.launchWrite(failures) {
            onReady(authRepository.buildAuthorizeUrl())
        }
    }

    fun onManualTokenChange(value: String) {
        updateState { it.copy(manualToken = value) }
    }

    fun onManualTokenDismiss() = clearManualToken()

    fun onManualTokenSubmit() {
        if (submitting) return

        val token = currentState.manualToken.trim()
        clearManualToken()
        if (token.isEmpty()) {
            failures.post(R.string.login_token_required)
            return
        }
        loginJob = viewModelScope.launch {
            // 结果经 isLoggedIn / loginError StateFlow 回流到 UI
            authRepository.loginWithToken(token)
        }
    }

    private fun clearManualToken() = updateState { it.copy(manualToken = "") }
}

private const val CAPTCHA_SECTION = "captcha"

/** 提示条上的「试试直连」：点了去设置的「网络」页（[LoginScreen] 认出它）。 */
internal val TryDirectRoute = NoticeAction(R.string.login_try_direct)
