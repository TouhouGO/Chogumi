package io.github.touhougo.chogumi.core.ui.common.navigation

import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TopicType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 能收录的页面 → 目录目标：七类各有一种页面；目标到页面的 Route 在 app 接线层验证。 */
class IndexTargetsTest {

    @Test
    fun `七类页面各自交出对应的目录目标`() {
        assertEquals(IndexTarget(IndexCategory.SUBJECT, 1), indexTargetOf(Route.SubjectDetails(1)))
        assertEquals(IndexTarget(IndexCategory.CHARACTER, 2), indexTargetOf(Route.MonoDetails(MonoType.CHARACTER, 2)))
        assertEquals(IndexTarget(IndexCategory.PERSON, 3), indexTargetOf(Route.MonoDetails(MonoType.PERSON, 3)))
        assertEquals(IndexTarget(IndexCategory.EPISODE, 4), indexTargetOf(Route.Episode(4)))
        assertEquals(IndexTarget(IndexCategory.BLOG, 5), indexTargetOf(Route.BlogDetails(5)))
        assertEquals(IndexTarget(IndexCategory.GROUP_TOPIC, 6), indexTargetOf(Route.Topic(TopicType.GROUP, 6)))
        assertEquals(IndexTarget(IndexCategory.SUBJECT_TOPIC, 7), indexTargetOf(Route.Topic(TopicType.SUBJECT, 7)))
    }

    @Test
    fun `贴进来的链接经inAppRoute认出类别_楼层锚点不影响`() {
        assertEquals(IndexTarget(IndexCategory.SUBJECT, 265), inAppRoute("https://bgm.tv/subject/265")?.let(::indexTargetOf))
        assertEquals(IndexTarget(IndexCategory.SUBJECT_TOPIC, 9), inAppRoute("https://bgm.tv/subject/topic/9#post_3")?.let(::indexTargetOf))
        assertEquals(IndexTarget(IndexCategory.GROUP_TOPIC, 8), inAppRoute("https://bangumi.tv/group/topic/8")?.let(::indexTargetOf))
        assertEquals(IndexTarget(IndexCategory.EPISODE, 7), inAppRoute("https://bgm.tv/ep/7")?.let(::indexTargetOf))
        assertEquals(IndexTarget(IndexCategory.BLOG, 6), inAppRoute("/blog/6")?.let(::indexTargetOf))
        assertEquals(IndexTarget(IndexCategory.PERSON, 5), inAppRoute("https://chii.in/person/5")?.let(::indexTargetOf))
    }

    @Test
    fun `贴进来的文字里取第一个能收录的链接_分享出去的标题加链接原样贴回也认`() {
        assertEquals(IndexTarget(IndexCategory.SUBJECT, 265), indexLinkOf("  https://bgm.tv/subject/265  ")?.target)
        assertEquals(
            IndexLink("https://bgm.tv/character/1", IndexTarget(IndexCategory.CHARACTER, 1)),
            indexLinkOf("某角色\nhttps://bgm.tv/character/1"),
            "只取链接那一段",
        )
        assertEquals(
            IndexTarget(IndexCategory.BLOG, 6),
            indexLinkOf("https://bgm.tv/user/fixture_user https://bgm.tv/blog/6")?.target,
            "前面收不进目录的链接跳过",
        )
        assertNull(indexLinkOf(""))
        assertNull(indexLinkOf("265"), "光看数字分不出类别")
        assertNull(indexLinkOf("https://bgm.tv/index/1"), "目录不能收进目录")
        assertNull(indexLinkOf("https://example.com/subject/265"))
    }

    @Test
    fun `收不进目录的页面没有目标`() {
        assertNull(indexTargetOf(Route.UserProfile("fixture_user")))
        assertNull(indexTargetOf(Route.Group("boring")))
        assertNull(indexTargetOf(Route.IndexDetails(1)), "目录不能收进目录")
        assertNull(indexTargetOf(Route.UserCareer(null, SubjectType.ANIME)))
    }
}
