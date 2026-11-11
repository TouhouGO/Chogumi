package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.util.storageOutcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.model.SignedInAccount
import io.github.touhougo.chogumi.core.network.api.AuthApi
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.api.WebLoginApi
import io.github.touhougo.chogumi.core.network.api.WebLoginAttempt
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一次账号密码登录（不透明）：网页登录途中的状态留在数据层，界面只把它原样交回 [AuthRepository]。 */
class LoginAttempt internal constructor(internal val web: WebLoginAttempt)

/** 账号密码登录的一张验证码图（GIF），连同它所属的那次登录：提交时把 [attempt] 交回 [AuthRepository.loginWithPassword]。 */
class PasswordCaptcha(val attempt: LoginAttempt, val image: ByteArray)

/** 本地会话唯一来源；初始化失败与确认游客严格区分，业务网络不阻塞启动。 */
class AuthRepository(
    private val authApi: AuthApi,
    private val userApi: UserApi,
    private val webLoginApi: WebLoginApi,
    private val webSessions: WebSessionRepository,
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope,
) {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Initializing)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()
    private val _loginProcessing = MutableStateFlow(false)
    val loginProcessing: StateFlow<Boolean> = _loginProcessing.asStateFlow()
    /** 最近一次登录失败的原因；开始新一次登录时清空。 */
    private val _loginError = MutableStateFlow<Failure?>(null)
    val loginError: StateFlow<Failure?> = _loginError.asStateFlow()
    private val initializationMutex = Mutex()
    private val loginMutex = Mutex()

    init {
        scope.launch {
            tokenStore.sessionFlow.catch {
                setState(SessionState.InitializationError)
            }.collect { session ->
                // 避免订阅的初始 null 抢先把 Splash 误判为游客。
                if (_sessionState.value != SessionState.Initializing && !_loginProcessing.value) {
                    // 这一拍已被后一次写入盖掉时 commit 会抛（取消的子类），不接住收集器就悄悄停了：丢掉，下一拍照常同步
                    outcomeOf { tokenStore.commitForSession(session) { setState(session.toState()) } }
                }
            }
        }
    }

    suspend fun ensureInitialized() = initializationMutex.withLock {
        if (_sessionState.value != SessionState.Initializing &&
            _sessionState.value !is SessionState.InitializationError) return@withLock
        val initialized = outcomeOf {
            val session = tokenStore.session()
            // 有令牌却没有账号信息：上次登录在途中被打断（进程被杀）。按未登录处理并清掉残留令牌，
            // 否则每次启动都卡在「登录信息不完整」，重试也走不出去。
            if (session.hasToken && !session.authenticated) {
                tokenStore.clear(session)
                setState(SessionState.Unauthenticated)
                return@withLock
            }
            tokenStore.commitForSession(session) { setState(session.toState()) }
        }
        // 换了号：登录 / 登出已抢先完成，下次调用可以读取新的本地状态。
        if (initialized is Outcome.Failed && initialized.failure != Failure.SessionChanged) setState(SessionState.InitializationError)
    }

    /** 后台读取的操作归属：恢复本机会话后捕获一次，游客也是明确的会话；读不出来是本机存储失败。 */
    suspend fun currentSession(): Outcome<AccountSession> = storageOutcomeOf {
        ensureInitialized()
        tokenStore.session()
    }

    /** 只提交本机结果：归属校验与提交共用会话写锁，不在这里等待网络。 */
    suspend fun <T> withSession(session: AccountSession, block: suspend () -> T): T =
        tokenStore.commitForSession(session, block)

    /** 只有令牌与账号信息都齐才算登录；登录途中（令牌已写、账号信息未到）不算。 */
    private fun AccountSession.toState(): SessionState =
        if (authenticated) SessionState.Authenticated(SignedInAccount(userId!!, username!!)) else SessionState.Unauthenticated

    private fun setState(state: SessionState) {
        _sessionState.value = state
        _isLoggedIn.value = state is SessionState.Authenticated
    }

    /** 浏览器 OAuth 登录的授权地址；state 存下来，回调时核对（[onCallback]）。 */
    suspend fun buildAuthorizeUrl(): String {
        val state = UUID.randomUUID().toString()
        tokenStore.saveOAuthState(state)
        return authApi.authorizeUrl(state)
    }

    /**
     * 账号密码登录的一张新验证码。[attempt] 是这张图要接着用的那次登录；为 null 时先打开网站登录页，开始新的一次
     * （头一回，或上次没登录上：网站要的是新的一次）。
     */
    suspend fun passwordCaptcha(attempt: LoginAttempt?): Outcome<PasswordCaptcha> = outcomeOf {
        val current = attempt ?: LoginAttempt(webLoginApi.start())
        PasswordCaptcha(current, webLoginApi.captcha(current.web))
    }

    /**
     * 账号密码登录：提交网站的登录表单，再用登录后的网页会话替用户授权本应用、换令牌。
     * 成功后令牌与网页会话（Cookie）都存下，结果与失败原因同其他登录方式（[isLoggedIn] / [loginError]）。
     * 授权在应用内直接完成，不经浏览器回调，所以 state 不必存下核对。
     */
    suspend fun loginWithPassword(attempt: LoginAttempt, email: String, password: String, captcha: String): Boolean =
        loginMutex.withLock {
            login(withWeb = attempt.web) {
                webLoginApi.signIn(attempt.web, email, password, captcha)
                val code = webLoginApi.authorize(attempt.web, authApi.authorizeUrl(UUID.randomUUID().toString()))
                val before = tokenStore.session()
                val token = authApi.exchangeCode(code)
                tokenStore.requireCurrent(before)
                tokenStore.beginSession(token.accessToken, token.refreshToken)
            }
        }

    /**
     * 浏览器 OAuth 回调交到这里：在 App 级作用域里换令牌。回调到达的时刻正是旋转、切深浅色最常见的时候，
     * 界面重建不会打断这次登录；结果照常由 [sessionState] / [loginError] 给出。本机写入（核对并消耗 state）抛了也只记为登录失败，
     * 不冲出 [scope]（它没有异常处理器）。
     */
    fun handleCallback(code: String, state: String) {
        scope.launch {
            outcomeOf { onCallback(code, state) }.failureOrNull()?.let { _loginError.value = it }
        }
    }

    internal suspend fun onCallback(code: String, state: String): Boolean {
        return loginMutex.withLock {
            // 核对 state 与换令牌同在登录权里：等着的回调核对时，前一次登录已经结束、作废了它之前和途中发起的授权（见 [login]）。
            if (!tokenStore.consumeOAuthState(state)) {
                // 对不上：伪造的、空的，或是已被一次登录作废的授权。回调不属于当前登录流程，账号并没有变。
                // 已登录时没有人在等这次回调（登录页只对游客开着），不提示：否则它留到下次打开登录页才冒出来。
                if (!_isLoggedIn.value) _loginError.value = Failure.Rejected(RejectReason.LOGIN_CALLBACK_MISMATCH)
                return@withLock false
            }
            login {
                val before = tokenStore.session()
                val token = authApi.exchangeCode(code)
                tokenStore.requireCurrent(before)
                tokenStore.beginSession(token.accessToken, token.refreshToken)
            }
        }
    }

    suspend fun loginWithToken(token: String): Boolean = loginMutex.withLock {
        // 空令牌在登录页就拦下了（提示「请输入 Access Token」），这里只是护栏
        if (token.isBlank()) return@withLock false
        login { tokenStore.beginSession(token.trim()) }
    }

    /**
     * [withWeb]：同时拿网页会话的那次网页登录。它的 Cookie 在 [begin] 提交登录表单之后才齐，所以账号信息到齐后才从它取出、
     * 随会话存下。
     *
     * 每种登录（账号密码、浏览器回调、令牌）都经过这里，且都在 [loginMutex] 里：这次尝试结束时（成败、取消都算）作废还没回来的
     * 浏览器授权——它们发起在这次尝试之前或途中，迟到的回调不能再覆盖这次的结果。
     */
    private suspend fun login(withWeb: WebLoginAttempt? = null, begin: suspend () -> AccountSession): Boolean {
        _loginProcessing.value = true
        _loginError.value = null
        var pending: AccountSession? = null
        var outcome: Outcome<Unit>? = null
        try {
            outcome = outcomeOf {
                val session = begin().also { pending = it }
                val me = userApi.getMe(tokenStore.tokenFor(session))
                val completed = tokenStore.completeSession(session, me.id, me.username).also { pending = it }
                if (withWeb != null) webSessions.save(completed, withWeb.session)
                tokenStore.commitForSession(completed) { setState(completed.toState()) }
            }
            (outcome as? Outcome.Failed)?.let { _loginError.value = it.failure }
            return outcome is Outcome.Success
        } finally {
            // 没登成就清掉写了一半的令牌：失败时，以及途中被取消时（outcome 还是 null）。换了号时令牌已归新的那次登录，不动。
            // 取消时协程已停，清理放在 NonCancellable 里才做得完。
            val failure = (outcome as? Outcome.Failed)?.failure
            val cleaned = try {
                withContext(NonCancellable) {
                    storageOutcomeOf {
                        if (outcome == null || (failure != null && failure != Failure.SessionChanged)) {
                            pending?.let { tokenStore.clear(it) }
                        }
                    }
                }
            } finally {
                _loginProcessing.value = false
            }
            val synced = outcomeOf {
                // 本机一次写入；写不进去和下面的同步一样按本地存储出错处理。取消时也要做完，否则旧授权还能回来。
                withContext(NonCancellable) { tokenStore.clearOAuthState() }
                val current = tokenStore.session()
                tokenStore.commitForSession(current) { setState(current.toState()) }
            }
            // 换了号：随后的 sessionFlow 负责同步最新身份。
            if (cleaned is Outcome.Failed) {
                _loginError.value = cleaned.failure
                setState(SessionState.InitializationError)
            } else if (synced is Outcome.Failed && synced.failure != Failure.SessionChanged) {
                setState(SessionState.InitializationError)
            }
        }
    }

    suspend fun logout() {
        tokenStore.clear()
        // 收藏按 accountId 隔离，登出不删除任何账户的持久缓存。
        setState(SessionState.Unauthenticated)
        _loginError.value = null
    }
}
