package io.github.touhougo.chogumi.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.FailureException
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.Outcome
import io.github.touhougo.chogumi.core.foundation.RejectReason
import io.github.touhougo.chogumi.core.data.token.AccountSession
import io.github.touhougo.chogumi.core.data.token.SessionChangedException
import io.github.touhougo.chogumi.core.data.token.TokenStore
import io.github.touhougo.chogumi.core.data.token.withSessionToken
import io.github.touhougo.chogumi.core.data.util.failuresAsLoad
import io.github.touhougo.chogumi.core.data.util.onWebIfUnconfirmed
import io.github.touhougo.chogumi.core.data.util.WriteFence
import io.github.touhougo.chogumi.core.data.util.outcomeOf
import io.github.touhougo.chogumi.core.data.web.WebSessionRepository
import io.github.touhougo.chogumi.core.network.api.PrivacyApi
import io.github.touhougo.chogumi.core.network.model.p1.PrivacyDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 服务端隐私偏好里与 NSFW 显示相关的三项。
 *
 * [allowNsfw] 是**服务端算出来的最终结论**（开关打开 && 注册满 60 天 && 未封禁），
 * 客户端不得自行推导 —— 60 天这条客户端算不出来，只能信服务端。
 */
data class NsfwPreference(
    val allowNsfw: Boolean,
    val showNsfwSubject: Boolean,
    val canSetNsfwSubject: Boolean,
)

/**
 * NSFW 显示开关的**缓存层**：真源是 bgm.tv 账号设置（`p1/privacy`），本地只存按账号隔离的副本。
 *
 * ## 为什么不是本地开关
 *
 * 服务端自己会按账号判定 `allowNsfw` 并剔除 NSFW 条目，本地开关**绕不过**它。
 * 本地开关若与账号设置不一致只会造成"开了也看不到"的困惑，故此处只做
 * 「服务端偏好的缓存」：不产生、不臆测任何值。
 *
 * ## 分区隔离（禁止跨账号读到旧值）
 *
 * 缓存键带 `userId` 后缀（`nsfw_allow_<userId>`），与 `UserCollectionDao` 按
 * `accountId` 隔离同理。**未登录（userId 为 null）不读任何分区**，
 * [showNsfw] 恒为 false —— 这就是「未登录不显示 NSFW」的落点。
 * 换号后新账号读不到旧账号的键，天然隔离；登出**不删**任何账号的持久缓存
 * （与 `AuthRepository.logout` 注释的既有约定一致）。
 *
 * ## 在途状态
 *
 * [refresh] / [setShowNsfw] 都是「发请求 → 校验会话未变 → 写缓存」，
 * 会话在途中变化（换号/登出）时结果丢弃，由 `withSessionToken` 同款的
 * `requireCurrent` 保证，不把旧账号的响应写进新账号分区。
 */
class NsfwPreferenceRepository(
    private val dataStore: DataStore<Preferences>,
    private val privacyApi: PrivacyApi,
    private val tokenStore: TokenStore,
    private val webSessions: WebSessionRepository,
) {
    /** 写之前发出的读取（登录时的同步）晚到时不盖掉开关写下的值（[WriteFence]）。 */
    private val fence = WriteFence<PrivacyDto>()

    /** 不含凭据的会话身份，设置页用它归属读取与写入；读取失败沿统一加载失败通道。 */
    val sessions: Flow<Load<AccountSession>> = tokenStore.sessionFlow
        .map<AccountSession, Load<AccountSession>> { Load.Ready(it) }
        .failuresAsLoad()

    /**
     * 当前会话的开关值，供唯一过滤组件 [NsfwFilter] 在调用时读取。
     *
     * 未登录恒 false；已登录但缓存为空（首次启动、上次请求失败）也恒 false，
     * 即「拿不准时不显示 NSFW」——失败开放会让 NSFW 意外露出，不可接受。
     */
    suspend fun showNsfw(): Boolean = cached()?.allowNsfw ?: false

    /** 指定会话的缓存偏好；未登录或该账号尚无缓存时为 null，读到后再次检查身份。 */
    suspend fun cached(session: AccountSession? = null): NsfwPreference? {
        val owner = session ?: tokenStore.session()
        val userId = owner.userId ?: return null
        tokenStore.requireCurrent(owner)
        val prefs = dataStore.data.first()
        tokenStore.requireCurrent(owner)
        val allow = prefs[PreferenceKeys.nsfwAllowKey(userId)] ?: return null
        return NsfwPreference(
            allowNsfw = allow,
            showNsfwSubject = prefs[PreferenceKeys.nsfwShowKey(userId)] ?: false,
            canSetNsfwSubject = prefs[PreferenceKeys.nsfwCanSetKey(userId)] ?: false,
        )
    }

    /**
     * 从服务端拉取当前账号的偏好并写入缓存。
     *
     * 未登录返回 [Failure.NotLoggedIn]（调用方据此显示"登录后可在 bgm.tv 账号中开启"，
     * 不当作错误弹窗）；已登录时网络失败返回错误，**不清空**已有缓存
     * （宁可显示旧值也不退回 false 把已开启的 NSFW 突然藏起来）。
     */
    suspend fun refresh(session: AccountSession? = null): Outcome<NsfwPreference> =
        request(session) { readSession, token, store ->
            fence.read(readSession, fetch = { privacyApi.getPrivacy(token) }, publish = store)
        }

    /**
     * 切换 `show_nsfw_subject`，成功后以**响应体**写缓存。
     *
     * 服务端 403 表示账号当前无权修改（[RejectReason.NSFW_ACCOUNT_TOO_NEW]，"注册满 60 天后可开启"），同步把
     * `canSetNsfwSubject` 置 false（服务端是权威），UI 据此切到禁用态。
     *
     * 接口在服务端恒 5xx（guide §1.2）：改用网页会话提交个人设置页的表单（[onWebIfUnconfirmed]），做完回读隐私设置，
     * `showNsfwSubject` 对上才算做成，并以回读的结果写缓存。接口超时也这样补：表单是整张提交、把这一项设成目标值，
     * 服务端其实已经改了也只是再设一遍。
     */
    suspend fun setShowNsfw(value: Boolean, session: AccountSession? = null): Outcome<NsfwPreference> =
        request(
            session,
            onForbidden = { userId ->
                // 服务端明确拒绝：记下"不可改"，但保留 showNsfwSubject/allowNsfw 旧值。
                dataStore.edit { it[PreferenceKeys.nsfwCanSetKey(userId)] = false }
            },
        ) { session, token, store ->
            val written = outcomeOf { privacyApi.patchShowNsfw(token, value) }
                .onWebIfUnconfirmed(RejectReason.PRIVACY_SETTING_UNAVAILABLE) {
                    webSessions.setShowNsfw(session, value)
                    val readBack = tokenStore.withSessionToken(session) { _, readToken -> privacyApi.getPrivacy(readToken) }
                    if (readBack.preferences.showNsfwSubject != value) {
                        throw FailureException(Failure.Rejected(RejectReason.PRIVACY_SETTING_UNAVAILABLE))
                    }
                    readBack
                }
            when (written) {
                is Outcome.Success -> written.value.also { fence.write(session) { store(it) } }
                is Outcome.Failed -> throw FailureException(written.failure)
            }
        }

    /**
     * 会话绑定模板：取当前会话 → 连同凭据发请求 → 校验会话未变 → 写缓存。[block] 拿到写缓存的那一步（`store`），
     * 读与写各自决定何时落缓存：读经 [WriteFence.read]，写做成后经 [WriteFence.write]。
     *
     * ## 绝不向外抛非取消异常（观察者存活的前提）
     *
     * 本方法被 [NsfwPreferenceSynchronizer] 的 collect 长期调用。只要向外抛一次异常，
     * 那个 collect 协程就结束了，此后换号/重登都不再刷新 —— 且因为挂在脱离页面的
     * 作用域上，异常还会走默认处理器崩掉 App。故此处把**一切**失败都收敛成
     * 失败结果，只放行真正的协程取消（[CancellationException]）：
     *
     * - 会话读取失败（DataStore 读异常）→ 失败；
     * - 会话在途中变化（[SessionChangedException]）→ [Failure.SessionChanged]。
     *   **注意它是 CancellationException 的子类**，但语义是"换号了、结果作废"，
     *   不是"协程被取消"。若照直 `throw`，用户在刷新途中换号就会打死观察者，
     *   正是"换号后不再刷新"的成因；[outcomeOf] 先于取消分支认出它；
     * - 网络/解析/写缓存失败 → 失败（服务端拒绝改 NSFW 偏好的两种原因由 PrivacyApi 直接给出）。
     *
     * 失败只写错误态、不落地任何值，避免把 A 账号的响应写进 B 账号分区。
     */
    private suspend fun request(
        knownSession: AccountSession?,
        onForbidden: suspend (userId: Int) -> Unit = {},
        block: suspend (session: AccountSession, token: String?, store: suspend (PrivacyDto) -> Unit) -> PrivacyDto,
    ): Outcome<NsfwPreference> {
        // 会话读取本身也会失败（DataStore IO 异常），故一并纳入兜底，不裸露在外。
        val session = when (val read = outcomeOf { knownSession ?: tokenStore.session() }) {
            is Outcome.Success -> read.value
            is Outcome.Failed -> return read
        }
        val userId = session.userId
        if (!session.authenticated || userId == null) {
            return Outcome.Failed(Failure.NotLoggedIn)
        }

        return outcomeOf {
            tokenStore.withSessionToken(session) { _, token ->
                val store: suspend (PrivacyDto) -> Unit = { dto ->
                    val preference = dto.toPreference()
                    tokenStore.commitForSession(session) {
                        dataStore.edit {
                            it[PreferenceKeys.nsfwAllowKey(userId)] = preference.allowNsfw
                            it[PreferenceKeys.nsfwShowKey(userId)] = preference.showNsfwSubject
                            it[PreferenceKeys.nsfwCanSetKey(userId)] = preference.canSetNsfwSubject
                        }
                    }
                }
                val written = outcomeOf { block(session, token, store) }
                // 403 才记下「不可改」；网络或网页失败不改变账号权限。存储失败也在同一 Outcome 边界内。
                if (written is Outcome.Failed && written.failure == Failure.Rejected(RejectReason.NSFW_ACCOUNT_TOO_NEW)) {
                    tokenStore.commitForSession(session) { onForbidden(userId) }
                }
                when (written) {
                    is Outcome.Success -> written.value.toPreference()
                    is Outcome.Failed -> throw FailureException(written.failure)
                }
            }
        }
    }

    private fun PrivacyDto.toPreference() = NsfwPreference(
        allowNsfw = preferences.allowNsfw,
        showNsfwSubject = preferences.showNsfwSubject,
        canSetNsfwSubject = preferences.canSetNsfwSubject,
    )
}
