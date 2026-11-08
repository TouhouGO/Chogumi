package io.github.touhougo.chogumi.core.data.token

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** 令牌库（chogumi_tokens）里凭证与会话的键，[TokenStore] 只从这里取。 */
internal object TokenStoreKeys {
    val ACCESS_TOKEN = stringPreferencesKey("access_token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val USER_ID = intPreferencesKey("user_id")
    val USERNAME = stringPreferencesKey("username")
    val OAUTH_STATE = stringPreferencesKey("oauth_state")
    val SESSION_GENERATION = longPreferencesKey("session_generation")
    /** 网页会话的 Cookie，加密后的密文（见 WebSessionRepository）。 */
    val WEB_COOKIE = stringPreferencesKey("web_cookie")
}
