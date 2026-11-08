package io.github.touhougo.chogumi.feature.messages

import io.github.touhougo.chogumi.core.model.BlockedUsers
import io.github.touhougo.chogumi.core.model.PmConversationSummary
import io.github.touhougo.chogumi.core.model.UserBo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 选收件人的规则：按名字或用户名筛、绝交的不列、最近联系人里有的好友不重复、像用户名的输入可以直接写给。 */
class PmRecipientsTest {
    private val alice = UserBo(id = 1, username = "alice", nickname = "爱丽丝")
    private val bob = UserBo(id = 2, username = "bob_2", nickname = "")
    private val carol = UserBo(id = 3, username = "carol", nickname = "卡罗尔")

    /** 会话列表里的一行：读得出 uid 时是 [id]，读不出（默认头像）时是 0；行里没有用户名。 */
    private fun recent(conversationId: Int, id: Int, nickname: String) =
        PmConversationSummary(conversationId, UserBo(id = id, username = "", nickname = nickname), date = null, preview = "", unread = 0)

    @Test
    fun `输入为空时全列_最近联系人里已有的好友不在好友里再列`() {
        val result = pmRecipientsOf("", listOf(recent(10, id = 1, "爱丽丝"), recent(11, id = 0, "某人")), listOf(alice, bob), BlockedUsers.NONE)

        assertEquals(listOf(10, 11), result.recent.map { it.id })
        assertEquals(listOf(bob), result.friends)
        assertNull(result.typed)
    }

    @Test
    fun `按名字或用户名筛_不分大小写`() {
        val friends = listOf(alice, bob, carol)

        assertEquals(listOf(carol), pmRecipientsOf("罗", emptyList(), friends, BlockedUsers.NONE).friends)
        assertEquals(listOf(bob), pmRecipientsOf("BOB", emptyList(), friends, BlockedUsers.NONE).friends, "没有昵称的人按用户名显示，也按用户名筛")
        assertEquals(listOf(alice), pmRecipientsOf("ali", emptyList(), friends, BlockedUsers.NONE).friends)
    }

    @Test
    fun `最近联系人只按名字筛`() {
        val result = pmRecipientsOf("某", listOf(recent(10, id = 0, "某人"), recent(11, id = 5, "别人")), emptyList(), BlockedUsers.NONE)

        assertEquals(listOf(10), result.recent.map { it.id })
    }

    @Test
    fun `绝交的人不列_读不出uid的最近联系人不当作谁`() {
        val blocked = BlockedUsers(setOf(1, 3))

        val result = pmRecipientsOf("", listOf(recent(10, id = 3, "卡罗尔"), recent(11, id = 0, "某人")), listOf(alice, bob), blocked)

        assertEquals(listOf(11), result.recent.map { it.id })
        assertEquals(listOf(bob), result.friends)
    }

    @Test
    fun `筛掉的最近联系人不挡好友_按用户名找得到`() {
        // 爱丽丝在最近联系人里（只有名字），输入的是她的用户名：最近联系人那一行筛掉了，好友里照常列出
        val result = pmRecipientsOf("alice", listOf(recent(10, id = 1, "爱丽丝")), listOf(alice), BlockedUsers.NONE)

        assertEquals(emptyList(), result.recent)
        assertEquals(listOf(alice), result.friends)
    }

    @Test
    fun `像用户名又不是哪位好友的用户名时_可以直接写给它`() {
        val friends = listOf(alice)

        assertEquals("someone_1", pmRecipientsOf(" someone_1 ", emptyList(), friends, BlockedUsers.NONE).typed)
        assertNull(pmRecipientsOf("Alice", emptyList(), friends, BlockedUsers.NONE).typed, "是好友的用户名：好友那一行就是")
        assertNull(pmRecipientsOf("爱丽丝", emptyList(), friends, BlockedUsers.NONE).typed, "不像用户名")
        assertNull(pmRecipientsOf("", emptyList(), friends, BlockedUsers.NONE).typed)
    }
}
