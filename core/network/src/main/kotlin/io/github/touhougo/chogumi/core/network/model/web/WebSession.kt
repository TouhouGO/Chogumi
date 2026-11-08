package io.github.touhougo.chogumi.core.network.model.web

/** 登录后的 bgm.tv 网页会话：网页认的是 Cookie（`name=value; …`），不认 OAuth 令牌。 */
data class WebSession(val cookie: String)
