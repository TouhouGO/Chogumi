package io.github.touhougo.chogumi.core.data.repository

import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SearchSource
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.availableSorts
import io.github.touhougo.chogumi.core.model.effectiveSort
import io.github.touhougo.chogumi.core.model.searchDimensionsFor
import io.github.touhougo.chogumi.core.model.searchSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestMapperTest {

    private val json = Json { encodeDefaults = true }

    private fun SearchCriteria.dto() = toSearchRequestDto(nsfwEnabled = true)

    private fun opt(type: SubjectType, dimension: SearchDimension, label: String) =
        searchDimensionsFor(type).single { it.dimension == dimension }.options.single { it.label == label }

    private val anime = SearchCriteria(subjectType = SubjectType.ANIME)

    // --- 搜索接口（v0） ---

    @Test
    fun 搜索_完整组合_literal预期() {
        val dto = SearchCriteria(
            query = "机动",
            subjectType = SubjectType.ANIME,
            sort = SearchSort.RANK,
            filters = SearchFilters(
                tags = listOf(TagFilter.Public("科幻"), TagFilter.User("原创")),
                airtime = SearchAirtime(2010, 2010),
            ),
        ).dto()

        assertEquals("机动", dto.keyword)
        // 有关键词：排序一律收藏（heat），所选「排名」不生效，也不带 rank 下限
        assertEquals("heat", dto.sort)
        assertEquals(listOf(2), dto.filter?.type)
        assertEquals(listOf("科幻"), dto.filter?.metaTags)
        assertEquals(listOf("原创"), dto.filter?.tag)
        assertEquals(listOf(">=2010-01-01", "<=2010-12-31"), dto.filter?.airDate)
        assertNull(dto.filter?.rank)
        assertNull(dto.filter?.rating)
        assertNull(dto.filter?.ratingCount)
        assertNull(dto.filter?.nsfw)
    }

    @Test
    fun 搜索_月份换算成该月首尾_闰年二月() {
        val filter = { airtime: SearchAirtime -> SearchCriteria(filters = SearchFilters(airtime = airtime)).dto().filter?.airDate }
        assertEquals(listOf(">=2024-10-01", "<=2024-10-31"), filter(SearchAirtime(2024, 2024, 10)))
        assertEquals(listOf(">=2024-02-01", "<=2024-02-29"), filter(SearchAirtime(2024, 2024, 2)))
        assertEquals(listOf(">=2023-02-01", "<=2023-02-28"), filter(SearchAirtime(2023, 2023, 2)))
        assertEquals(listOf(">=0998-01-01", "<=0998-12-31"), filter(SearchAirtime(998, 998)))
    }

    @Test
    fun 搜索_范围只写有值的一端_两端都给就是闭区间() {
        val filter = { airtime: SearchAirtime -> SearchCriteria(filters = SearchFilters(airtime = airtime)).dto().filter?.airDate }
        // 只有起始年
        assertEquals(listOf(">=2015-01-01"), filter(SearchAirtime(2015, null)))
        // 只有结束年
        assertEquals(listOf("<=1999-12-31"), filter(SearchAirtime(null, 1999)))
        // 两端
        assertEquals(listOf(">=2015-01-01", "<=2020-12-31"), filter(SearchAirtime(2015, 2020)))
        // 早于 1000 年按 4 位补零
        assertEquals(listOf("<=0998-12-31"), filter(SearchAirtime(null, 998)))
    }

    @Test
    fun 两个搜索接口的请求体是同一份条件_只换字段名() {
        val cases = listOf(
            anime.copy(query = "eva"),
            anime.copy(sort = SearchSort.RANK, filters = SearchFilters(tags = listOf(TagFilter.User("百合"), TagFilter.User("日常")))),
            anime.copy(filters = SearchFilters(tags = listOf(TagFilter.Public("科幻"), TagFilter.User("机战")))),
            SearchCriteria(filters = SearchFilters(airtime = SearchAirtime(2015, 2020))),
        )
        for (criteria in cases) for (nsfw in listOf(true, false)) {
            val v0 = criteria.toSearchRequestDto(nsfwEnabled = nsfw)
            val p1 = criteria.toP1SearchRequestDto(nsfwEnabled = nsfw)
            assertEquals("$criteria", v0.keyword, p1.keyword)
            assertEquals("$criteria", v0.sort, p1.sort)
            assertEquals("$criteria", v0.filter?.type, p1.filter?.type)
            assertEquals("$criteria", v0.filter?.tag, p1.filter?.tags)
            assertEquals("$criteria", v0.filter?.metaTags, p1.filter?.metaTags)
            assertEquals("$criteria", v0.filter?.airDate, p1.filter?.date)
            assertEquals("$criteria", v0.filter?.rank, p1.filter?.rank)
            assertEquals("$criteria", v0.filter?.nsfw, p1.filter?.nsfw)
        }
    }

    @Test
    fun 搜索_带范围时排序只剩排名收藏且走搜索接口() {
        val range = SearchCriteria(
            subjectType = SubjectType.ANIME,
            sort = SearchSort.TRENDS,
            filters = SearchFilters(airtime = SearchAirtime(2015, 2020)),
        )
        // 分类浏览被范围排除，热度不可用 → 回落收藏，带时间条件走 v0
        assertEquals(setOf(SearchSort.RANK, SearchSort.COLLECTS), range.availableSorts())
        assertEquals(SearchSort.COLLECTS, range.effectiveSort())
        assertEquals(SearchSource.DATED_SEARCH, range.searchSource())
        // 单年仍是分类浏览，热度可用
        val single = range.copy(filters = SearchFilters(airtime = SearchAirtime(2015, 2015)))
        assertEquals(SearchSort.entries.toSet(), single.availableSorts())
        assertEquals(SearchSource.BROWSE, single.searchSource())
    }

    @Test
    fun 搜索_用户标签时排名与收藏可用_排名附带rank下限1() {
        val tagged = anime.copy(filters = SearchFilters(tags = listOf(TagFilter.User("百合"))))
        val rank = tagged.copy(sort = SearchSort.RANK).dto()
        assertEquals("rank", rank.sort)
        assertEquals(listOf(">=1"), rank.filter?.rank)

        val collects = tagged.dto()
        assertEquals("heat", collects.sort)
        assertNull(collects.filter?.rank)

        // 热度在搜索接口不可用：按收藏发
        assertEquals("heat", tagged.copy(sort = SearchSort.TRENDS).dto().sort)
    }

    @Test
    fun 搜索_无任何条件时filter省略() {
        assertNull(SearchCriteria(query = "eva").dto().filter)
    }

    @Test
    fun 搜索_空关键词仍序列化keyword与sort() {
        val text = json.encodeToString(SearchCriteria(filters = SearchFilters(airtime = SearchAirtime(2024, 2024))).dto())
        assertTrue("keyword 必须仍存在: $text", text.contains("\"keyword\":\"\""))
        assertTrue("sort 必须写出: $text", text.contains("\"sort\":\"heat\""))
    }

    @Test
    fun 搜索_UNKNOWN类型不进入请求() {
        // 映射层不做业务校验；UNKNOWN 应在 normalize 阶段被拒，这里只确认不生成 type=0
        assertNull(SearchCriteria(subjectType = SubjectType.UNKNOWN).dto().filter?.type)
    }

    @Test
    fun 搜索_维度经翻译进入meta_tags_用实测标签而非显示文字() {
        val dto = SearchCriteria(
            query = "塞尔达",
            subjectType = SubjectType.GAME,
            filters = SearchFilters(
                dimensions = mapOf(
                    SearchDimension.CONTENT_RATING to listOf(opt(SubjectType.GAME, SearchDimension.CONTENT_RATING, "全年龄")),
                    SearchDimension.PLATFORM to listOf(opt(SubjectType.GAME, SearchDimension.PLATFORM, "Nintendo Switch")),
                ),
            ),
        ).dto()
        // 词典维度顺序：平台在分级之前
        assertEquals(listOf("NS", "全年龄"), dto.filter?.metaTags)
        assertNull(dto.filter?.tag)
    }

    @Test
    fun 搜索_同一维度多个值全部进meta_tags_按词典取值顺序() {
        val dto = SearchCriteria(
            subjectType = SubjectType.ANIME,
            filters = SearchFilters(
                dimensions = mapOf(
                    SearchDimension.GENRE to listOf(
                        opt(SubjectType.ANIME, SearchDimension.GENRE, "喜剧"),
                        opt(SubjectType.ANIME, SearchDimension.GENRE, "科幻"),
                    ),
                ),
            ),
        ).dto()
        // 词典里科幻在喜剧之前：输出按词典顺序，与列表插入顺序无关
        assertEquals(listOf("科幻", "喜剧"), dto.filter?.metaTags)
    }

    @Test
    fun 搜索_维度与自由公共标签合并去重_用户标签仍走tag() {
        val dto = anime.copy(
            filters = SearchFilters(
                dimensions = mapOf(SearchDimension.GENRE to listOf(opt(SubjectType.ANIME, SearchDimension.GENRE, "科幻"))),
                tags = listOf(TagFilter.Public("科幻"), TagFilter.Public("机甲"), TagFilter.User("原创")),
            ),
        ).dto()
        assertEquals(listOf("科幻", "机甲"), dto.filter?.metaTags)
        assertEquals(listOf("原创"), dto.filter?.tag)
    }

    // --- 分类浏览接口（p1） ---

    @Test
    fun 浏览_五种排序映射到p1原值() {
        // browseValue 是枚举穷尽映射，五个分支都必须保留；
        // 但 p1 在生产里可达的只有热度/日期/名称（排名与标注数走搜索接口，见 searchSource）。
        val expected = mapOf(
            SearchSort.RANK to "rank",
            SearchSort.TRENDS to "trends",
            SearchSort.COLLECTS to "collects",
            SearchSort.DATE to "date",
            SearchSort.TITLE to "title",
        )
        for ((sort, value) in expected) {
            val criteria = when (sort) {
                // 不可浏览形态，任何排序都能直通 toBrowseQuery（映射层不做业务校验）
                SearchSort.RANK, SearchSort.COLLECTS -> anime.copy(sort = sort, filters = SearchFilters(tags = listOf(TagFilter.User("x"), TagFilter.User("y"))))
                else -> anime.copy(sort = sort)
            }
            assertEquals("$sort", value, criteria.toBrowseQuery().sort)
        }
    }

    @Test
    fun 浏览_类型标签年月() {
        val query = anime.copy(
            sort = SearchSort.RANK,
            filters = SearchFilters(
                dimensions = mapOf(
                    SearchDimension.CATEGORY to listOf(opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")),
                    SearchDimension.REGION to listOf(opt(SubjectType.ANIME, SearchDimension.REGION, "中国香港")),
                ),
                tags = listOf(TagFilter.Public("2022")),
                airtime = SearchAirtime(2024, 2024, 10),
            ),
        ).toBrowseQuery()
        assertEquals(BrowseQuery(type = 2, sort = "rank", tags = listOf("TV", "香港", "2022"), year = 2024, month = 10), query)
    }

    @Test
    fun 浏览_单个meta标签原样发一次() {
        val one = anime.copy(filters = SearchFilters(tags = listOf(TagFilter.Public("漫画改"))))
        // 单个 meta 标签只发一次（该组合走 v0，p1 不可达）
        assertEquals(listOf("漫画改"), one.toBrowseQuery().tags)
        assertNull(one.toBrowseQuery().tagsCat)
        // 多个标签、无标签都原样
        val two = anime.copy(filters = SearchFilters(tags = listOf(TagFilter.Public("TV"), TagFilter.Public("漫画改"))))
        assertEquals(listOf("TV", "漫画改"), two.toBrowseQuery().tags)
        assertEquals(emptyList<String>(), anime.toBrowseQuery().tags)
    }

    @Test
    fun 浏览_单用户标签走标签浏览_带tagsCat() {
        // 标签浏览只发这一个用户标签；日期排序仍走 p1（排名 / 标注数走 v0）
        val tagDate = anime.copy(
            sort = SearchSort.DATE,
            filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子"))),
        ).toBrowseQuery()
        assertEquals("subject", tagDate.tagsCat)
        assertEquals(listOf("菅野洋子"), tagDate.tags)
        assertEquals("date", tagDate.sort)

        val tagTitle = anime.copy(
            sort = SearchSort.TITLE,
            filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子"))),
        ).toBrowseQuery()
        assertEquals("subject", tagTitle.tagsCat)
        assertEquals(listOf("菅野洋子"), tagTitle.tags)
        assertEquals("title", tagTitle.sort)
    }

    @Test
    fun 浏览_单用户标签支持年月() {
        val tagAirtime = anime.copy(
            sort = SearchSort.TITLE,
            filters = SearchFilters(
                tags = listOf(TagFilter.User("菅野洋子")),
                airtime = SearchAirtime(2003, 2003, 10),
            ),
        ).toBrowseQuery()
        assertEquals(
            BrowseQuery(
                type = 2,
                sort = "title",
                tags = listOf("菅野洋子"),
                year = 2003,
                month = 10,
                tagsCat = "subject",
            ),
            tagAirtime,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun 浏览_无具体类型是调用方错误() {
        SearchCriteria().toBrowseQuery()
    }

    // --- 可浏览条件走 v0 时的请求体（排名 / 标注数） ---

    @Test
    fun 浏览走v0_分类浏览的维度与公共标签进meta_tags_单个用户标签进tag() {
        // 维度 + 公共标签合并去重进 meta_tags（metaTags() 原样复用），用户标签走 tag
        val category = SearchCriteria(
            subjectType = SubjectType.ANIME,
            sort = SearchSort.RANK,
            filters = SearchFilters(
                dimensions = mapOf(SearchDimension.GENRE to listOf(opt(SubjectType.ANIME, SearchDimension.GENRE, "科幻"))),
                tags = listOf(TagFilter.Public("科幻"), TagFilter.Public("机甲")),
                airtime = SearchAirtime(2024, 2024, 10),
            ),
        ).dto()
        assertEquals("", category.keyword)
        assertEquals("rank", category.sort)
        assertEquals(listOf(2), category.filter?.type)
        assertEquals(listOf("科幻", "机甲"), category.filter?.metaTags)
        assertNull(category.filter?.tag)
        assertEquals(listOf(">=2024-10-01", "<=2024-10-31"), category.filter?.airDate)
        assertEquals(listOf(">=1"), category.filter?.rank)

        // 标注数：COLLECTS → heat，且不带 rank 下限
        val collects = SearchCriteria(
            subjectType = SubjectType.ANIME,
            sort = SearchSort.COLLECTS,
            filters = SearchFilters(dimensions = mapOf(SearchDimension.CATEGORY to listOf(opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")))),
        ).dto()
        assertEquals("heat", collects.sort)
        assertEquals(listOf("TV"), collects.filter?.metaTags)
        assertNull(collects.filter?.rank)

        // 标签浏览：单个用户标签进 tag，不进 meta_tags
        val tag = SearchCriteria(
            subjectType = SubjectType.ANIME,
            sort = SearchSort.RANK,
            filters = SearchFilters(tags = listOf(TagFilter.User("百合"))),
        ).dto()
        assertEquals("rank", tag.sort)
        assertEquals(listOf("百合"), tag.filter?.tag)
        assertNull(tag.filter?.metaTags)
        assertEquals(listOf(">=1"), tag.filter?.rank)
    }

    @Test
    fun 浏览走v0_年份换算成区间() {
        val filter = SearchCriteria(
            subjectType = SubjectType.ANIME,
            sort = SearchSort.RANK,
            filters = SearchFilters(airtime = SearchAirtime(2024, 2024, 2)),
        ).dto().filter
        assertEquals(listOf(">=2024-02-01", "<=2024-02-29"), filter?.airDate)
    }

    @Test
    fun 浏览走v0_账号偏好为关时显式发nsfwfalse_为开时不发() {
        val criteria = SearchCriteria(
            subjectType = SubjectType.ANIME,
            sort = SearchSort.RANK,
            filters = SearchFilters(tags = listOf(TagFilter.User("百合"))),
        )
        // 偏好关：显式 false，服务端据此只返回非 NSFW
        assertEquals(false, criteria.toSearchRequestDto(nsfwEnabled = false).filter?.nsfw)
        // 偏好开：不传，服务端放行 NSFW
        assertNull(criteria.toSearchRequestDto(nsfwEnabled = true).filter?.nsfw)
    }
}
