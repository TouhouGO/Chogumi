package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 楼层长按菜单的「报告」与「网页」：报告对象与网页地址各自只在一处判定，按评论区与是否帖子正文分。 */
class FloorModerationTest {

    @Test
    fun `帖子正文报告话题或日志本身，其余楼报告这层楼`() {
        val group = CommentSurface.Topic(TopicType.GROUP, 7)
        val subject = CommentSurface.Topic(TopicType.SUBJECT, 8)
        assertEquals(ReportTarget(ReportType.GROUP_TOPIC, 7), floorReportTargetOf(group, 100, isPost = true))
        assertEquals(ReportTarget(ReportType.GROUP_REPLY, 100), floorReportTargetOf(group, 100, isPost = false))
        assertEquals(ReportTarget(ReportType.SUBJECT_TOPIC, 8), floorReportTargetOf(subject, 100, isPost = true))
        assertEquals(ReportTarget(ReportType.SUBJECT_REPLY, 100), floorReportTargetOf(subject, 100, isPost = false))
        assertEquals(ReportTarget(ReportType.BLOG, 5), floorReportTargetOf(CommentSurface.Blog(5), -5, isPost = true))
        assertEquals(ReportTarget(ReportType.BLOG_REPLY, 100), floorReportTargetOf(CommentSurface.Blog(5), 100, isPost = false))
    }

    @Test
    fun `留言箱与动态回复报告这层楼，角色人物各用自己的类型`() {
        assertEquals(ReportTarget(ReportType.EPISODE_REPLY, 3), floorReportTargetOf(CommentSurface.Episode(1), 3, isPost = false))
        assertEquals(ReportTarget(ReportType.INDEX_REPLY, 3), floorReportTargetOf(CommentSurface.Index(1), 3, isPost = false))
        assertEquals(ReportTarget(ReportType.CHARACTER_REPLY, 3), floorReportTargetOf(CommentSurface.Mono(MonoType.CHARACTER, 1), 3, isPost = false))
        assertEquals(ReportTarget(ReportType.PERSON_REPLY, 3), floorReportTargetOf(CommentSurface.Mono(MonoType.PERSON, 1), 3, isPost = false))
        assertEquals(ReportTarget(ReportType.TIMELINE_REPLY, 3), floorReportTargetOf(CommentSurface.Timeline(1L), 3, isPost = false))
    }

    @Test
    fun `作品吐槽没有报告类型`() {
        assertNull(floorReportTargetOf(CommentSurface.SubjectComments(1), 3, isPost = false))
    }

    @Test
    fun `报告类型的值与服务端契约一致`() {
        assertEquals(6, userReportTargetOf(42).type.value)
        assertEquals(42, userReportTargetOf(42).id)
        assertEquals(
            listOf(6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 19),
            ReportType.entries.map { it.value },
        )
    }

    @Test
    fun `楼层网页地址：正文是页面本身，其余带楼层锚点`() {
        assertEquals("https://bgm.tv/group/topic/7", BgmWeb.floor(CommentSurface.Topic(TopicType.GROUP, 7), 100, isPost = true))
        assertEquals("https://bgm.tv/group/topic/7#post_100", BgmWeb.floor(CommentSurface.Topic(TopicType.GROUP, 7), 100, isPost = false))
        assertEquals("https://bgm.tv/ep/1#post_3", BgmWeb.floor(CommentSurface.Episode(1), 3, isPost = false))
        assertEquals("https://bgm.tv/blog/5", BgmWeb.floor(CommentSurface.Blog(5), -5, isPost = true))
        assertEquals("https://bgm.tv/index/2#post_3", BgmWeb.floor(CommentSurface.Index(2), 3, isPost = false))
        assertEquals("https://bgm.tv/person/9#post_3", BgmWeb.floor(CommentSurface.Mono(MonoType.PERSON, 9), 3, isPost = false))
    }

    @Test
    fun `作品吐槽与动态回复在网页上没有楼层地址`() {
        assertNull(BgmWeb.floor(CommentSurface.SubjectComments(1), 3, isPost = false))
        assertNull(BgmWeb.floor(CommentSurface.Timeline(1L), 3, isPost = false))
    }
}
