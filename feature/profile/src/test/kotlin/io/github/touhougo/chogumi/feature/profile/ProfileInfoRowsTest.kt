package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.res.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⓘ 面板「有什么」的唯一判定 [profileInfoOf]：ⓘ 图标出不出现与面板内容都读它。
 */
class ProfileInfoRowsTest {

    private fun user(joinedAt: Long = 0, location: String = "", site: String = "", bio: String = "") = UserBo(
        id = 1, username = "u", nickname = "U", joinedAt = joinedAt, location = location, site = site, bio = bio,
    )

    @Test
    fun `三项都有时顺序为加入时间、所在地、主页，只有主页可点`() {
        val rows = profileInfoOf(user(joinedAt = 1_500_000_000_000, location = "东京", site = "https://example.com")).rows
        assertEquals(
            listOf(R.string.profile_joined_at, R.string.profile_location, R.string.profile_site),
            rows.map { it.labelRes },
        )
        assertEquals(listOf(null, null, "https://example.com"), rows.map { it.link })
    }

    @Test
    fun `没有值的行不出现`() {
        val rows = profileInfoOf(user(location = "东京")).rows
        assertEquals(listOf(R.string.profile_location), rows.map { it.labelRes })
        assertNull(rows.single().link)
    }

    @Test
    fun `没有简介也没有资料行时为空，图标不出现`() {
        assertTrue(profileInfoOf(user(bio = "  ")).isEmpty)
    }

    @Test
    fun `只写了简介也有东西可看`() {
        val info = profileInfoOf(user(bio = "[b]你好[/b]"))
        assertEquals("[b]你好[/b]", info.bio)
        assertTrue(info.rows.isEmpty())
        assertFalse(info.isEmpty)
    }
}
