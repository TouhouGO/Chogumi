package io.github.touhougo.chogumi.core.model

/** 只表达本地会话恢复，不等待首页或其他业务网络请求。 */
sealed interface SessionState {
    data object Initializing : SessionState
    /** 已登录：[account] 是当前账号，「这个用户是不是我」只由它回答（[SignedInAccount.isMe]）。 */
    data class Authenticated(val account: SignedInAccount) : SessionState
    data object Unauthenticated : SessionState
    /** 本地会话读不出来（DataStore 读失败）；界面报「无法恢复本地设置」并可重试。 */
    data object InitializationError : SessionState
}

/** 当前登录的账号（只有身份，没有令牌）。 */
data class SignedInAccount(val userId: Int, val username: String) {
    /**
     * [userKey] 指的是不是这个账号：路由与链接里的用户键是用户名，作者没有用户名时是数字 id，两种都认；
     * 用户名不分大小写（网页地址里大小写都能打开同一个人）。
     */
    fun isMe(userKey: String): Boolean = userKey.equals(username, ignoreCase = true) || userKey == userId.toString()
}
