package io.github.touhougo.chogumi.core.network

/** token 读取抽象：core:data 的 TokenStore 实现，Ktor Auth 插件消费 */
interface TokenProvider {
    suspend fun accessToken(): String?
}

/** token 刷新抽象：core:data 实现（调 AuthApi + 写 TokenStore） */
interface TokenRefresher {
    /**
     * v0 请求挨了 401 时调用；[rejectedAccessToken] 是那个请求带的访问令牌（没带为 null）。
     *
     * @return 当前是否有可用的新令牌。只有服务端明确拒绝刷新令牌时，实现方才清空会话（登出）；
     * 断网、超时、5xx 返回 false 并保留登录，原请求照常以失败结束。
     */
    suspend fun refresh(rejectedAccessToken: String?): Boolean
}
