package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.db.mapper.toBo
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.authenticatedOutcome
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.ObservedPreferences
import io.github.touhougo.chogumi.core.data.util.WriteFence
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.foundation.AppClock
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.model.Friends
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.network.api.UserApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** 自己的好友列表一页的上限（服务端 `GET /p1/users/{username}/friends` 的 limit 最大 100）。 */
private const val FRIENDS_PAGE_LIMIT = 100

/**
 * 当前账号关注的人（[Friends]）：名单以 bgm.tv 为准（自己的 `GET /p1/users/{username}/friends`，逐页读全），本机按账号缓存一份
 * （偏好 DataStore，同绝交名单的做法），在网页端加的好友过一阵子也会同步过来。
 *
 * - 读：[changes] 跟着登录态与本机名单变；订阅时没有缓存或缓存超过 [STALE_MILLIS]，在后台重读一次，读不到就先按旧名单（或谁也没关注）；
 * - 写：加好友、取消好友（`UserRepository`）做成后经 [confirmWrite] 重读名单，名字旁的小牌、主页的「已是好友」当场跟上；
 * - 写信选收件人要好友本人（头像、名字）：[users]，与 [refresh] 是同一次读取，读到的名单同样记下。
 */
class FriendsRepository(
    dataStore: DataStore<Preferences>,
    private val userApi: UserApi,
    private val tokenStore: TokenStore,
    private val clock: AppClock,
    private val scope: CoroutineScope,
) {
    private val prefs = ObservedPreferences(dataStore)

    /** 同一账号同时只读一次；写之前发出的名单读取晚到时不盖掉写之后读到的名单（[WriteFence]）。 */
    private val fence = WriteFence<List<UserBo>>()

    /**
     * 当前账号的好友：游客与还没读到名单时是 [Friends.NONE]，之后每次本机名单变了再出一拍。读不出本机存储时这个账号不再出拍，
     * 换号后照常。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun changes(): Flow<Friends> = tokenStore.sessionFlow.flatMapLatest { session ->
        val userId = session.userId?.takeIf { session.authenticated } ?: return@flatMapLatest flowOf(Friends.NONE)
        prefs.data
            .map { stored -> Load.Ready(stored[PreferenceKeys.friendsKey(userId)]?.let(::decode) ?: Friends.NONE) as Load<Friends> }
            .failuresAsLoad()
            .mapNotNull { it.valueOrNull() }
            .distinctUntilChanged()
            .onStart { scope.launch { outcomeOf { if (isStale(userId)) refresh(session) } } }
    }

    /** 重读 [session] 的完整好友名单并存下；同一账号同时只读一次。换号、登出时结果作废，不写进缓存。 */
    private suspend fun refresh(session: AccountSession): Friends = Friends(fetch(session).mapTo(HashSet()) { it.id })

    /**
     * 加好友、取消好友在服务端做成之后：写之前发出、还在路上的读取作废（晚到也不落缓存、不交出），重读一次名单并记下。
     * 回读到的名单就是写之后的服务端名单（取消好友用它确认对方已不在）。
     */
    suspend fun confirmWrite(session: AccountSession): Friends {
        fence.write(session)
        return refresh(session)
    }

    /** 当前账号关注的全部的人，按服务端的顺序（写信时选收件人）；读到的名单同时记下（同 [refresh]）。 */
    suspend fun users(): Outcome<List<UserBo>> = tokenStore.authenticatedOutcome { session, _ -> outcomeOf { fetch(session) } }

    private suspend fun fetch(session: AccountSession): List<UserBo> {
        val userId = requireNotNull(session.userId)
        return fence.read(
            session = session,
            fetch = {
                tokenStore.withSessionToken(session) { _, token ->
                    fetchAllPages(FRIENDS_PAGE_LIMIT) { offset, limit ->
                        userApi.getFriends(requireNotNull(session.username), limit, offset, token).toSlice()
                    }.map { it.toBo() }
                }
            },
            publish = { users ->
                prefs.edit {
                    it[PreferenceKeys.friendsKey(userId)] = users.joinToString(SEPARATOR) { user -> user.id.toString() }
                    it[PreferenceKeys.friendsFetchedAtKey(userId)] = clock.nowMillis()
                }
            },
        )
    }

    private suspend fun isStale(userId: Int): Boolean {
        val fetchedAt = prefs.data.first()[PreferenceKeys.friendsFetchedAtKey(userId)] ?: return true
        return clock.nowMillis() - fetchedAt > STALE_MILLIS
    }

    private fun decode(raw: String): Friends = Friends(raw.split(SEPARATOR).mapNotNullTo(HashSet()) { it.toIntOrNull() })

    private companion object {
        const val SEPARATOR = ","

        /** 缓存过了这么久，下次订阅时在后台重读：在网页端新加的好友，过一阵子在 App 里也带上小牌。 */
        const val STALE_MILLIS = 60 * 60 * 1000L
    }
}
