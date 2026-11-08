package io.github.touhougo.chogumi.shell

import io.github.touhougo.chogumi.core.model.IndexCategory
import io.github.touhougo.chogumi.core.model.IndexTarget
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.TopicType
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals

/** 目录目标到页面的 Route 只在 AppEntries 接线层映射。 */
class IndexTargetRouteMappingTest {

    @Test
    fun `七类目录目标各自映射到页面`() {
        assertEquals(Route.SubjectDetails(1), indexTargetRoute(IndexTarget(IndexCategory.SUBJECT, 1)))
        assertEquals(Route.MonoDetails(MonoType.CHARACTER, 2), indexTargetRoute(IndexTarget(IndexCategory.CHARACTER, 2)))
        assertEquals(Route.MonoDetails(MonoType.PERSON, 3), indexTargetRoute(IndexTarget(IndexCategory.PERSON, 3)))
        assertEquals(Route.Episode(4), indexTargetRoute(IndexTarget(IndexCategory.EPISODE, 4)))
        assertEquals(Route.BlogDetails(5), indexTargetRoute(IndexTarget(IndexCategory.BLOG, 5)))
        assertEquals(Route.Topic(TopicType.GROUP, 6), indexTargetRoute(IndexTarget(IndexCategory.GROUP_TOPIC, 6)))
        assertEquals(Route.Topic(TopicType.SUBJECT, 7), indexTargetRoute(IndexTarget(IndexCategory.SUBJECT_TOPIC, 7)))
    }
}
