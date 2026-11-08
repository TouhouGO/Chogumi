package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.SharedFetch
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.network.api.UserApi
import io.github.touhougo.chogumi.core.network.model.v0.UserDto
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset

/** 个人资料与网页时区共用在途的 me；网页时区按登录会话只决定一次。 */
class AccountMe(private val api: UserApi, private val tokens: TokenStore) {
    private val fetches = SharedFetch<AccountSession, UserDto>()
    private val zones = SharedFetch<AccountSession, ZoneId>()
    private var cached: Pair<AccountSession, ZoneId>? = null

    suspend fun get(session: AccountSession): UserDto = fetches.fetch(session) {
        val user = tokens.withSessionToken(session) { _, token -> api.getMe(token) }
        remember(session, zoneOf(user.timeOffset))
        user
    }

    suspend fun webZone(session: AccountSession): ZoneId {
        tokens.requireCurrent(session)
        synchronized(this) { cached?.takeIf { it.first == session }?.second }?.let { return it }
        return zones.fetch(session) {
            synchronized(this) { cached?.takeIf { it.first == session }?.second } ?: run {
                val zone = outcomeOf { zoneOf(get(session).timeOffset) }.valueOrNull() ?: DEFAULT_WEB_ZONE
                remember(session, zone)
            }
        }
    }

    private suspend fun remember(session: AccountSession, zone: ZoneId): ZoneId =
        tokens.commitForSession(session) {
            synchronized(this) {
                if (cached?.first != session) cached = session to zone
                cached!!.second
            }
        }
}

/** API 合同规定 offset 为整小时；非法值与缺失按站点默认解释。 */
private fun zoneOf(hours: Int?): ZoneId = try {
    hours?.let(ZoneOffset::ofHours) ?: DEFAULT_WEB_ZONE
} catch (e: DateTimeException) {
    DEFAULT_WEB_ZONE
}

private val DEFAULT_WEB_ZONE = ZoneOffset.ofHours(8)
