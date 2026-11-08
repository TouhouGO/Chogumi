package io.github.touhougo.chogumi.core.network

/** OAuth 应用凭据（app 壳从 BuildConfig 提供给 Koin） */
data class AuthConfig(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
)
