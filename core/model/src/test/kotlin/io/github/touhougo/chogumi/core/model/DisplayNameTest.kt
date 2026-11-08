package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayNameTest {

    @Test
    fun `有中文名用中文名`() {
        assertEquals("进击的巨人", displayNameOf(name = "進撃の巨人", nameCn = "进击的巨人"))
    }

    @Test
    fun `中文名为空或只有空白时回退原名`() {
        assertEquals("進撃の巨人", displayNameOf(name = "進撃の巨人", nameCn = ""))
        assertEquals("進撃の巨人", displayNameOf(name = "進撃の巨人", nameCn = "  "))
    }

    @Test
    fun `实体与两个字符串走同一条规则`() {
        val subject = SubjectBo(1, SubjectType.ANIME, name = "Cowboy Bebop", nameCn = " ")
        val episode = EpisodeBo(id = 2, subjectId = 1, type = 0, name = "Asteroid Blues", nameCn = "小行星蓝调", sort = 1.0)
        assertEquals("Cowboy Bebop", displayNameOf(subject))
        assertEquals("小行星蓝调", displayNameOf(episode))
    }

    @Test
    fun `用户有昵称用昵称`() {
        assertEquals("Fixture User", displayNameOf(UserBo(id = 9, username = "fixture_user", nickname = "Fixture User")))
    }

    @Test
    fun `用户昵称为空或只有空白时回退用户名_两者都空时写uid`() {
        assertEquals("fixture_user", displayNameOf(UserBo(id = 9, username = "fixture_user", nickname = " ")))
        assertEquals("#9", displayNameOf(UserBo(id = 9, username = "", nickname = "")))
        assertEquals("#9", userDisplayNameOf(username = " ", nickname = "", uid = 9))
    }

    @Test
    fun `资料没随来时只有uid`() {
        assertEquals("#42", displayNameOf(user = null, uid = 42))
        assertEquals("Fixture User", displayNameOf(UserBo(id = 9, username = "fixture_user", nickname = "Fixture User"), uid = 9))
    }

    @Test
    fun `用户键有用户名时用用户名_空白或缺失时用数字id`() {
        assertEquals("fixture_user", userKeyOf(username = "fixture_user", uid = 9))
        assertEquals("9", userKeyOf(username = " ", uid = 9))
        assertEquals("9", userKeyOf(username = null, uid = 9))
    }
}
