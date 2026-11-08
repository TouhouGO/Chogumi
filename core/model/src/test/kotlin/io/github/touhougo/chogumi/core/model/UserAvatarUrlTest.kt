package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserAvatarUrlTest {

    @Test
    fun `优先大图`() = assertEquals("l", userAvatarUrl("l", "m", "s"))

    @Test
    fun `大图为空依次回退中图与小图`() {
        assertEquals("m", userAvatarUrl("", "m", "s"))
        assertEquals("s", userAvatarUrl(" ", "", "s"))
    }

    @Test
    fun `三档都空返回 null`() = assertNull(userAvatarUrl("", "", ""))

    @Test
    fun `UserBo 走同一规则`() =
        assertEquals("m", UserBo(id = 1, username = "u", nickname = "n", avatarMedium = "m", avatarSmall = "s").avatarUrl)
}
