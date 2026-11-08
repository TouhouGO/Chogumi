package io.github.touhougo.chogumi.core.data.token

import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 请求的会话安全模板（读写共用）：
 * 1. 取出捕获时刻的会话 [AccountSession] 与对应凭据（未登录时 token 为 null）；
 * 2. 将 `(session, token)` 交给调用方发起请求并处理业务逻辑（如 mergeP1Interests）；
 * 3. 结束后立即校验 [TokenStore.requireCurrent]：执行期间换号或登出时，成功和失败都换成 [SessionChangedException]，
 *    旧账号的结果不落地，旧账号的失败也不提示到新账号上。普通协程取消原样传播。
 */
suspend inline fun <T> TokenStore.withSessionToken(
    knownSession: AccountSession? = null,
    crossinline block: suspend (session: AccountSession, token: String?) -> T,
): T {
    val session = knownSession ?: session()
    val token = if (session.hasToken) tokenFor(session) else null
    val result = try {
        block(session, token)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        requireCurrent(session)
        throw e
    }
    requireCurrent(session)
    return result
}

/** 必须登录的写操作：API、网页补偿与确认回读固定在操作起点的同一个会话（[withSessionToken]），过期结果统一丢弃。 */
internal suspend inline fun <T> TokenStore.authenticatedOutcome(
    crossinline block: suspend (session: AccountSession, token: String) -> Outcome<T>,
): Outcome<T> = authenticatedOutcomeIn(outcomeOf { requireSession() }, block)

/**
 * 排队等 [queue] 的必须登录的写操作：账号在排队之前、动作发生的那一刻定下；排到时已登出或换号就以
 * [io.github.touhougo.chogumi.core.foundation.Failure.SessionChanged] 结束，不写进排到时的那个账号。其余同 [authenticatedOutcome]。
 */
internal suspend inline fun <T> TokenStore.queuedAuthenticatedOutcome(
    queue: Mutex,
    crossinline block: suspend (session: AccountSession, token: String) -> Outcome<T>,
): Outcome<T> {
    val session = outcomeOf { requireSession() }
    return queue.withLock { authenticatedOutcomeIn(session, block) }
}

/** [authenticatedOutcome] 与 [queuedAuthenticatedOutcome] 共用：在 [session] 上执行 [block]，没取到会话就是那次的失败。 */
internal suspend inline fun <T> TokenStore.authenticatedOutcomeIn(
    session: Outcome<AccountSession>,
    crossinline block: suspend (session: AccountSession, token: String) -> Outcome<T>,
): Outcome<T> {
    val pinned = when (session) {
        is Outcome.Success -> session.value
        is Outcome.Failed -> return session
    }
    return when (val result = outcomeOf { withSessionToken(pinned) { current, token -> block(current, requireNotNull(token)) } }) {
        is Outcome.Success -> result.value
        is Outcome.Failed -> result
    }
}
