package io.github.touhougo.chogumi.feature.settings

import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 调试跳转的输入归一：只负责把手输的东西变成链接，去哪一页仍由 inAppRoute 判定。 */
class DebugRouteTest {

    @Test
    fun `裸数字是条目_裸名字是用户`() {
        assertEquals(Route.SubjectDetails(253), debugRoute(" 253 "))
        assertEquals(Route.UserProfile("fixture_user"), debugRoute("fixture_user"))
    }

    @Test
    fun `完整链接与不带协议的链接`() {
        assertEquals(Route.MonoDetails(MonoType.PERSON, 7), debugRoute("https://bgm.tv/person/7"))
        assertEquals(Route.UserProfile("demo_user"), debugRoute("bgm.tv/user/demo_user"))
        assertEquals(Route.Topic(TopicType.SUBJECT, 36704), debugRoute("/subject/topic/36704"))
        assertEquals(Route.Topic(TopicType.GROUP, 1), debugRoute("bgm.tv/group/topic/1"))
        assertEquals(Route.Group("boring"), debugRoute("https://bgm.tv/group/boring"))
    }

    @Test
    fun `空输入与认不出的链接为null`() {
        assertNull(debugRoute("  "))
        assertNull(debugRoute("https://bgm.tv/group/discover"))
        assertNull(debugRoute("example.com/subject/1"))
    }
}
