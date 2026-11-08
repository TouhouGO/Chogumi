package io.github.touhougo.chogumi.shell

import android.webkit.CookieManager
import android.webkit.WebStorage
import io.github.touhougo.chogumi.core.model.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * 账号离开（登出，或直接换到另一个账号）时清掉 WebView 的 Cookie 与网页存储。应用里的 WebView 只有发帖前的人机验证，
 * 它留下的是 Cloudflare 的 Cookie；留给下一个账号，Cloudflare 会把两个账号对上同一台设备。
 */
fun resetWebViewDataOnAccountDeparture(sessionState: Flow<SessionState>, scope: CoroutineScope) {
    scope.launch(Dispatchers.Main) {
        sessionState.accountDepartures().collect {
            // 系统 WebView 不可用（被停用、正在更新）时 getInstance 会抛：清不了就不清，不能让登出崩溃
            runCatching {
                val cookies = CookieManager.getInstance()
                // 删除是异步的：落盘后才算数，否则登出后进程很快被杀，Cookie 又回来
                cookies.removeAllCookies { cookies.flush() }
                WebStorage.getInstance().deleteAllData()
            }
        }
    }
}

/**
 * 每有一个已登录的账号离开，发一次它的 id。启动时读本地会话、读不出会话都不算离开；
 * 同一个账号重复出现（令牌刷新）也不算。
 */
internal fun Flow<SessionState>.accountDepartures(): Flow<Int> = flow {
    var current: Int? = null
    collect { state ->
        val next = when (state) {
            is SessionState.Authenticated -> state.account.userId
            SessionState.Unauthenticated -> null
            SessionState.Initializing, SessionState.InitializationError -> return@collect
        }
        val left = current
        current = next
        if (left != null && left != next) emit(left)
    }
}
