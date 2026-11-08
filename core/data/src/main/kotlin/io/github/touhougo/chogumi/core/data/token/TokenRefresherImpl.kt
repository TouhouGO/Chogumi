package io.github.touhougo.chogumi.core.data.token

import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.TokenRefresher
import io.github.touhougo.chogumi.core.network.api.AuthApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 401 时的刷新。
 *
 * - 只有服务端明确拒绝这张刷新令牌（OAuth 的 400 / 401，如 `invalid_grant`）才清空会话；断网、超时、5xx、限流都是暂时的，
 *   保留登录，挨 401 的那个请求照常失败。
 * - 并发的 401 排队：轮到时当前令牌已经不是挨拒的那张（前一个刚刷新过，或已换号），就不再发刷新请求。
 * - 会话与令牌出自同一个快照（[TokenStore.refreshGrant]），途中换号时刷新结果不写给新账号（[TokenStore.refreshSession]）。
 */
class TokenRefresherImpl(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
) : TokenRefresher {

    private val mutex = Mutex()

    override suspend fun refresh(rejectedAccessToken: String?): Boolean = mutex.withLock {
        // 请求没带令牌，挨 401 说明不了令牌过期
        if (rejectedAccessToken == null) return@withLock false
        val grant = tokenStore.refreshGrant() ?: return@withLock false
        if (grant.accessToken != rejectedAccessToken) return@withLock grant.accessToken != null
        when (val result = outcomeOf { authApi.refresh(grant.refreshToken) }) {
            is Outcome.Success -> tokenStore.refreshSession(grant.session, result.value.accessToken, result.value.refreshToken)
            is Outcome.Failed -> {
                if (result.failure.rejectsRefreshToken()) tokenStore.clear(grant.session)
                false
            }
        }
    }
}

/** 服务端明确不认这张刷新令牌：OAuth 令牌端点回 400 / 401。 */
private fun Failure.rejectsRefreshToken(): Boolean = this is Failure.Http && status in 400..401
