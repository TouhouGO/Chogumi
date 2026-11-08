package io.github.touhougo.chogumi.core.foundation

/**
 * 一次失败的原因——**类型**，不是文字。
 *
 * 文案按当前语言由界面取（core:ui 的 `Failure.text()`，全仓唯一的映射）；异常 → 原因只在 core:network 的
 * `Throwable.toFailure()` 一处判定。数据层与 ViewModel 只传递它、按类型分支（例如「404 = 服务端确无此收藏」），
 * 从不拼接、解析或嗅探文字。
 */
sealed interface Failure {
    /** 连不上服务器：域名解析失败、连接被拒绝。 */
    data object Offline : Failure

    /** 连接或读写超时。 */
    data object Timeout : Failure

    /** 连上了，传输途中被断开（TLS 握手被关闭、EOF、连接被重置）。 */
    data object Interrupted : Failure

    /**
     * 服务端回了非 2xx。[oauth] 是错误体里能认出的 OAuth / p1 错误码（比状态码更具体，界面优先用它）。
     */
    data class Http(val status: Int, val oauth: OAuthError? = null) : Failure

    /** 本机存储写不进去：磁盘满、文件损坏。与网络无关，重试前先清出空间。 */
    data object Storage : Failure

    /** 响应读不懂：字段缺失、类型不符、取值超出约定。 */
    data object BadResponse : Failure

    /** 需要登录才能做。 */
    data object NotLoggedIn : Failure

    /** 操作途中账号变了（登出、换号）：结果没有应用到当前账号。 */
    data object SessionChanged : Failure

    /** 服务端或业务规则明确拒绝，原因见 [reason]。 */
    data class Rejected(val reason: RejectReason) : Failure

    /** 认不出的异常。原文可能含英文或内部 URL，一律不上屏。 */
    data object Unknown : Failure
}

/** 错误体里能认出的 OAuth / p1 错误码（`error` 字段，大小写不定）。 */
enum class OAuthError(val code: String) {
    INVALID_GRANT("invalid_grant"),
    INVALID_CLIENT("invalid_client"),
    INVALID_REQUEST("invalid_request"),
    UNAUTHORIZED("unauthorized"),
    ACCESS_DENIED("access_denied"),
    ;

    companion object {
        fun of(code: String?): OAuthError? = entries.find { it.code.equals(code, ignoreCase = true) }
    }
}

/** 业务规则拒绝的具体原因。 */
enum class RejectReason {
    /** bgm.tv 账号注册满 60 天才能开启 NSFW。 */
    NSFW_ACCOUNT_TOO_NEW,

    /** bgm.tv 的隐私设置接口暂时不可用（PATCH /p1/privacy 恒 500），只能去网页端改。 */
    PRIVACY_SETTING_UNAVAILABLE,

    /**
     * 取消收藏角色 / 人物没做成，只能去网页端取消：已收藏时 `DELETE /p1/collections/characters|persons/{id}` 恒 500
     * （带不带请求体都一样），v0 的 `DELETE /v0/characters|persons/{id}/collect` 没有实现（404），改用网页会话也没做成。
     */
    MONO_UNCOLLECT_UNAVAILABLE,

    /** 网页移除作品收藏后，回读仍有收藏记录。 */
    SUBJECT_UNCOLLECT_UNAVAILABLE,

    /** 解除好友没做成，只能去网页端解除：`DELETE /p1/friends/{username}` 恒 500，改用网页会话也没做成。 */
    UNFRIEND_UNAVAILABLE,

    /** 删除自己的动态没做成，只能去网页端删：`DELETE /p1/timeline/{id}` 在服务端 5xx，改用网页会话也没做成。 */
    TIMELINE_DELETE_UNAVAILABLE,

    /** 绝交、取消绝交没做成，只能去网页端的隐私设置：`PUT|DELETE /p1/blocklist/{username}` 恒 500，改用网页会话也没做成。 */
    BLOCK_UNAVAILABLE,

    /** 加入、退出小组没做成：开放接口没有这两件事，用网页会话做了，回读小组仍没变。 */
    GROUP_MEMBERSHIP_UNAVAILABLE,

    /** 短信没发出去：网页说收件人不存在。 */
    PM_RECEIVER_NOT_FOUND,

    /** 短信没发出去：网页停在写信页，给的原因认不出（或没给）。 */
    PM_NOT_SENT,

    /** 删除短信没做成：网页删除后重读会话，那封信还在。 */
    PM_DELETE_UNAVAILABLE,

    /** 这件事只有网页能做，要 bgm.tv 网页登录：还没有网页会话，或者网页那边已经登出、过期。 */
    WEB_LOGIN_REQUIRED,

    /** 账号密码登录：验证码填错了（网站出错页这样说）。 */
    LOGIN_CAPTCHA_WRONG,

    /** 账号密码登录：邮箱或密码不对（网站出错页这样说）。 */
    LOGIN_CREDENTIALS_WRONG,

    /** 账号密码登录：错误次数太多，网站暂时不让登录（十几分钟后才能再试）。 */
    LOGIN_TOO_MANY_ATTEMPTS,

    /** 账号密码登录没成功，网站给的原因认不出。 */
    LOGIN_FAILED,

    /** 浏览器登录：回调带的 state 对不上这次登录（旧的授权链接、别处发起的授权），不拿它换令牌。 */
    LOGIN_CALLBACK_MISMATCH,

    /** 人机验证没有通过：服务端拿令牌去校验失败（错误码 `CAPTCHA_ERROR`），重新验证再发即可。 */
    VERIFICATION_FAILED,

    /** bgm.tv 不让在这里发言：账号被禁言，或话题已关闭（发帖、回复、发动态回 403）。 */
    POSTING_NOT_ALLOWED,

    /** 改不了这层楼：楼下已经有人回复，或话题已关闭（服务端回 403）。 */
    EDIT_NOT_ALLOWED,

    /** 选的图片读不出来（不是图片，或文件已经不在了），换不了头像。 */
    IMAGE_UNREADABLE,

    /** 非公开小组只有成员能发帖、回复（错误码 `NOT_JOIN_PRIVATE_GROUP_ERROR`，服务端回 401，不能当成登录失效）。 */
    GROUP_MEMBERS_ONLY,
}

/** 以 [failure] 为原因的异常：数据层在「要失败」的地方抛它，`toFailure()` 原样取回原因。 */
class FailureException(val failure: Failure) : Exception(failure.toString())

/**
 * 请求发出之后没等到答复（超时、传输途中断开）：服务端可能已经收下。写操作不能当成「没发出去」，只读请求值得重发一次。
 * 连不上（[Failure.Offline]）请求没出门，不算。全仓唯一的判定。
 */
fun Failure.mayHaveReachedServer(): Boolean = this == Failure.Timeout || this == Failure.Interrupted
