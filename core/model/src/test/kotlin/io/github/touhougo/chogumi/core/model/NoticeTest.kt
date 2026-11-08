package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 一条通知点开去哪：类型只在 [noticeKindOf] 解一次，地址按服务端通知表拼成带楼层锚点的网页地址。 */
class NoticeTest {

    private fun notice(type: Int, mainId: Int = 472971, relatedId: Int = 2391, username: String = "fixture_notice_user", uid: Int = 9) = NoticeBo(
        id = 1,
        type = type,
        sender = NoticeSenderBo(id = uid, username = username, nickname = "示例发送者"),
        title = "示例通知",
        mainId = mainId,
        relatedId = relatedId,
        createdAt = 0L,
        unread = true,
    )

    @Test
    fun `回复与提到落到那一页的那层楼`() {
        assertEquals("https://bgm.tv/group/topic/472971#post_2391", noticeLinkOf(notice(2)))
        assertEquals("https://bgm.tv/group/topic/472971#post_2391", noticeLinkOf(notice(1)))
        assertEquals("https://bgm.tv/group/topic/472971#post_2391", noticeLinkOf(notice(23)))
        assertEquals("https://bgm.tv/subject/topic/472971#post_2391", noticeLinkOf(notice(4)))
        assertEquals("https://bgm.tv/subject/topic/472971#post_2391", noticeLinkOf(notice(24)))
        assertEquals("https://bgm.tv/blog/472971#post_2391", noticeLinkOf(notice(8)))
        assertEquals("https://bgm.tv/ep/472971#post_2391", noticeLinkOf(notice(10)))
        assertEquals("https://bgm.tv/character/472971#post_2391", noticeLinkOf(notice(6)))
        assertEquals("https://bgm.tv/person/472971#post_2391", noticeLinkOf(notice(13)))
        assertEquals("https://bgm.tv/index/472971#post_2391", noticeLinkOf(notice(12)))
    }

    @Test
    fun `没有楼层时打开那一页本身`() {
        assertEquals("https://bgm.tv/group/topic/472971", noticeLinkOf(notice(1, relatedId = 0)))
    }

    @Test
    fun `好友请求与通过去发送者主页`() {
        assertEquals("https://bgm.tv/user/fixture_notice_user", noticeLinkOf(notice(14)))
        assertEquals("https://bgm.tv/user/fixture_notice_user", noticeLinkOf(notice(15)))
        assertEquals("https://bgm.tv/user/9", noticeLinkOf(notice(14, username = "")))
        assertEquals("https://bgm.tv/user/9", noticeLinkOf(notice(15, username = "　 ")))
        assertEquals("https://bgm.tv/user/fixture_notice_user", noticeLinkOf(notice(14, uid = 0)))
    }

    @Test
    fun `好友通知发送者没有有效身份时没有入口`() {
        for (uid in listOf(0, -1)) {
            assertNull(noticeLinkOf(notice(14, username = "", uid = uid)))
            assertNull(noticeLinkOf(notice(15, username = " ", uid = uid)))
        }
    }

    @Test
    fun `没有去处的通知为null`() {
        for (type in listOf(0, 16, 22, 28, 17, 31, 35, 50, 51, -1)) {
            assertNull("type $type", noticeLinkOf(notice(type)))
        }
        assertNull(noticeLinkOf(notice(2, mainId = 0)))
    }

    @Test
    fun `7与8按服务端通知表的地址是日志`() {
        assertEquals(NoticeKind.BLOG_TOPIC_REPLY, noticeKindOf(7))
        assertEquals(NoticeKind.BLOG_POST_REPLY, noticeKindOf(8))
        assertEquals(NoticeKind.SUBJECT_TOPIC_REPLY, noticeKindOf(3))
    }

    @Test
    fun `楼层锚点只在楼id为正时加`() {
        assertEquals("https://bgm.tv/blog/5#post_7", BgmWeb.floor(BgmWeb.blog(5), 7))
        assertEquals("https://bgm.tv/blog/5", BgmWeb.floor(BgmWeb.blog(5), 0))
        assertEquals("https://bgm.tv/blog/5", BgmWeb.floor(BgmWeb.blog(5), -3))
    }
}
