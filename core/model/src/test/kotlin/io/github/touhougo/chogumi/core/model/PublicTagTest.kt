package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

class PublicTagTest {

    @Test
    fun tagFilterOf_分流映射正确() {
        assertEquals(TagFilter.Public("科幻"), tagSearchCriteria(null, "科幻", TagSource.PUBLIC)?.filters?.tags?.single())
        assertEquals(TagFilter.User("妹控"), tagSearchCriteria(null, "妹控", TagSource.USER)?.filters?.tags?.single())
    }

    @Test
    fun 详情页标签空白值返回null() {
        assertNull(tagSearchCriteria(null, "", TagSource.PUBLIC))
        assertNull(tagSearchCriteria(SubjectType.ANIME, "   ", TagSource.USER))
    }

    @Test
    fun 无类型_按全部搜_值trim后作为标签条件() {
        assertEquals(
            SearchCriteria(filters = SearchFilters(tags = listOf(TagFilter.Public("科幻")))),
            tagSearchCriteria(null, "  科幻  ", TagSource.PUBLIC),
        )
        assertEquals(
            SearchCriteria(filters = SearchFilters(tags = listOf(TagFilter.User("妹控")))),
            tagSearchCriteria(null, "妹控", TagSource.USER),
        )
    }

    @Test
    fun 分类标签带类型且词典有此值_落成维度选择() {
        val criteria = tagSearchCriteria(SubjectType.ANIME, "漫画改", TagSource.PUBLIC)!!
        assertEquals(SubjectType.ANIME, criteria.subjectType)
        assertEquals(SearchDimensionOption("漫画改", "漫画改"), criteria.filters.dimensions[SearchDimension.SOURCE]?.single())
        assertEquals(emptyList<TagFilter>(), criteria.filters.tags)
        assertTrue(normalizeSearchCriteria(criteria) is SearchValidation.Valid)
    }

    @Test
    fun 分类标签按metaTag反查_别名落到网页文字的取值() {
        val criteria = tagSearchCriteria(SubjectType.ANIME, "香港", TagSource.PUBLIC)!!
        assertEquals(SearchDimensionOption("中国香港", "香港"), criteria.filters.dimensions[SearchDimension.REGION]?.single())
    }

    @Test
    fun 词典没有的分类与标签_带类型但作为标签条件透传() {
        assertEquals(
            SearchCriteria(subjectType = SubjectType.ANIME, filters = SearchFilters(tags = listOf(TagFilter.Public("2022")))),
            tagSearchCriteria(SubjectType.ANIME, "2022", TagSource.PUBLIC),
        )
        // 标签即使与某个分类同名也不落到维度：来源按点击所在列表判定，不按名称推断
        assertEquals(
            SearchCriteria(subjectType = SubjectType.ANIME, filters = SearchFilters(tags = listOf(TagFilter.User("漫画改")))),
            tagSearchCriteria(SubjectType.ANIME, "漫画改", TagSource.USER),
        )
    }

    @Test
    fun UNKNOWN类型视为无类型() {
        assertEquals(
            SearchCriteria(filters = SearchFilters(tags = listOf(TagFilter.Public("漫画改")))),
            tagSearchCriteria(SubjectType.UNKNOWN, "漫画改", TagSource.PUBLIC),
        )
    }

    @Test
    fun 标签条件落库的类型标记是稳定名字_不随类名变() {
        assertEquals("""{"type":"public","value":"科幻"}""", Json.encodeToString(TagFilter.serializer(), TagFilter.Public("科幻")))
        assertEquals("""{"type":"user","value":"妹控"}""", Json.encodeToString(TagFilter.serializer(), TagFilter.User("妹控")))
        assertEquals(TagFilter.User("妹控"), Json.decodeFromString(TagFilter.serializer(), """{"type":"user","value":"妹控"}"""))
    }
}
