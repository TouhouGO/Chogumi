package io.github.touhougo.chogumi.core.network.api

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.http.HttpHeaders

/**
 * 将请求绑定到**捕获时刻**的会话 token，而不是发送/重试时再读全局 token。
 *
 * 设计目标：一个 `HttpClient` 服务多个身份。p1 的 401 不能触发 v0 的刷新失败登出；
 * 换号后，旧账号排队的写请求也不能用新账号的 token 发出去。
 *
 * ## 为什么不在这里用 `AuthCircuitBreaker`
 *
 * 直觉上 `attributes.put(AuthCircuitBreaker, Unit)` 像是"让 Auth 插件别管这个请求"。
 * 实测（Ktor 3.5.2，变体矩阵）：
 *
 * | 装了 Auth 插件 | 置 CircuitBreaker | 显式 bearerAuth | 实际发出的 Authorization |
 * |---|---|---|---|
 * | 否 | 否 | 是 | `Bearer <显式>` |
 * | 否 | 是 | 是 | `Bearer <显式>` |
 * | 是 | 否 | 是 | `Bearer <插件预注入的>` |
 * | 是 | 是 | 是 | **`null`（头被整个丢弃）** |
 *
 * 生产客户端装了 `Auth { bearer { } }`，所以线上命中最后一行：**所有鉴权请求都不带 token**。
 * 表现为服务端返回 401 `{"title":"Unauthorized","description":"need Login"}`
 * （实测确认：只有"完全不带 Authorization 头"时 bgm.tv 才返回这句话）。
 *
 * 因此这里**不能**置 `AuthCircuitBreaker`。防止插件覆盖显式 token 的责任，
 * 由 [io.github.touhougo.chogumi.core.network.networkModule] 里 `bearer { loadTokens { null } }` 承担
 * ——不预注入，插件就没有 token 可用来覆盖。
 *
 * ## 与 401 自动刷新的关系（已知限制，勿踩）
 *
 * Ktor 的 Auth 插件在 401 后重试时，**不会重跑请求块、也不会重新应用刷新结果**：
 * 实测 `blockRuns=1, sent=[Bearer old, Bearer old]`，即刷新成功但重试仍发旧 token。
 * 所以本函数**不承担刷新语义**：调用方传入的 token 应当来自一次有效会话；
 * token 过期应由 [io.github.touhougo.chogumi.core.data.token.TokenStore] / 刷新链路在发起前解决，
 * 不能指望这里"发出去被拒后再自动救回来"。
 *
 * @param token 该请求所属会话的 token；null 或空白表示匿名请求（不带 Authorization 头）。
 */
internal fun HttpRequestBuilder.pinSessionToken(token: String?) {
    headers.remove(HttpHeaders.Authorization)
    token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
}
