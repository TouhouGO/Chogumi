package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一组条件的判等与命名：组合键的唯一判等依据、默认名字的连接规则。
 *
 * 组合键必须**与取值顺序无关**（同一组条件从不同路径摆进来时，Map/List 里的顺序可能不同，
 * 但那还是同一组条件），又必须**认得出生效排序的变化**（排序是条件的一部分）。
 */
class SearchCriteriaPresetKeyTest {

    private fun option(label: String, metaTag: String = label) = SearchDimensionOption(label, metaTag)

    private fun anime(
        dimensions: Map<SearchDimension, List<SearchDimensionOption>> = emptyMap(),
        tags: List<TagFilter> = emptyList(),
        airtime: SearchAirtime? = null,
        sort: SearchSort = SearchSort.COLLECTS,
        query: String = "",
    ) = SearchCriteria(
        query = query,
        subjectType = SubjectType.ANIME,
        sort = sort,
        filters = SearchFilters(dimensions = dimensions, tags = tags, airtime = airtime),
    )

    @Test
    fun `维度取值顺序不同_组合键相同`() {
        val a = anime(dimensions = mapOf(SearchDimension.GENRE to listOf(option("奇幻"), option("战斗"))))
        val b = anime(dimensions = mapOf(SearchDimension.GENRE to listOf(option("战斗"), option("奇幻"))))
        assertEquals(a.presetKey(), b.presetKey())
    }

    @Test
    fun `维度书写顺序不同_组合键相同`() {
        val a = anime(
            dimensions = mapOf(
                SearchDimension.GENRE to listOf(option("战斗")),
                SearchDimension.REGION to listOf(option("日本")),
            ),
        )
        val b = anime(
            dimensions = mapOf(
                SearchDimension.REGION to listOf(option("日本")),
                SearchDimension.GENRE to listOf(option("战斗")),
            ),
        )
        assertEquals(a.presetKey(), b.presetKey())
    }

    @Test
    fun `标签顺序不同_组合键相同_来源不同则不同`() {
        val a = anime(tags = listOf(TagFilter.User("异世界"), TagFilter.Public("TV")))
        val b = anime(tags = listOf(TagFilter.Public("TV"), TagFilter.User("异世界")))
        assertEquals(a.presetKey(), b.presetKey())

        // 同名不同来源是两个不同的条件（一个进 meta_tags、一个进 tag）
        val publicTv = anime(tags = listOf(TagFilter.Public("TV")))
        val userTv = anime(tags = listOf(TagFilter.User("TV")))
        assertNotEquals(publicTv.presetKey(), userTv.presetKey())
    }

    @Test
    fun `排序不同_组合键不同`() {
        assertNotEquals(
            anime(sort = SearchSort.COLLECTS).presetKey(),
            anime(sort = SearchSort.RANK).presetKey(),
        )
    }

    @Test
    fun `类型不同_组合键不同`() {
        assertNotEquals(
            anime().presetKey(),
            SearchCriteria(subjectType = SubjectType.BOOK).presetKey(),
        )
    }

    @Test
    fun `时间不同_组合键不同_月份也要算进去`() {
        assertNotEquals(
            anime(airtime = SearchAirtime(2019, 2019)).presetKey(),
            anime(airtime = SearchAirtime(2015, 2020)).presetKey(),
        )
        assertNotEquals(
            anime(airtime = SearchAirtime(2019, 2019)).presetKey(),
            anime(airtime = SearchAirtime(2019, 2019, 5)).presetKey(),
        )
    }

    @Test
    fun `关键词不参与组合键`() {
        val bare = anime(dimensions = mapOf(SearchDimension.GENRE to listOf(option("奇幻"))))
        val typed = bare.copy(query = "frieren")
        assertEquals(bare.presetKey(), typed.presetKey())
    }

    /**
     * 关键词会把生效排序打回「收藏」（有关键词时 availableSorts 为空 → effectiveSort = COLLECTS）。
     * 组合键取生效排序，为了不受关键词影响，必须先清掉关键词再算——否则同一个组合会在
     * 输入框里打了字之后变成另一个 key，图钉就掉下来了。
     */
    @Test
    fun `关键词不影响组合键_含排序回落`() {
        val ranked = anime(
            dimensions = mapOf(SearchDimension.GENRE to listOf(option("奇幻"))),
            sort = SearchSort.TRENDS,
        )
        assertEquals(ranked.presetKey(), ranked.copy(query = "eva").presetKey())
    }

    @Test
    fun `标签值含分隔符也不会撞键`() {
        // 手拼分隔符的实现会把这两条编成同一个键；JSON 转义不会。
        val one = anime(tags = listOf(TagFilter.User("a、b"), TagFilter.User("c")))
        val two = anime(tags = listOf(TagFilter.User("a"), TagFilter.User("b、c")))
        assertNotEquals(one.presetKey(), two.presetKey())
    }

    @Test
    fun `默认名字_三个以内用乘号连起来`() {
        assertEquals(CombinationTitle("形式：TV", more = false), combinationTitle(listOf("形式：TV")))
        assertEquals(CombinationTitle("形式：TV × 题材：奇幻", more = false), combinationTitle(listOf("形式：TV", "题材：奇幻")))
        assertEquals(
            CombinationTitle("形式：TV × 题材：奇幻 × 标签：异世界", more = false),
            combinationTitle(listOf("形式：TV", "题材：奇幻", "标签：异世界")),
        )
    }

    @Test
    fun `默认名字_超过三个只取前三个并标出还有更多`() {
        assertEquals(
            CombinationTitle("形式：TV × 题材：奇幻 × 标签：异世界", more = true),
            combinationTitle(listOf("形式：TV", "题材：奇幻", "标签：异世界", "时间：1990 年代")),
        )
    }

    @Test
    fun `可固定_关键词为空、具体类型且至少一个条件`() {
        assertTrue(anime(dimensions = mapOf(SearchDimension.GENRE to listOf(option("奇幻")))).isPinnable())
        assertTrue(anime(tags = listOf(TagFilter.User("异世界"))).isPinnable())
        assertTrue(anime(airtime = SearchAirtime(2019, 2019)).isPinnable())
        // 只有类型或只有排序：不算条件，不能固定
        assertFalse(anime().isPinnable())
        assertFalse(anime(sort = SearchSort.RANK).isPinnable())
        // 有关键词：不能固定（关键词不进组合键，固定出来会对不上）
        assertFalse(anime(query = "eva", dimensions = mapOf(SearchDimension.GENRE to listOf(option("奇幻")))).isPinnable())
        // 「全部」类型：没有探索页可放
        assertFalse(anime(dimensions = mapOf(SearchDimension.GENRE to listOf(option("奇幻")))).copy(subjectType = null).isPinnable())
    }
}
