package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFiltersTest {

    private fun errorsOf(result: SearchValidation<*>): List<SearchValidationError> =
        (result as SearchValidation.Invalid).errors

    // --- 标签规范化 ---

    @Test
    fun normalizeSearchTags_双来源不合并() {
        assertEquals(
            SearchValidation.Valid(listOf(TagFilter.Public("科幻"), TagFilter.User("科幻"))),
            normalizeSearchTags(
                listOf(TagFilter.User("科幻"), TagFilter.Public(" 科幻 "), TagFilter.Public("科幻")),
            ),
        )
    }

    @Test
    fun normalizeSearchTags_顺序规范化后完全相等() {
        val a = normalizeSearchTags(listOf(TagFilter.Public("科幻"), TagFilter.User("原创")))
        val b = normalizeSearchTags(listOf(TagFilter.User("原创"), TagFilter.Public("科幻")))
        assertEquals(a, b)
    }

    @Test
    fun normalizeSearchTags_外部可变集合隔离() {
        val input = mutableListOf(TagFilter.Public("科幻"), TagFilter.User("原创"))
        val result = normalizeSearchTags(input) as SearchValidation.Valid
        input.clear()
        assertEquals(listOf(TagFilter.Public("科幻"), TagFilter.User("原创")), result.value)
    }

    @Test
    fun normalizeSearchTags_空白标签拒绝() {
        val errors = errorsOf(normalizeSearchTags(listOf(TagFilter.User("  "))))
        assertTrue(errors.contains(SearchValidationError(SearchInputError.EMPTY_TAG)))
    }

    @Test
    fun normalizeSearchTags_不支持排除() {
        val errors = errorsOf(normalizeSearchTags(listOf(TagFilter.Public(" -科幻 "))))
        assertTrue(errors.contains(SearchValidationError(SearchInputError.EXCLUDED_PUBLIC_TAG)))
    }

    @Test
    fun normalizeSearchTags_大小写保留为不同值() {
        assertEquals(
            SearchValidation.Valid(listOf(TagFilter.User("GAL"), TagFilter.User("gal"))),
            normalizeSearchTags(listOf(TagFilter.User("GAL"), TagFilter.User("gal"))),
        )
    }

    @Test
    fun normalizeSearchTags_清单外标签保留() {
        assertEquals(
            SearchValidation.Valid(listOf(TagFilter.Public("清单外样本"))),
            normalizeSearchTags(listOf(TagFilter.Public("清单外样本"))),
        )
    }

    @Test
    fun normalizeSearchTags_同来源同值去重() {
        assertEquals(
            SearchValidation.Valid(listOf(TagFilter.User("原创"))),
            normalizeSearchTags(listOf(TagFilter.User("原创"), TagFilter.User("原创"))),
        )
    }

    // --- 时间条件 ---

    @Test
    fun validateSearchAirtime_年份与月份边界() {
        assertEquals(SearchValidation.Valid(null), validateSearchAirtime(null))
        val ok = listOf(
            SearchAirtime(2024, 2024),
            SearchAirtime(2024, 2024, 1),
            SearchAirtime(2024, 2024, 12),
            SearchAirtime(SEARCH_YEAR_MIN, SEARCH_YEAR_MIN),
            SearchAirtime(SEARCH_YEAR_MAX, SEARCH_YEAR_MAX),
        )
        for (time in ok) {
            assertEquals("$time", SearchValidation.Valid(time), validateSearchAirtime(time))
        }
        val bad = listOf(
            SearchAirtime(0, 0),
            SearchAirtime(SEARCH_YEAR_MAX + 1, SEARCH_YEAR_MAX + 1),
            SearchAirtime(2024, 2024, 0),
            SearchAirtime(2024, 2024, 13),
        )
        for (time in bad) {
            assertEquals("$time", listOf(SearchValidationError(SearchInputError.OUT_OF_RANGE)), errorsOf(validateSearchAirtime(time)))
        }
    }

    @Test
    fun validateSearchAirtime_两端不得都空_起不得大于止_月份只在同一年合法() {
        // 两端都空是非法状态（「不限」用 filters.airtime 为 null 表示）
        assertEquals(
            listOf(SearchValidationError(SearchInputError.OUT_OF_RANGE)),
            errorsOf(validateSearchAirtime(SearchAirtime(null, null))),
        )
        // 起 > 止
        assertEquals(
            listOf(SearchValidationError(SearchInputError.OUT_OF_RANGE)),
            errorsOf(validateSearchAirtime(SearchAirtime(2020, 2015))),
        )
        // 起 == 止合法（单独一年）
        assertEquals(SearchValidation.Valid(SearchAirtime(2020, 2020)), validateSearchAirtime(SearchAirtime(2020, 2020)))
        // 月份只在起止同一年时合法
        assertEquals(SearchValidation.Valid(SearchAirtime(2020, 2020, 5)), validateSearchAirtime(SearchAirtime(2020, 2020, 5)))
        assertEquals(
            listOf(SearchValidationError(SearchInputError.OUT_OF_RANGE)),
            errorsOf(validateSearchAirtime(SearchAirtime(2020, 2021, 5))),
        )
        assertEquals(
            listOf(SearchValidationError(SearchInputError.OUT_OF_RANGE)),
            errorsOf(validateSearchAirtime(SearchAirtime(null, 2020, 5))),
        )
        // 一端不限（范围）
        assertEquals(SearchValidation.Valid(SearchAirtime(null, 1999)), validateSearchAirtime(SearchAirtime(null, 1999)))
        assertEquals(SearchValidation.Valid(SearchAirtime(2015, null)), validateSearchAirtime(SearchAirtime(2015, null)))
    }

    @Test
    fun isSingleYear与isRange的判定() {
        assertFalse((null as SearchAirtime?).isSingleYear())
        assertFalse((null as SearchAirtime?).isRange())
        assertTrue(SearchAirtime(2020, 2020).isSingleYear())
        assertFalse(SearchAirtime(2020, 2020).isRange())
        assertFalse(SearchAirtime(2015, 2020).isSingleYear())
        assertTrue(SearchAirtime(2015, 2020).isRange())
        assertTrue(SearchAirtime(null, 2020).isRange())
        assertTrue(SearchAirtime(2020, null).isRange())
    }

    @Test
    fun withYears_两端都空为不限_同年保留月份_其余清掉月份() {
        assertNullValue(SearchAirtime(2020, 2020, 5).withYears(null, null))
        // 仍是同一年且年份不变：保留月份
        assertEquals(SearchAirtime(2020, 2020, 5), SearchAirtime(2020, 2020, 5).withYears(2020, 2020))
        // 年份变了：清掉月份
        assertEquals(SearchAirtime(2021, 2021), SearchAirtime(2020, 2020, 5).withYears(2021, 2021))
        // 变成范围：清掉月份
        assertEquals(SearchAirtime(2015, 2020), SearchAirtime(2020, 2020, 5).withYears(2015, 2020))
        // 从无到有
        assertEquals(SearchAirtime(2015, 2020), null.withYears(2015, 2020))
    }

    private fun assertNullValue(value: SearchAirtime?) {
        assertEquals(null, value)
    }

    // --- filters 聚合 ---

    @Test
    fun normalizeSearchFilters_汇总标签与时间错误() {
        val filters = SearchFilters(
            tags = listOf(TagFilter.User("  ")),
            airtime = SearchAirtime(0),
        )
        val errors = errorsOf(normalizeSearchFilters(filters))
        assertTrue(errors.contains(SearchValidationError(SearchInputError.EMPTY_TAG)))
        assertTrue(errors.contains(SearchValidationError(SearchInputError.OUT_OF_RANGE)))
    }

    @Test
    fun hasAdditionalFilters_维度标签时间任一即算() {
        assertFalse(SearchFilters().hasAdditionalFilters())
        assertTrue(
            SearchFilters(dimensions = mapOf(SearchDimension.CATEGORY to listOf(SearchDimensionOption("TV", "TV"))))
                .hasAdditionalFilters(),
        )
        assertTrue(SearchFilters(tags = listOf(TagFilter.User("原创"))).hasAdditionalFilters())
        assertTrue(SearchFilters(airtime = SearchAirtime(2010)).hasAdditionalFilters())
    }

    // --- 标签来源与开关 ---

    @Test
    fun tagFilter_source与stableKey() {
        assertEquals(TagSource.PUBLIC, TagFilter.Public("科幻").source())
        assertEquals(TagSource.USER, TagFilter.User("科幻").source())
        assertEquals("PUBLIC:科幻", TagFilter.Public("科幻").stableKey())
        assertEquals("USER:科幻", TagFilter.User("科幻").stableKey())
    }
}
