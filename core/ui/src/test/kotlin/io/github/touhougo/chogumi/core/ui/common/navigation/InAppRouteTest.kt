package io.github.touhougo.chogumi.core.ui.common.navigation

import io.github.touhougo.chogumi.core.model.BgmWeb
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.TopicType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** bgm.tv 系链接 → App 内路由的唯一判定点 [inAppRoute]；其余为 null，去浏览器。 */
class InAppRouteTest {

    @Test
    fun `站内路径各自对应路由`() {
        assertEquals(Route.SubjectDetails(123), inAppRoute("https://bgm.tv/subject/123"))
        assertEquals(Route.MonoDetails(MonoType.PERSON, 7), inAppRoute("http://bangumi.tv/person/7"))
        assertEquals(Route.MonoDetails(MonoType.CHARACTER, 99), inAppRoute("https://chii.in/character/99"))
        assertEquals(Route.UserProfile("fixture_profile_user"), inAppRoute("https://bgm.tv/user/fixture_profile_user"))
        assertEquals(Route.IndexDetails(31), inAppRoute("https://bgm.tv/index/31"))
        assertEquals(Route.BlogDetails(55), inAppRoute("https://bgm.tv/blog/55"))
    }

    @Test
    fun `作品与小组话题路径解析_其余三段路径仍走浏览器`() {
        assertEquals(Route.Topic(TopicType.SUBJECT, 36704), inAppRoute("https://bgm.tv/subject/topic/36704"))
        assertEquals(Route.Topic(TopicType.SUBJECT, 892, focusPostId = 1), inAppRoute("/subject/topic/892#post_1"))
        assertEquals(Route.Topic(TopicType.GROUP, 472971), inAppRoute("https://bgm.tv/group/topic/472971"))
        assertEquals(Route.Topic(TopicType.GROUP, 1, focusPostId = 9), inAppRoute("/group/topic/1?page=2#post_9"))
        assertNull(inAppRoute("https://bgm.tv/subject/topic/abc"))
        assertNull(inAppRoute("https://bgm.tv/group/topic/abc"))
        assertNull(inAppRoute("https://bgm.tv/blog/topic/1"))
        assertNull(inAppRoute("https://bgm.tv/group/boring/forum"))
        assertNull(inAppRoute("https://bgm.tv/subject/253/topics"))
    }

    @Test
    fun `每日放送页_别的单段路径仍走浏览器`() {
        assertEquals(Route.Calendar, inAppRoute(BgmWeb.calendar()))
        assertEquals(Route.Calendar, inAppRoute("/calendar?week=1"))
        assertNull(inAppRoute("https://bgm.tv/anime"))
    }

    @Test
    fun `单集讨论页`() {
        assertEquals(Route.Episode(1749254), inAppRoute("https://bgm.tv/ep/1749254"))
        assertEquals(Route.Episode(7, focusPostId = 31258), inAppRoute("/ep/7#post_31258"))
        assertNull(inAppRoute("https://bgm.tv/ep/abc"))
    }

    @Test
    fun `短信会话页_主题筛选不算_其余短信网页仍走浏览器`() {
        assertEquals(Route.PmConversation(300003), inAppRoute("https://bgm.tv/pm/conversation/300003.chii"))
        assertEquals(Route.PmConversation(300003), inAppRoute("/pm/conversation/300003.chii?thread=500001"))
        assertNull(inAppRoute("https://bgm.tv/pm/conversation/abc.chii"))
        assertNull(inAppRoute("https://bgm.tv/pm/conversation/0.chii"))
        assertNull(inAppRoute("https://bgm.tv/pm/inbox.chii"))
        assertNull(inAppRoute("https://bgm.tv/pm/compose/400100.chii"))
    }

    @Test
    fun `小组页与全部小组_小组下的其他网页仍走浏览器`() {
        assertEquals(Route.Group("boring"), inAppRoute("https://bgm.tv/group/boring"))
        assertEquals(Route.Group("a"), inAppRoute("/group/a?page=2"))
        assertEquals(Route.AllGroups, inAppRoute("https://bgm.tv/group/all"))
        assertNull(inAppRoute("https://bgm.tv/group/discover"))
        assertNull(inAppRoute("https://bgm.tv/group/category/1"))
        assertNull(inAppRoute("https://bgm.tv/group/topic"))
    }

    @Test
    fun `各个站内域名都算_大小写不论`() {
        for (host in listOf("bgm.tv", "www.bgm.tv", "m.bgm.tv", "next.bgm.tv", "bangumi.tv", "www.bangumi.tv", "chii.in", "BGM.TV")) {
            assertEquals(Route.SubjectDetails(1), inAppRoute("https://$host/subject/1"), host)
        }
    }

    @Test
    fun `站外域名返回null`() {
        assertNull(inAppRoute("https://example.com/subject/123"))
        assertNull(inAppRoute("https://bgm.tv.evil.io/subject/123"))
    }

    @Test
    fun `查询串与锚点不影响解析`() {
        assertEquals(Route.SubjectDetails(123), inAppRoute("https://bgm.tv/subject/123?utm=x#top"))
    }

    @Test
    fun `楼层锚点带进话题_单集与日志_别的锚点不算`() {
        assertEquals(Route.Topic(TopicType.GROUP, 472971, focusPostId = 2391), inAppRoute("https://bgm.tv/group/topic/472971#post_2391"))
        assertEquals(Route.BlogDetails(55, focusPostId = 8), inAppRoute("https://bgm.tv/blog/55#post_8"))
        assertEquals(Route.Topic(TopicType.GROUP, 1), inAppRoute("https://bgm.tv/group/topic/1#top"))
        assertEquals(Route.Topic(TopicType.GROUP, 1), inAppRoute("https://bgm.tv/group/topic/1#post_x"))
        assertEquals(Route.MonoDetails(MonoType.CHARACTER, 3), inAppRoute("https://bgm.tv/character/3#post_5"))
    }

    @Test
    fun `无路由或非法路径返回null`() {
        assertNull(inAppRoute("https://bgm.tv/subject/abc"))
        assertNull(inAppRoute("https://bgm.tv/subject/"))
        assertNull(inAppRoute("https://bgm.tv/rakuen/topic/group/1"))
        assertNull(inAppRoute("ftp://bgm.tv/subject/1"))
        assertNull(inAppRoute("https://bgm.tv"))
        assertNull(inAppRoute("bgm.tv/subject/1"))
    }

    @Test
    fun `id 不是正整数 或用户名小组名带路径字符 都去浏览器`() {
        assertNull(inAppRoute("https://bgm.tv/subject/-1"))
        assertNull(inAppRoute("https://bgm.tv/subject/0"))
        assertNull(inAppRoute("https://bgm.tv/group/topic/-5"))
        assertNull(inAppRoute("https://bgm.tv/user/.."))
        assertNull(inAppRoute("https://bgm.tv/group/a%2Fb"))
        assertEquals(Route.UserProfile("fixture_user_1-x"), inAppRoute("https://bgm.tv/user/fixture_user_1-x"))
    }

    @Test
    fun `相对路径与协议相对链接视为站内`() {
        assertEquals(Route.SubjectDetails(123), inAppRoute("/subject/123"))
        assertEquals(Route.SubjectDetails(123), inAppRoute("//bgm.tv/subject/123"))
    }
}
