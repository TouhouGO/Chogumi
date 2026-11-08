package io.github.touhougo.chogumi.core.ui.common.navigation

import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class RouteContractTest {

    @Test
    fun `子页面与带参路由均不是顶级目的地`() {
        val tabs = TopLevelDestination.routes
        assertFalse(tabs.any { it is Route.Search })
        assertFalse(Route.Settings in tabs)
        assertFalse(Route.Calendar in tabs)
        assertFalse(Route.Login in tabs)
        assertFalse(Route.Feed in tabs)
        assertFalse(Route.UserProfile("fixture_user") in tabs)
    }

    @Test
    fun `详情路由序列化往返参数不丢失`() {
        val json = Json
        val encoded = json.encodeToString(Route.SubjectDetails.serializer(), Route.SubjectDetails(8))
        assertEquals(Route.SubjectDetails(8), json.decodeFromString(Route.SubjectDetails.serializer(), encoded))
    }

    @Test
    fun `角色人物路由序列化往返类型与ID不丢失`() {
        for (type in MonoType.entries) {
            val details = Route.MonoDetails(type, 2481)
            assertEquals(details, Json.decodeFromString(Route.MonoDetails.serializer(), Json.encodeToString(Route.MonoDetails.serializer(), details)))
            val works = Route.MonoWorks(type, 6734)
            assertEquals(works, Json.decodeFromString(Route.MonoWorks.serializer(), Json.encodeToString(Route.MonoWorks.serializer(), works)))
            val relations = Route.MonoRelations(type, 1)
            assertEquals(relations, Json.decodeFromString(Route.MonoRelations.serializer(), Json.encodeToString(Route.MonoRelations.serializer(), relations)))
        }
    }

    @Test
    fun `人物出演角色路由序列化往返参数不丢失`() {
        val route = Route.PersonCharacters(2481)
        val encoded = Json.encodeToString(Route.PersonCharacters.serializer(), route)
        assertEquals(route, Json.decodeFromString(Route.PersonCharacters.serializer(), encoded))
    }

    /**
     * 搜索路由要能整组条件往返：多选维度、自由词、年份范围、排序、类型、标题一个都不能丢。
     * 这条覆盖的是「探索页带着条件进搜索页」——条件丢掉或类型塌回 null 时，界面会静默变成空搜索。
     */
    @Test
    fun `搜索路由序列化往返整组条件不丢失`() {
        val json = Json
        val routes = listOf(
            Route.Search(),
            Route.Search(criteria = null, openFilters = true),
            Route.Search(criteria = SearchCriteria(subjectType = SubjectType.ANIME), title = "近期热门"),
            Route.Search(
                criteria = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    sort = SearchSort.RANK,
                    filters = SearchFilters(
                        dimensions = mapOf(
                            SearchDimension.GENRE to listOf(
                                SearchDimensionOption("奇幻", "奇幻"),
                                SearchDimensionOption("战斗", "战斗"),
                            ),
                        ),
                        tags = listOf(TagFilter.User("异世界"), TagFilter.Public("TV")),
                        airtime = SearchAirtime(startYear = 2015, endYear = 2020),
                    ),
                ),
                title = "奇幻世界里的战斗",
            ),
        )
        for (route in routes) {
            val encoded = json.encodeToString(Route.Search.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.Search.serializer(), encoded))
        }
    }

    @Test
    fun `搜索路由损坏的序列化数据解码失败`() {
        val json = Json
        assertFailsWith<SerializationException> {
            json.decodeFromString(
                Route.Search.serializer(),
                """{"criteria":{"subjectType":"ANIME","sort":"NOPE"}}""",
            )
        }
    }
}
