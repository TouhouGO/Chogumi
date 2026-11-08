package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 网页地址只在 BgmWeb 一处拼：路径与网页一致，来自用户的段一律编码。 */
class BgmWebTest {

    @Test
    fun `各类页面的网页地址`() {
        assertEquals("https://bgm.tv/subject/253", BgmWeb.subject(253))
        assertEquals("https://bgm.tv/subject/253/stats", BgmWeb.subjectStats(253))
        assertEquals("https://bgm.tv/subject/topic/7", BgmWeb.topic(TopicType.SUBJECT, 7))
        assertEquals("https://bgm.tv/group/topic/8", BgmWeb.topic(TopicType.GROUP, 8))
        assertEquals("https://bgm.tv/ep/122548", BgmWeb.episode(122548))
        assertEquals("https://bgm.tv/character/8", BgmWeb.mono(MonoType.CHARACTER, 8))
        assertEquals("https://bgm.tv/person/9", BgmWeb.mono(MonoType.PERSON, 9))
        assertEquals("https://bgm.tv/blog/5", BgmWeb.blog(5))
        assertEquals("https://bgm.tv/pm/compose/42.chii", BgmWeb.privateMessage(42))
    }

    @Test
    fun `用户名与小组名按路径段编码`() {
        assertEquals("https://bgm.tv/user/a%20b", BgmWeb.user("a b"))
        assertEquals("https://bgm.tv/group/%E5%B0%8F%E7%BB%84", BgmWeb.group("小组"))
        assertEquals("https://bgm.tv/user/12345", BgmWeb.user("12345"))
    }
}
