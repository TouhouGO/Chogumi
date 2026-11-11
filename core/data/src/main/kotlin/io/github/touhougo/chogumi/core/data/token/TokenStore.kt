package io.github.touhougo.chogumi.core.data.token

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.util.recoverStorageReads
import io.github.touhougo.chogumi.core.data.util.ObservedPreferences
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.network.TokenProvider
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 不包含凭据的账户身份；generation 在登出/登录尝试时递增，令牌刷新不换分区。 */
data class AccountSession(
    val generation: Long,
    val userId: Int?,
    val username: String?,
    val hasToken: Boolean,
) {
    val authenticated: Boolean get() = hasToken && userId != null && !username.isNullOrBlank()

    /**
     * 按谁的视角缓存服务端内容（NSFW 等可见性随账号变）：已登录是账号 id，游客是 [ANONYMOUS_VIEWER]。
     * 带视角的缓存键都取这一个值。
     */
    val viewerId: Int get() = userId?.takeIf { authenticated } ?: ANONYMOUS_VIEWER

    companion object {
        /** 游客的查看者 id：真实账号 id 从 1 起。 */
        const val ANONYMOUS_VIEWER = 0
    }
}

/**
 * 本机数据归谁：草稿、屏蔽名单这类只在本机的内容按查看者（[AccountSession.viewerId]）分开存取，换号后看不到上一个账号的。
 * 只此一个来源；仓库依赖它而不是整个 [TokenStore]。
 */
interface ViewerSource {
    /** 当前查看者，换号、登出时发新值。 */
    val viewerIds: Flow<Int>
}

/** 存着的网页会话：Cookie 是密文（加解密见 `CookieCipher`）。 */
data class StoredWebSession(val sealedCookie: String)

/** 刷新令牌要用的一组凭据，与 [session] 出自同一次读取。 */
internal data class RefreshGrant(val session: AccountSession, val accessToken: String?, val refreshToken: String)

class SessionChangedException : CancellationException("账户已切换，已丢弃旧请求")

/**
 * token 持久化与账户边界；独占 chogumi_tokens DataStore 文件，与用户偏好物理隔离并排除云备份。
 * 本地提交和身份切换共用互斥锁，检查与写库不可分割。
 */
class TokenStore(dataStore: DataStore<Preferences>) : TokenProvider, ViewerSource {
    private val store = ObservedPreferences(dataStore)
    private val accessTokenKey = TokenStoreKeys.ACCESS_TOKEN
    private val refreshTokenKey = TokenStoreKeys.REFRESH_TOKEN
    private val userIdKey = TokenStoreKeys.USER_ID
    private val usernameKey = TokenStoreKeys.USERNAME
    private val oauthStateKey = TokenStoreKeys.OAUTH_STATE
    private val sessionGenerationKey = TokenStoreKeys.SESSION_GENERATION
    private val webCookieKey = TokenStoreKeys.WEB_COOKIE
    private val sessionMutex = Mutex()
    private val currentSession = MutableStateFlow<AccountSession?>(null)

    private val accessTokenFlow: Flow<String?> = store.data.map { it[accessTokenKey] }

    /** 本地恢复与会话写入共用同一身份真源，动作发起不等待订阅者的下一拍。 */
    val sessionFlow: Flow<AccountSession> = flow {
        session()
        emitAll(currentSession.filterNotNull())
    }
    /** 应用级投影的会话来源：冷读失败时退避再读，不伪造游客，恢复后继续接收换号。 */
    internal val recoveringSessions: Flow<AccountSession> = sessionFlow.recoverStorageReads()

    override val viewerIds: Flow<Int> = sessionFlow.map { it.viewerId }.distinctUntilChanged()

    private fun Preferences.toWebSession(): StoredWebSession? = this[webCookieKey]?.let(::StoredWebSession)

    private fun Preferences.toSession() = AccountSession(
        generation = this[sessionGenerationKey] ?: 0,
        userId = this[userIdKey],
        username = this[usernameKey],
        hasToken = !this[accessTokenKey].isNullOrBlank(),
    )

    override suspend fun accessToken(): String? = accessTokenFlow.first()
    suspend fun session(): AccountSession = currentSession.value ?: sessionMutex.withLock {
        currentSession.value ?: store.data.first().toSession().also { currentSession.value = it }
    }

    /** 已登录的会话；未登录抛 [FailureException]（[Failure.NotLoggedIn]）——「这事要登录」全仓只在这里判。 */
    suspend fun requireSession(): AccountSession = when (val result = authenticatedSession(session())) {
        is Outcome.Success -> result.value
        is Outcome.Failed -> throw FailureException(result.failure)
    }

    private fun authenticatedSession(session: AccountSession?): Outcome<AccountSession> = when {
        session == null -> Outcome.Failed(Failure.SessionChanged)
        !session.authenticated -> Outcome.Failed(Failure.NotLoggedIn)
        else -> Outcome.Success(session)
    }

    /** 同步捕获动作身份；本地会话尚未恢复时拒绝，不把动作延迟绑定到之后登录的账号。 */
    fun captureSession(): Outcome<AccountSession> = authenticatedSession(currentSession.value)

    /** 会话写入的持久快照与同步身份一起前进；取消不能留下已经落盘但尚未发布的身份。 */
    private suspend fun updateSession(transform: suspend (MutablePreferences) -> Unit): AccountSession =
        withContext(NonCancellable) {
            store.edit(transform).toSession().also { currentSession.value = it }
        }

    suspend fun isCurrent(expected: AccountSession): Boolean =
        (currentSession.value ?: store.data.first().toSession()) == expected
    suspend fun requireCurrent(expected: AccountSession) {
        if (!isCurrent(expected)) throw SessionChangedException()
    }

    /** 只包围本地提交，不能持锁等待网络（401 刷新可能触发登出）。 */
    suspend fun <T> commitForSession(expected: AccountSession, block: suspend () -> T): T =
        sessionMutex.withLock {
            requireCurrent(expected)
            block()
        }

    /**
     * 在同一快照内取得请求凭据，API 将此凭据固定到请求，避免调度间隙串号。
     *
     * 读的是 DataStore 的一份一致快照，与 [expected] 比较不需要 [sessionMutex]：会话的写入都是单次 `edit`，本身原子；
     * 拿锁只会让每个带令牌的请求排在持锁的大事务（全量同步、首页落库）后面。
     *
     * 不变量：`expected.authenticated == true` 时**必须**返回非空 token。
     * 已登录会话却拿不到 token 是自相矛盾的状态；若静默返回 null，
     * 请求会以"无 Authorization 头"发出，最终表现为无从解释的服务端 401
     * （"Unauthorized: need Login"）。所以这里显式抛错，让矛盾在源头暴露。
     */
    suspend fun tokenFor(expected: AccountSession): String? {
        val prefs = store.data.first()
        if (prefs.toSession() != expected) throw SessionChangedException()
        val token = prefs[accessTokenKey]
        if (expected.authenticated && token.isNullOrBlank()) {
            throw IllegalStateException("会话标记为已登录但缺少 access_token，疑似状态不一致")
        }
        return token
    }

    /** 必须登录的写请求的凭据：[expected] 没有令牌时是 [Failure.NotLoggedIn]（同 [requireSession]）。 */
    suspend fun requireTokenFor(expected: AccountSession): String =
        tokenFor(expected) ?: throw FailureException(Failure.NotLoggedIn)

    /** 与 [expected] 的身份在同一个快照里读取 Cookie，不能拿到另一账号的网页凭据。 */
    internal suspend fun webSessionFor(expected: AccountSession): StoredWebSession? {
        val prefs = store.data.first()
        if (prefs.toSession() != expected) throw SessionChangedException()
        return prefs.toWebSession()
    }

    suspend fun beginSession(token: String, refreshToken: String? = null): AccountSession =
        sessionMutex.withLock {
            updateSession {
                it[sessionGenerationKey] = (it[sessionGenerationKey] ?: 0) + 1
                it[accessTokenKey] = token
                it.remove(userIdKey)
                it.remove(usernameKey)
                it.remove(refreshTokenKey)
                it.removeWebSession()
                it.remove(TokenStoreKeys.NOTICE_ACTION_KEY)
                if (refreshToken != null) it[refreshTokenKey] = refreshToken
            }
        }

    suspend fun completeSession(expected: AccountSession, userId: Int, username: String): AccountSession =
        sessionMutex.withLock {
            requireCurrent(expected)
            updateSession {
                it[userIdKey] = userId
                it[usernameKey] = username
            }
        }

    /** 当前会话与它的访问令牌、刷新令牌（同一个快照，中间不会换号）；没有刷新令牌为 null。 */
    internal suspend fun refreshGrant(): RefreshGrant? {
        val prefs = store.data.first()
        val refreshToken = prefs[refreshTokenKey] ?: return null
        return RefreshGrant(prefs.toSession(), prefs[accessTokenKey], refreshToken)
    }

    /** 刷新成功只替换同一会话的凭据；旧刷新不得覆盖新账号。 */
    suspend fun refreshSession(expected: AccountSession, token: String, refreshToken: String): Boolean =
        sessionMutex.withLock {
            if (!isCurrent(expected)) return@withLock false
            store.edit {
                it[accessTokenKey] = token
                it[refreshTokenKey] = refreshToken
            }
            true
        }

    /** 给 [expected] 这个会话存上网页会话；会话已经换了就不存（抛 [SessionChangedException]）。 */
    suspend fun saveWebSession(expected: AccountSession, web: StoredWebSession) = commitForSession(expected) {
        store.edit {
            it[webCookieKey] = web.sealedCookie
        }
        Unit
    }

    /** 丢掉 [expected] 这个会话的网页会话（过期、解不开）；会话已经换了就什么也不做。 */
    suspend fun clearWebSession(expected: AccountSession) = sessionMutex.withLock {
        if (!isCurrent(expected)) return@withLock
        store.edit { it.removeWebSession() }
        Unit
    }

    private fun MutablePreferences.removeWebSession() {
        remove(webCookieKey)
    }

    /** 系统通知的动作凭据：绑定会话与提醒 id，进程退出后仍可验证；换号后作废。 */
    internal suspend fun noticeAction(session: AccountSession, id: Int): String = commitForSession(session) {
        val key = store.data.first()[TokenStoreKeys.NOTICE_ACTION_KEY] ?: UUID.randomUUID().toString().also { secret ->
            store.edit { it[TokenStoreKeys.NOTICE_ACTION_KEY] = secret }
        }
        noticeSignature(key, session, id)
    }

    /** 公开 Activity 的 extras 不可信；只接受本机为当前会话签发的通知动作，不从 extras 构造会话。 */
    internal suspend fun noticeSession(id: Int, proof: String?): AccountSession? {
        if (id <= 0 || proof == null) return null
        val session = session()
        return commitForSession(session) {
            if (!session.authenticated) return@commitForSession null
            val key = store.data.first()[TokenStoreKeys.NOTICE_ACTION_KEY] ?: return@commitForSession null
            session.takeIf { MessageDigest.isEqual(noticeSignature(key, session, id).toByteArray(), proof.toByteArray()) }
        }
    }

    suspend fun saveOAuthState(state: String) { store.edit { it[oauthStateKey] = state } }

    /** 作废还没回来的浏览器授权：之后带着它的回调一律对不上（[consumeOAuthState]）。 */
    suspend fun clearOAuthState() { store.edit { it.remove(oauthStateKey) } }

    /**
     * 回调带回的 [state] 与存着的一致才把它消费掉（一次授权只认一次回调）并返回 true。对不上时存着的不动：
     * 别的应用发来的伪造回调被拒，正在进行的那次登录照样能用真回调完成。
     */
    suspend fun consumeOAuthState(state: String): Boolean {
        var matched = false
        store.edit {
            if (state.isNotBlank() && it[oauthStateKey] == state) {
                it.remove(oauthStateKey)
                matched = true
            }
        }
        return matched
    }

    suspend fun clear(expected: AccountSession? = null) = sessionMutex.withLock {
        if (expected != null && !isCurrent(expected)) return@withLock
        updateSession {
            it[sessionGenerationKey] = (it[sessionGenerationKey] ?: 0) + 1
            it.remove(accessTokenKey)
            it.remove(refreshTokenKey)
            it.remove(userIdKey)
            it.remove(usernameKey)
            it.remove(oauthStateKey)
            it.removeWebSession()
            it.remove(TokenStoreKeys.NOTICE_ACTION_KEY)
        }
        Unit
    }
}

/** HMAC 只授权这一条已读动作；密钥与 OAuth 令牌分开，消息只含无凭据的会话身份。 */
private fun noticeSignature(key: String, session: AccountSession, id: Int): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
    return mac.doFinal("${session.generation}:${session.viewerId}:$id".toByteArray()).joinToString("") { "%02x".format(it) }
}
