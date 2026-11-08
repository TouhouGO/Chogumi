package io.github.touhougo.chogumi.shell

import io.github.touhougo.chogumi.core.model.SessionState
import io.github.touhougo.chogumi.core.model.SignedInAccount
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 什么时候清 WebView 的 Cookie：只在一个已登录的账号离开时。 */
class AccountDeparturesTest {

    private fun signedIn(id: Int) = SessionState.Authenticated(SignedInAccount(id, "user$id"))

    private fun departures(vararg states: SessionState): List<Int> = runBlocking { flowOf(*states).accountDepartures().toList() }

    @Test
    fun `启动时读出登录态或游客态 不算离开`() {
        assertEquals(emptyList<Int>(), departures(SessionState.Initializing, signedIn(7)))
        assertEquals(emptyList<Int>(), departures(SessionState.Initializing, SessionState.Unauthenticated, signedIn(7)))
    }

    @Test
    fun `登出算离开 同一个账号再出现不算`() {
        assertEquals(listOf(7), departures(SessionState.Initializing, signedIn(7), signedIn(7), SessionState.Unauthenticated))
    }

    @Test
    fun `直接换到另一个账号算前一个账号离开`() {
        assertEquals(listOf(7, 8), departures(signedIn(7), signedIn(8), SessionState.Unauthenticated))
    }

    @Test
    fun `本地会话读不出来不算离开`() {
        assertEquals(emptyList<Int>(), departures(signedIn(7), SessionState.InitializationError, signedIn(7)))
    }
}
