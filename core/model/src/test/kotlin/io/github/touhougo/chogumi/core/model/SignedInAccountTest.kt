package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「这个用户是不是我」只由 SignedInAccount.isMe 回答：用户名（不分大小写）与数字 id 都认。 */
class SignedInAccountTest {
    private val me = SignedInAccount(userId = 400100, username = "fixture_user")

    @Test
    fun `用户名或数字 id 指向当前账号`() {
        assertTrue(me.isMe("fixture_user"))
        assertTrue(me.isMe("FIXTURE_USER"))
        assertTrue(me.isMe("400100"))
    }

    @Test
    fun `别人的用户名与 id 不是我`() {
        assertFalse(me.isMe("fixture_profile_user"))
        assertFalse(me.isMe("900002"))
        assertFalse(me.isMe(""))
    }
}
