package io.github.touhougo.chogumi.feature.profile

import io.github.touhougo.chogumi.core.model.UserBo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 资料头数字行「哪些项出现」的唯一判定 [profileNumberLineOf]。
 */
class ProfileNumberLineTest {

    private fun user(friends: Int, blogs: Int, indexCreate: Int, indexCollect: Int) = UserBo(
        id = 1, username = "u", nickname = "U",
        friendCount = friends, blogCount = blogs,
        indexCreateCount = indexCreate, indexCollectCount = indexCollect,
    )

    @Test
    fun `全有_顺序为好友日志目录`() {
        val items = profileNumberLineOf(user(3, 5, 2, 4), community = true)
        assertEquals(
            listOf(ProfileNumberItem.FRIENDS(3), ProfileNumberItem.BLOGS(5), ProfileNumberItem.INDEXES(IndexListCounts(created = 2, collected = 4))),
            items,
            "目录数 = 创建 + 收藏",
        )
        assertEquals(6, items.last().count, "目录数 = 创建 + 收藏")
    }

    @Test
    fun `为0的项不出现`() {
        val items = profileNumberLineOf(user(0, 5, 0, 0), community = true)
        assertEquals(listOf(ProfileNumberItem.BLOGS(5)), items)
    }

    @Test
    fun `全为0整行不出现`() {
        assertTrue(profileNumberLineOf(user(0, 0, 0, 0), community = true).isEmpty())
    }

    @Test
    fun `专注模式不显示好友_日志目录保留`() {
        val items = profileNumberLineOf(user(3, 5, 1, 0), community = false)
        assertEquals(
            listOf(ProfileNumberItem.BLOGS(5), ProfileNumberItem.INDEXES(IndexListCounts(created = 1, collected = 0))),
            items,
            "好友项被专注模式滤除",
        )
    }

    @Test
    fun `目录只收藏未创建也计入`() {
        val items = profileNumberLineOf(user(0, 0, 0, 9), community = true)
        assertEquals(listOf(ProfileNumberItem.INDEXES(IndexListCounts(created = 0, collected = 9))), items)
    }
}
