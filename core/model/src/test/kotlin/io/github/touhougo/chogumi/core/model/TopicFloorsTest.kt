package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicFloorsTest {

    private fun post(
        id: Int,
        creatorId: Int,
        reactions: Int = 0,
        state: CommentState = CommentState.NORMAL,
    ) = CommentItemBo(
        id = id,
        mainId = 1,
        relatedId = 0,
        creatorId = creatorId,
        creatorNickname = "u$creatorId",
        creatorUsername = "u$creatorId",
        creatorAvatar = null,
        content = "c$id",
        createdAt = id.toLong(),
        state = state,
        reactions = if (reactions > 0) listOf(reactionOf(value = 0, count = reactions)) else emptyList(),
    )

    private fun thread(anchor: CommentItemBo, vararg replies: CommentItemBo) = CommentThreadBo(anchor, replies.toList())

    private val op = 7

    // #1 楼主正文；#2 热度 3；#3 热度 1 + 2×1 = 3（楼主在楼中楼回复）；#4 已删除；#5 楼主，热度 5
    private val threads = listOf(
        thread(post(10, op)),
        thread(post(20, 1, reactions = 3)),
        thread(post(30, 2, reactions = 1), post(31, op)),
        thread(post(40, op, reactions = 9, state = CommentState.DELETED)),
        thread(post(50, op, reactions = 5)),
    )

    private fun numbers(sort: TopicSort, opOnly: Boolean = false) =
        topicFloors(threads, sort, opOnly, op).map { it.number }

    @Test
    fun `楼层顺序原样，楼层号按原始位置`() {
        assertEquals(listOf(1, 2, 3, 4, 5), numbers(TopicSort.FLOOR))
    }

    @Test
    fun `最热：#1 固定在前，其余按热度降序，同热度按楼层顺序，已删除沉底`() {
        assertEquals(listOf(1, 5, 2, 3, 4), numbers(TopicSort.HOT))
    }

    @Test
    fun `最新：#1 固定在前，其余倒序`() {
        assertEquals(listOf(1, 5, 4, 3, 2), numbers(TopicSort.LATEST))
    }

    @Test
    fun `只看楼主：留楼主的主楼与楼主回复过的串，已删除的不留，楼层号不变`() {
        assertEquals(listOf(1, 3, 5), numbers(TopicSort.FLOOR, opOnly = true))
        assertEquals(listOf(1, 5, 3), numbers(TopicSort.HOT, opOnly = true))
    }

    @Test
    fun `热度：贴贴总数加楼中楼条数乘 2，已删除为 -1`() {
        assertEquals(3, topicFloorHeat(threads[2]))
        assertEquals(-1, topicFloorHeat(threads[3]))
        assertEquals(0, topicFloorHeat(threads[0]))
    }

    @Test
    fun `引用落点：主楼与楼中楼都映射到所在项，被过滤掉的楼没有键`() {
        val index = topicFloors(threads, TopicSort.FLOOR, opOnly = true, opCreatorId = op).floorIndexByPost()
        assertEquals(mapOf(10 to 0, 30 to 1, 31 to 1, 50 to 2), index)
        assertNull(index[20])
    }

    @Test
    fun `不固定首楼：留言全部参与排序与只看楼主，楼层号仍按原始位置`() {
        fun numbers(sort: TopicSort, opOnly: Boolean = false) =
            topicFloors(threads, sort, opOnly, op, firstIsPost = false).map { it.number }
        assertEquals(listOf(5, 4, 3, 2, 1), numbers(TopicSort.LATEST))
        assertEquals(listOf(5, 2, 3, 1, 4), numbers(TopicSort.HOT))
        assertEquals(listOf(1, 3, 5), numbers(TopicSort.FLOOR, opOnly = true))
    }

    @Test
    fun `楼主未知时只看楼主不过滤`() {
        assertEquals(listOf(1, 2, 3, 4, 5), topicFloors(threads, TopicSort.FLOOR, opOnly = true, opCreatorId = null).map { it.number })
    }

    @Test
    fun `阅读视图：可点的引用只留落点仍在显示楼层里的`() {
        val reading = FloorReading(threads, allQuoteTargets = mapOf(31 to 20, 50 to 10), opCreatorId = op, opOnly = true, blocked = BlockedUsers.NONE)
        assertEquals(listOf(1, 3, 5), reading.floors.map { it.number })
        assertEquals(mapOf(50 to 10), reading.quoteTargets)
        assertEquals(mapOf(31 to 20, 50 to 10), reading.copy(opOnly = false).quoteTargets)
    }

    @Test
    fun `帖子正文：#1 楼单独拿出，回复从 #2 起，排序与只看楼主只动回复`() {
        val reading = FloorReading(threads, opCreatorId = op, sort = TopicSort.LATEST, blocked = BlockedUsers.NONE)
        assertEquals(1, reading.post?.number)
        assertEquals(listOf(5, 4, 3, 2), reading.replies.map { it.number })
        assertEquals(listOf(3, 5), reading.copy(sort = TopicSort.FLOOR, opOnly = true).replies.map { it.number })
        // 正文仍在 floors 第 0 项：引用 #1 楼的跳转落到正文
        assertEquals(0, reading.floorIndexByPost[10])
    }

    @Test
    fun `没有正文概念（日志与目录留言）：post 为空，回复即全部楼层`() {
        val reading = FloorReading(threads, opCreatorId = op, firstIsPost = false, blocked = BlockedUsers.NONE)
        assertNull(reading.post)
        assertEquals(reading.floors, reading.replies)
    }

    @Test
    fun `空话题不崩`() {
        assertNull(FloorReading().post)
        assertEquals(emptyList<TopicFloor>(), FloorReading().replies)

        assertEquals(emptyList<TopicFloor>(), topicFloors(emptyList(), TopicSort.HOT, opOnly = true, opCreatorId = op))
    }

    @Test
    fun `绝交用户：主楼整串不显示、楼层号跳号；别人楼里的楼中楼只去掉那一条；#1 正文留着只去掉楼中楼`() {
        val withBlocked = listOf(
            thread(post(10, op), post(11, 9), post(12, 1)),
            thread(post(20, 9), post(21, 1)),
            thread(post(30, 2), post(31, 9)),
        )
        val blocked = BlockedUsers(setOf(9))
        val floors = topicFloors(withBlocked, TopicSort.FLOOR, opOnly = false, opCreatorId = op, blocked = blocked)
        assertEquals(listOf(1, 3), floors.map { it.number })
        assertEquals(listOf(12), floors[0].thread.replies.map { it.id })
        assertEquals(emptyList<CommentItemBo>(), floors[1].thread.replies)

        val comments = topicFloors(withBlocked, TopicSort.FLOOR, opOnly = false, opCreatorId = op, firstIsPost = false, blocked = blocked)
        assertEquals(listOf(1, 3), comments.map { it.number })

        val blockedAuthor = listOf(thread(post(10, 9), post(11, 9), post(12, 1)), thread(post(20, 2)))
        val topic = topicFloors(blockedAuthor, TopicSort.FLOOR, opOnly = false, opCreatorId = 9, blocked = blocked)
        assertEquals("帖子正文不能因楼主被绝交而消失", listOf(1, 2), topic.map { it.number })
        assertEquals(10, topic.first().thread.anchor.id)
        assertEquals(listOf(12), topic.first().thread.replies.map { it.id })
        assertEquals(
            "同一条在普通留言区不是正文，应按绝交名单隐藏",
            listOf(2),
            topicFloors(blockedAuthor, TopicSort.FLOOR, opOnly = false, opCreatorId = 9, firstIsPost = false, blocked = blocked).map { it.number },
        )
    }

    @Test
    fun `名单变了同一份楼层当场重排：新绝交的人的楼与指向它的引用一起拿掉，取消后回来`() {
        val shown = FloorReading(
            threads = listOf(thread(post(10, op)), thread(post(20, 9)), thread(post(30, 2))),
            allQuoteTargets = mapOf(30 to 20),
            opCreatorId = op,
            blocked = BlockedUsers.NONE,
        )
        assertEquals(listOf(2, 3), shown.replies.map { it.number })
        assertEquals(mapOf(30 to 20), shown.quoteTargets)

        val afterBlock = shown.copy(blocked = BlockedUsers(setOf(9)))
        assertEquals(listOf(3), afterBlock.replies.map { it.number })
        assertEquals(emptyMap<Int, Int>(), afterBlock.quoteTargets)

        assertEquals(listOf(2, 3), afterBlock.copy(blocked = BlockedUsers.NONE).replies.map { it.number })
    }

    @Test
    fun `名单还没读到时一层楼也不给_读到后按名单一次给出`() {
        val unread = FloorReading(threads = listOf(thread(post(10, op)), thread(post(20, 9)), thread(post(30, 2))), opCreatorId = op)
        assertNull(unread.post)
        assertEquals(emptyList<TopicFloor>(), unread.floors)

        assertEquals(listOf(1, 3), unread.copy(blocked = BlockedUsers(setOf(9))).floors.map { it.number })
    }
}
