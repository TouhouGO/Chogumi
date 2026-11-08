package io.github.touhougo.chogumi.feature.settings

import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.data.repository.NsfwPreference

/**
 * NSFW 开关在设置页的**四态**。
 *
 * 真源是 bgm.tv 账号设置，本地只有缓存，所以界面必须区分：
 * 「没登录」「登录了但拿不准」「登录了但账号无权改」「登录了可以改」。
 * 前三者一律禁用开关 —— 拿不准时**不能**默认成"关"让用户以为关掉了，
 * 也不能默认成"开"（服务端不认，徒增困惑）。
 */
sealed interface NsfwSwitchState {

    /** 未登录：没有账号可读，开关禁用。 */
    data object LoggedOut : NsfwSwitchState

    /**
     * 已登录但缓存为空（首次启动尚未拉到、或上次请求失败）。
     *
     * [loading] 区分"正在加载"与"加载失败"：[failureReason] 非空即失败，可重试。
     */
    data class Unavailable(
        val loading: Boolean,
        val failureReason: Failure? = null,
    ) : NsfwSwitchState

    /** 已登录但账号当前无权修改（注册未满 60 天等）：显示真实值但禁用。 */
    data class Locked(val preference: NsfwPreference) : NsfwSwitchState

    /** 已登录且可修改：开关可用，切换即 PATCH 服务端。 */
    data class Editable(
        val preference: NsfwPreference,
        val pending: Boolean = false,
    ) : NsfwSwitchState

    /** 开关的显示位置：拿不准时停在"关"（不臆测服务端值）。 */
    val checked: Boolean
        get() = when (this) {
            is Locked -> preference.allowNsfw
            is Editable -> preference.allowNsfw
            else -> false
        }
}
