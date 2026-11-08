package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 自己的楼能改、能删到哪一步（照服务端的规则），屏蔽的、已退出的小组在社区帖子流里怎么滤。 */
class OwnPostsAndMutesTest {

    private val topic = CommentSurface.Topic(TopicType.GROUP, 5)

    private fun item(own: Boolean, state: CommentState = CommentState.NORMAL) = CommentItemBo(
        id = 20, mainId = 5, relatedId = 0, creatorId = 1, creatorNickname = "我", creatorUsername = "me",
        creatorAvatar = null, content = "c", createdAt = 0L, state = state, isOwn = own,
    )

    @Test
    fun `自己的楼：楼下没回复能改能删，有回复只能删，帖子正文能改不能删`() {
        assertEquals(OwnFloorActions(edit = true, delete = true), ownFloorActionsOf(topic, item(own = true), repliesBelow = 0, isPost = false))
        assertEquals(OwnFloorActions(edit = false, delete = true), ownFloorActionsOf(topic, item(own = true), repliesBelow = 2, isPost = false))
        assertEquals(OwnFloorActions(edit = true, delete = false), ownFloorActionsOf(topic, item(own = true), repliesBelow = 0, isPost = true))
    }

    @Test
    fun `别人的楼、删掉的楼、没接编辑接口的评论区：什么都没有`() {
        assertEquals(OwnFloorActions.None, ownFloorActionsOf(topic, item(own = false), repliesBelow = 0, isPost = false))
        assertEquals(OwnFloorActions.None, ownFloorActionsOf(topic, item(own = true, state = CommentState.DELETED), repliesBelow = 0, isPost = false))
        assertEquals(OwnFloorActions.None, ownFloorActionsOf(CommentSurface.Blog(1), item(own = true), repliesBelow = 0, isPost = false))
    }

    private fun group(name: String) = CommunityTopicBo(
        key = "group:$name", webUrl = "", title = name, episode = null,
        source = CommunityTopicSource.Group(GroupSummaryBo(name = name, title = name, icon = null, members = 0)),
        author = null, replies = 0, updatedAt = 0L,
    )

    @Test
    fun `屏蔽的小组的帖子滤掉，别的小组和别的来源不动`() {
        val subject = CommunityTopicBo(
            key = "subject:1", webUrl = "", title = "s", episode = null,
            source = CommunityTopicSource.Subject(TopicSubjectBo(1, SubjectType.ANIME, "n", "", null)),
            author = null, replies = 0, updatedAt = 0L,
        )
        val topics = listOf(group("boring"), subject, group("a"))
        assertEquals(listOf(subject, group("a")), withoutMutedGroups(topics, setOf("boring")))
        assertEquals(topics, withoutMutedGroups(topics, emptySet()))
    }

    @Test
    fun `小组页只留实时加入名单里的小组，名单未知时不筛，空名单不留旧帖`() {
        val topics = listOf(group("boring"), group("a"), group("film"))
        assertEquals(listOf(group("a"), group("film")), onlyJoinedGroups(topics, setOf("a", "film")))
        assertEquals(topics, onlyJoinedGroups(topics, null))
        assertEquals(emptyList<CommunityTopicBo>(), onlyJoinedGroups(topics, emptySet()))
    }
}
