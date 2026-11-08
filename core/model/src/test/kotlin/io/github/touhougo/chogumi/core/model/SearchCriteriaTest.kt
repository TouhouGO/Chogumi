package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCriteriaTest {

    // --- isActive ---

    @Test
    fun isActive_空态为假() {
        assertFalse(SearchCriteria().isActive())
    }

    @Test
    fun isActive_类型筛选关键词任一为真_排序不算() {
        assertTrue(SearchCriteria(subjectType = SubjectType.ANIME).isActive())
        assertTrue(SearchCriteria(filters = SearchFilters(airtime = SearchAirtime(2010, 2010))).isActive())
        assertTrue(SearchCriteria(query = "eva").isActive())
        assertFalse(SearchCriteria(sort = SearchSort.RANK).isActive())
    }

    @Test
    fun isActive_UNKNOWN不算具体类型() {
        assertFalse(SearchCriteria(subjectType = SubjectType.UNKNOWN).isActive())
    }

    // --- 接口选择 / 可用排序 ---

    private val anime = SearchCriteria(subjectType = SubjectType.ANIME)
    private val singleUserTag = SearchFilters(tags = listOf(TagFilter.User("百合")))
    private val multiUserTags = SearchFilters(tags = listOf(TagFilter.User("百合"), TagFilter.User("日常")))
    private val mixedTagAndCategory = SearchFilters(tags = listOf(TagFilter.User("百合"), TagFilter.Public("TV")))

    @Test
    fun isTagBrowse_无关键词具体类型恰好单用户标签无分类走标签浏览_年月允许() {
        assertTrue(anime.copy(filters = singleUserTag).isTagBrowse())
        assertTrue(anime.copy(filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子")), airtime = SearchAirtime(2003, 2003))).isTagBrowse())
        // 有关键词
        assertFalse(anime.copy(query = "eva", filters = singleUserTag).isTagBrowse())
        // 无类型或 UNKNOWN
        assertFalse(SearchCriteria(filters = singleUserTag).isTagBrowse())
        assertFalse(SearchCriteria(subjectType = SubjectType.UNKNOWN, filters = singleUserTag).isTagBrowse())
        // 多个用户标签
        assertFalse(anime.copy(filters = multiUserTags).isTagBrowse())
        // 用户标签与分类混用（公共标签或维度）
        assertFalse(anime.copy(filters = mixedTagAndCategory).isTagBrowse())
    }

    @Test
    fun 年份范围不算可浏览_标签浏览与分类浏览都排除() {
        // 范围只有搜索接口能查，p1 分类浏览只收 year/month
        val rangedTag = SearchFilters(tags = listOf(TagFilter.User("菅野洋子")), airtime = SearchAirtime(2015, 2020))
        assertFalse(anime.copy(filters = rangedTag).isTagBrowse())
        assertFalse(anime.copy(filters = rangedTag).isCategoryBrowse())
        // 一端不限也算范围
        assertFalse(anime.copy(filters = SearchFilters(airtime = SearchAirtime(null, 1999))).isCategoryBrowse())
        assertFalse(anime.copy(filters = SearchFilters(airtime = SearchAirtime(2015, null))).isCategoryBrowse())
        // 单独一年仍可浏览
        assertTrue(anime.copy(filters = SearchFilters(airtime = SearchAirtime(2015, 2015))).isCategoryBrowse())
        // 范围下排序只剩排名、收藏，且走带时间条件的搜索接口（v0）
        val ranged = anime.copy(sort = SearchSort.TRENDS, filters = SearchFilters(airtime = SearchAirtime(2015, 2020)))
        assertEquals(setOf(SearchSort.RANK, SearchSort.COLLECTS), ranged.availableSorts())
        assertEquals(SearchSource.DATED_SEARCH, ranged.searchSource())
    }

    @Test
    fun searchSource_分浏览与关键词的全枚举分流表() {
        // 分类浏览：排名/标注数走搜索，热度/日期/名称留 p1 浏览
        val category = mapOf(
            SearchSort.RANK to SearchSource.SEARCH,
            SearchSort.TRENDS to SearchSource.BROWSE,
            SearchSort.COLLECTS to SearchSource.SEARCH,
            SearchSort.DATE to SearchSource.BROWSE,
            SearchSort.TITLE to SearchSource.BROWSE,
        )
        for ((sort, source) in category) {
            assertEquals("分类浏览 $sort", source, anime.copy(sort = sort).searchSource())
        }

        // 标签浏览：可用集合无热度；热度回落标注数 → 同样走搜索
        val tag = mapOf(
            SearchSort.RANK to SearchSource.SEARCH,
            SearchSort.COLLECTS to SearchSource.SEARCH,
            SearchSort.DATE to SearchSource.BROWSE,
            SearchSort.TITLE to SearchSource.BROWSE,
            SearchSort.TRENDS to SearchSource.SEARCH,
        )
        for ((sort, source) in tag) {
            assertEquals(
                "标签浏览 $sort",
                source,
                anime.copy(sort = sort, filters = singleUserTag).searchSource(),
            )
        }

        // 关键词搜索：排序一律不生效（全部回落标注数），恒走搜索
        for (sort in SearchSort.entries) {
            assertEquals("关键词 $sort", SearchSource.SEARCH, anime.copy(query = "eva", sort = sort).searchSource())
        }

        // 不可浏览形态：无论什么排序都走搜索
        for (sort in SearchSort.entries) {
            assertEquals(SearchSource.SEARCH, anime.copy(sort = sort, filters = multiUserTags).searchSource())
            assertEquals(SearchSource.SEARCH, anime.copy(sort = sort, filters = mixedTagAndCategory).searchSource())
            assertEquals(SearchSource.SEARCH, SearchCriteria(filters = singleUserTag, sort = sort).searchSource())
            assertEquals(
                SearchSource.SEARCH,
                SearchCriteria(subjectType = SubjectType.UNKNOWN, filters = singleUserTag, sort = sort).searchSource(),
            )
        }

        // 标签浏览支持年月，年月不改变浏览的分流；走搜索时带了时间条件就走 v0（p1 搜索的日期条件服务端坏了）
        assertEquals(
            SearchSource.BROWSE,
            anime.copy(
                sort = SearchSort.DATE,
                filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子")), airtime = SearchAirtime(2003, 2003)),
            ).searchSource(),
        )
        assertEquals(
            SearchSource.DATED_SEARCH,
            anime.copy(filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子")), airtime = SearchAirtime(2003, 2003))).searchSource(),
        )
        assertEquals(SearchSource.DATED_SEARCH, SearchCriteria(filters = SearchFilters(airtime = SearchAirtime(2024, 2024))).searchSource())
        assertEquals(SearchSource.DATED_SEARCH, anime.copy(query = "eva", filters = SearchFilters(airtime = SearchAirtime(null, 1999))).searchSource())
    }

    @Test
    fun 词典外的分类不走分类浏览_只有排名与收藏且走搜索() {
        val galgame = tagSearchCriteria(SubjectType.GAME, "Galgame", TagSource.PUBLIC)!!
        assertFalse(galgame.isCategoryBrowse())
        assertEquals(setOf(SearchSort.RANK, SearchSort.COLLECTS), galgame.availableSorts())
        assertEquals(SearchSource.SEARCH, galgame.copy(sort = SearchSort.TRENDS).searchSource())

        val rpg = tagSearchCriteria(SubjectType.GAME, "RPG", TagSource.PUBLIC)!!
        assertTrue("词典里的分类照旧分类浏览", rpg.isCategoryBrowse())
        assertEquals(SearchSource.BROWSE, rpg.copy(sort = SearchSort.TRENDS).searchSource())
    }

    @Test
    fun availableSorts_空态与关键词无排序_分类浏览五种_标签浏览四种无热度_其余排名与收藏() {
        assertEquals(emptySet<SearchSort>(), SearchCriteria().availableSorts())
        assertEquals(emptySet<SearchSort>(), SearchCriteria(sort = SearchSort.RANK).availableSorts())
        assertEquals(emptySet<SearchSort>(), anime.copy(query = "eva").availableSorts())
        // 分类浏览：五种
        assertEquals(SearchSort.entries.toSet(), anime.availableSorts())
        // 单用户标签浏览：排名、收藏（标注数）、日期、名称（无热度）
        assertEquals(
            setOf(SearchSort.RANK, SearchSort.COLLECTS, SearchSort.DATE, SearchSort.TITLE),
            anime.copy(filters = singleUserTag).availableSorts(),
        )
        // 标签+年月：同为标签浏览四种
        assertEquals(
            setOf(SearchSort.RANK, SearchSort.COLLECTS, SearchSort.DATE, SearchSort.TITLE),
            anime.copy(filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子")), airtime = SearchAirtime(2003, 2003))).availableSorts(),
        )
        // 多标签、标签+分类、未选类型：仅排名与收藏
        assertEquals(setOf(SearchSort.RANK, SearchSort.COLLECTS), anime.copy(filters = multiUserTags).availableSorts())
        assertEquals(setOf(SearchSort.RANK, SearchSort.COLLECTS), anime.copy(filters = mixedTagAndCategory).availableSorts())
        assertEquals(setOf(SearchSort.RANK, SearchSort.COLLECTS), SearchCriteria(filters = singleUserTag).availableSorts())
        // 可选集合对用户不因分流而变：分类浏览恒五种、标签浏览恒四种，与所选排序无关
        for (sort in SearchSort.entries) {
            assertEquals("分类浏览 $sort", SearchSort.entries.toSet(), anime.copy(sort = sort).availableSorts())
            assertEquals(
                "标签浏览 $sort",
                setOf(SearchSort.RANK, SearchSort.COLLECTS, SearchSort.DATE, SearchSort.TITLE),
                anime.copy(sort = sort, filters = singleUserTag).availableSorts(),
            )
        }
    }

    @Test
    fun effectiveSort_可用则用所选_不可用退回收藏且选择本身保留() {
        val trends = anime.copy(sort = SearchSort.TRENDS)
        assertEquals(SearchSort.TRENDS, trends.effectiveSort())

        // 标签浏览不支持热度：退回收藏，但保留用户的 sort=TRENDS
        val withTag = trends.copy(filters = singleUserTag)
        assertEquals(SearchSort.COLLECTS, withTag.effectiveSort())
        assertEquals(SearchSort.TRENDS, withTag.sort)

        // 标签浏览支持日期与名称
        assertEquals(SearchSort.DATE, anime.copy(sort = SearchSort.DATE, filters = singleUserTag).effectiveSort())
        assertEquals(SearchSort.TITLE, anime.copy(sort = SearchSort.TITLE, filters = singleUserTag).effectiveSort())
        assertEquals(SearchSort.RANK, anime.copy(sort = SearchSort.RANK, filters = singleUserTag).effectiveSort())

        // 多用户标签不支持日期：退回收藏
        assertEquals(SearchSort.COLLECTS, anime.copy(sort = SearchSort.DATE, filters = multiUserTags).effectiveSort())

        // 有关键词：一律收藏
        assertEquals(SearchSort.COLLECTS, anime.copy(query = "eva", sort = SearchSort.RANK).effectiveSort())

        // 生效排序与分流一致：分类浏览选热度 → 热度，标签浏览选热度 → 回落收藏
        assertEquals(SearchSort.TRENDS, anime.copy(sort = SearchSort.TRENDS).effectiveSort())
        assertEquals(SearchSort.COLLECTS, anime.copy(sort = SearchSort.TRENDS, filters = singleUserTag).effectiveSort())
    }

    @Test
    fun 默认排序是收藏() {
        assertEquals(SearchSort.COLLECTS, SearchCriteria().sort)
        assertEquals(SearchSort.COLLECTS, anime.effectiveSort())
    }


    // --- normalize ---

    @Test
    fun normalize_trim关键词并规范化筛选() {
        val result = normalizeSearchCriteria(
            SearchCriteria(
                query = "  eva  ",
                filters = SearchFilters(tags = listOf(TagFilter.Public(" 科幻 "), TagFilter.Public("科幻"))),
            ),
        )
        assertEquals(
            SearchValidation.Valid(
                SearchCriteria(query = "eva", filters = SearchFilters(tags = listOf(TagFilter.Public("科幻")))),
            ),
            result,
        )
    }

    @Test
    fun normalize_UNKNOWN返回Invalid而非type0() {
        val result = normalizeSearchCriteria(SearchCriteria(subjectType = SubjectType.UNKNOWN))
        assertEquals(
            SearchValidation.Invalid(listOf(SearchValidationError(SearchInputError.UNKNOWN_TYPE))),
            result,
        )
    }

    @Test
    fun normalize_汇总筛选错误() {
        val result = normalizeSearchCriteria(SearchCriteria(filters = SearchFilters(airtime = SearchAirtime(0, 0))))
        assertTrue(result is SearchValidation.Invalid)
        assertTrue((result as SearchValidation.Invalid).errors.contains(
            SearchValidationError(SearchInputError.OUT_OF_RANGE),
        ))
    }

    // --- 维度 ---

    private fun tv() = searchDimensionsFor(SubjectType.ANIME)
        .single { it.dimension == SearchDimension.CATEGORY }.options.single { it.label == "TV" }

    @Test
    fun normalize_维度选择合法时通过且计入附加筛选() {
        val criteria = SearchCriteria(
            subjectType = SubjectType.ANIME,
            filters = SearchFilters(dimensions = mapOf(SearchDimension.CATEGORY to listOf(tv()))),
        )
        assertTrue(normalizeSearchCriteria(criteria) is SearchValidation.Valid)
        assertTrue(criteria.filters.hasAdditionalFilters())
    }

    @Test
    fun normalize_维度选择不属于当前类型时拒绝_不静默丢弃() {
        val expected = SearchValidation.Invalid(
            listOf(SearchValidationError(SearchInputError.UNKNOWN_DIMENSION_VALUE)),
        )
        val dims = mapOf(SearchDimension.CATEGORY to listOf(tv()))
        assertEquals(expected, normalizeSearchCriteria(SearchCriteria(subjectType = SubjectType.BOOK, filters = SearchFilters(dimensions = dims))))
        assertEquals(expected, normalizeSearchCriteria(SearchCriteria(subjectType = null, filters = SearchFilters(dimensions = dims))))
    }

    @Test
    fun concreteOrNull_全部与无效类型都视为没有类型() {
        assertEquals(null, (null as SubjectType?).concreteOrNull())
        assertEquals(null, SubjectType.UNKNOWN.concreteOrNull())
        for (type in SubjectType.entries - SubjectType.UNKNOWN) assertEquals(type, type.concreteOrNull())
    }
}
