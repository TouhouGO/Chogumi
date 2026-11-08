package io.github.touhougo.chogumi.core.data.token

import io.github.touhougo.chogumi.core.data.repository.TestResources
import io.github.touhougo.chogumi.core.data.repository.testTokenStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenStoreTest {
    @get:org.junit.Rule
    val resources = TestResources()

    @Test
    fun `保存与读取 token 与用户`() = runTest {
        val s = testTokenStore(resources)
        s.completeSession(s.beginSession("at", refreshToken = "rt"), userId = 42, username = "fixture_user")
        s.saveOAuthState("state123")
        assertEquals("at", s.accessToken())
        assertEquals("rt", s.refreshGrant()?.refreshToken)
        assertEquals(42, s.session().userId)
        assertEquals("fixture_user", s.session().username)
        assertFalse(s.consumeOAuthState("forged"), "对不上的不消费")
        assertTrue(s.consumeOAuthState("state123"))
        assertFalse(s.consumeOAuthState("state123"), "state 用过即删")
    }

    @Test
    fun `clear 清空全部`() = runTest {
        val s = testTokenStore(resources)
        s.completeSession(s.beginSession("at"), userId = 1, username = "fixture_user")
        s.clear()
        assertNull(s.accessToken())
        assertNull(s.refreshGrant()?.refreshToken)
        assertNull(s.session().userId)
    }
}
