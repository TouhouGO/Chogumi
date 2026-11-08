package io.github.touhougo.chogumi.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileChangeTest {
    private val me = UserBo(id = 42, username = "fixture_user", nickname = "新昵称", avatarLarge = "https://lain.bgm.tv/l/42.jpg?r=2")

    @Test
    fun 自我介绍按换行统一与去掉首尾空白之后比() {
        val after = me.copy(bio = "第一行\r\n第二行")

        assertTrue(ProfileChange.Bio("\n第一行\n第二行  ").landedIn(after, avatarBefore = null))
        assertFalse(ProfileChange.Bio("第一行\n第三行").landedIn(after, avatarBefore = null))
    }

    @Test
    fun 昵称去掉首尾空白之后比() {
        assertTrue(ProfileChange.Nickname(" 新昵称 ").landedIn(me, avatarBefore = null))
        assertFalse(ProfileChange.Nickname("别的昵称").landedIn(me, avatarBefore = null))
    }

    @Test
    fun 头像回读的地址变了才算换成() {
        val jpeg = ProfileChange.Avatar(byteArrayOf(1))

        assertTrue(jpeg.landedIn(me, avatarBefore = "https://lain.bgm.tv/l/42.jpg?r=1"))
        assertFalse(jpeg.landedIn(me, avatarBefore = me.avatarLarge))
        assertFalse(jpeg.landedIn(me.copy(avatarLarge = ""), avatarBefore = "https://lain.bgm.tv/l/42.jpg?r=1"))
    }
}
