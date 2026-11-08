package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDimensionTest {

    private val concreteTypes = listOf(
        SubjectType.ANIME,
        SubjectType.BOOK,
        SubjectType.GAME,
        SubjectType.MUSIC,
        SubjectType.REAL,
    )

    private fun option(type: SubjectType, dimension: SearchDimension, label: String): SearchDimensionOption =
        searchDimensionsFor(type).single { it.dimension == dimension }.options.single { it.label == label }

    private fun criteria(
        type: SubjectType?,
        dimensions: Map<SearchDimension, List<SearchDimensionOption>> = emptyMap(),
        tags: List<TagFilter> = emptyList(),
        airtime: SearchAirtime? = null,
    ) = SearchCriteria(subjectType = type, filters = SearchFilters(dimensions, tags, airtime))

    // --- 词典结构 ---

    @Test
    fun 全部与UNKNOWN类型没有维度() {
        assertTrue(searchDimensionsFor(null).isEmpty())
        assertTrue(searchDimensionsFor(SubjectType.UNKNOWN).isEmpty())
    }

    @Test
    fun 各具体类型维度非空且不重复_每组取值非空() {
        for (type in concreteTypes) {
            val groups = searchDimensionsFor(type)
            assertTrue("$type 必须有维度", groups.isNotEmpty())
            assertEquals("$type 维度不得重复", groups.size, groups.map { it.dimension }.toSet().size)
            groups.forEach { assertTrue("$type/${it.dimension} 取值不得为空", it.options.isNotEmpty()) }
        }
    }

    @Test
    fun 同一类型内显示文字与metaTag均不跨维度重复() {
        // 重复会让「维度 ↔ meta_tag」翻译出现二义性（2026-09-27 实测过没有重名）。
        for (type in concreteTypes) {
            val options = searchDimensionsFor(type).flatMap { it.options }
            assertEquals("$type 显示文字重复", options.size, options.map { it.label }.toSet().size)
            assertEquals("$type metaTag 重复", options.size, options.map { it.metaTag }.toSet().size)
        }
    }

    @Test
    fun 维度顺序按网页原顺序() {
        assertEquals(
            listOf(
                SearchDimension.CATEGORY,
                SearchDimension.SOURCE,
                SearchDimension.GENRE,
                SearchDimension.REGION,
                SearchDimension.AUDIENCE,
            ),
            searchDimensionsFor(SubjectType.ANIME).map { it.dimension },
        )
        assertEquals(
            listOf(SearchDimension.CATEGORY, SearchDimension.THEME, SearchDimension.REGION),
            searchDimensionsFor(SubjectType.REAL).map { it.dimension },
        )
    }

    @Test
    fun 别名取值用网页文字显示_用实测标签请求() {
        assertEquals("NS", option(SubjectType.GAME, SearchDimension.PLATFORM, "Nintendo Switch").metaTag)
        assertEquals("香港", option(SubjectType.REAL, SearchDimension.REGION, "中国香港").metaTag)
    }

    // --- 选法表（唯一判定点）---

    @Test
    fun 选法表逐格断言() {
        assertEquals(DimensionSelectionMode.SINGLE, dimensionSelectionMode(SubjectType.ANIME, SearchDimension.CATEGORY))
        assertEquals(DimensionSelectionMode.SINGLE, dimensionSelectionMode(SubjectType.BOOK, SearchDimension.SERIES))
        assertEquals(DimensionSelectionMode.SINGLE, dimensionSelectionMode(SubjectType.BOOK, SearchDimension.PUBLICATION_STATUS))
        assertEquals(DimensionSelectionMode.SINGLE, dimensionSelectionMode(SubjectType.ANIME, SearchDimension.AUDIENCE))
        assertEquals(DimensionSelectionMode.SINGLE, dimensionSelectionMode(SubjectType.GAME, SearchDimension.CONTENT_RATING))
        // SOURCE：音乐多选，其余单选
        assertEquals(DimensionSelectionMode.MULTI, dimensionSelectionMode(SubjectType.MUSIC, SearchDimension.SOURCE))
        assertEquals(DimensionSelectionMode.SINGLE, dimensionSelectionMode(SubjectType.ANIME, SearchDimension.SOURCE))
        // GENRE：动画、游戏、音乐多选
        for (type in listOf(SubjectType.ANIME, SubjectType.GAME, SubjectType.MUSIC)) {
            assertEquals(DimensionSelectionMode.MULTI, dimensionSelectionMode(type, SearchDimension.GENRE))
        }
        // THEME：书籍、三次元多选
        for (type in listOf(SubjectType.BOOK, SubjectType.REAL)) {
            assertEquals(DimensionSelectionMode.MULTI, dimensionSelectionMode(type, SearchDimension.THEME))
        }
        assertEquals(DimensionSelectionMode.MULTI, dimensionSelectionMode(SubjectType.GAME, SearchDimension.PLATFORM))
        // REGION：动画、三次元多选；书籍单选
        assertEquals(DimensionSelectionMode.MULTI, dimensionSelectionMode(SubjectType.ANIME, SearchDimension.REGION))
        assertEquals(DimensionSelectionMode.MULTI, dimensionSelectionMode(SubjectType.REAL, SearchDimension.REGION))
        assertEquals(DimensionSelectionMode.SINGLE, dimensionSelectionMode(SubjectType.BOOK, SearchDimension.REGION))
    }

    // --- 唯一翻译点 ---

    @Test
    fun 翻译按词典维度顺序输出_与Map插入顺序无关() {
        val audience = option(SubjectType.ANIME, SearchDimension.AUDIENCE, "少女向")
        val category = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val a = linkedMapOf(SearchDimension.AUDIENCE to listOf(audience), SearchDimension.CATEGORY to listOf(category))
        val b = linkedMapOf(SearchDimension.CATEGORY to listOf(category), SearchDimension.AUDIENCE to listOf(audience))
        assertEquals(listOf("TV", "少女向"), dimensionMetaTags(SubjectType.ANIME, a))
        assertEquals(listOf("TV", "少女向"), dimensionMetaTags(SubjectType.ANIME, b))
    }

    @Test
    fun 翻译组内按词典取值顺序输出_与列表插入顺序无关() {
        val sciFi = option(SubjectType.ANIME, SearchDimension.GENRE, "科幻")
        val comedy = option(SubjectType.ANIME, SearchDimension.GENRE, "喜剧")
        val a = mapOf(SearchDimension.GENRE to listOf(comedy, sciFi))
        val b = mapOf(SearchDimension.GENRE to listOf(sciFi, comedy))
        assertEquals(listOf("科幻", "喜剧"), dimensionMetaTags(SubjectType.ANIME, a))
        assertEquals(listOf("科幻", "喜剧"), dimensionMetaTags(SubjectType.ANIME, b))
    }

    @Test
    fun 翻译输出metaTag而非显示文字() {
        val switch = option(SubjectType.GAME, SearchDimension.PLATFORM, "Nintendo Switch")
        assertEquals(listOf("NS"), dimensionMetaTags(SubjectType.GAME, mapOf(SearchDimension.PLATFORM to listOf(switch))))
    }

    @Test
    fun 选择合法性_必须属于当前类型的对应维度() {
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        assertTrue(dimensionSelectionsValid(SubjectType.ANIME, mapOf(SearchDimension.CATEGORY to listOf(tv))))
        assertTrue(dimensionSelectionsValid(null, emptyMap()))
        // 跨类型、全部类型、错维度都非法
        assertFalse(dimensionSelectionsValid(SubjectType.BOOK, mapOf(SearchDimension.CATEGORY to listOf(tv))))
        assertFalse(dimensionSelectionsValid(null, mapOf(SearchDimension.CATEGORY to listOf(tv))))
        assertFalse(dimensionSelectionsValid(SubjectType.ANIME, mapOf(SearchDimension.SOURCE to listOf(tv))))
        // 词典外的值
        val fake = SearchDimensionOption("其他", "其他")
        assertFalse(dimensionSelectionsValid(SubjectType.ANIME, mapOf(SearchDimension.CATEGORY to listOf(fake))))
    }

    @Test
    fun 选择合法性_单选维度不得多值() {
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val ova = option(SubjectType.ANIME, SearchDimension.CATEGORY, "OVA")
        assertFalse(dimensionSelectionsValid(SubjectType.ANIME, mapOf(SearchDimension.CATEGORY to listOf(tv, ova))))
        // 同一取值重复也算非法
        assertFalse(dimensionSelectionsValid(SubjectType.ANIME, mapOf(SearchDimension.CATEGORY to listOf(tv, tv))))
        // 多选维度允许
        val sciFi = option(SubjectType.ANIME, SearchDimension.GENRE, "科幻")
        val comedy = option(SubjectType.ANIME, SearchDimension.GENRE, "喜剧")
        assertTrue(dimensionSelectionsValid(SubjectType.ANIME, mapOf(SearchDimension.GENRE to listOf(sciFi, comedy))))
    }

    // --- 唯一更新点 ---

    @Test
    fun 单选维度_点别的值替换_再点已选值取消() {
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val ova = option(SubjectType.ANIME, SearchDimension.CATEGORY, "OVA")
        val japan = option(SubjectType.ANIME, SearchDimension.REGION, "日本")
        val base = criteria(SubjectType.ANIME, mapOf(SearchDimension.REGION to listOf(japan)), airtime = SearchAirtime(2010))
            .toggleDimension(SearchDimension.CATEGORY, tv)

        assertEquals(mapOf(SearchDimension.REGION to listOf(japan), SearchDimension.CATEGORY to listOf(tv)), base.filters.dimensions)
        assertEquals(SearchAirtime(2010), base.filters.airtime)

        val replaced = base.toggleDimension(SearchDimension.CATEGORY, ova)
        assertEquals(listOf(ova), replaced.filters.dimensions[SearchDimension.CATEGORY])

        val cleared = replaced.toggleDimension(SearchDimension.CATEGORY, ova)
        assertEquals(mapOf(SearchDimension.REGION to listOf(japan)), cleared.filters.dimensions)
    }

    @Test
    fun 多选维度_点一下加_再点去掉() {
        val sciFi = option(SubjectType.ANIME, SearchDimension.GENRE, "科幻")
        val comedy = option(SubjectType.ANIME, SearchDimension.GENRE, "喜剧")
        val once = criteria(SubjectType.ANIME).toggleDimension(SearchDimension.GENRE, sciFi)
        val twice = once.toggleDimension(SearchDimension.GENRE, comedy)
        assertEquals(listOf(sciFi, comedy), twice.filters.dimensions[SearchDimension.GENRE])
        assertEquals(listOf(sciFi), twice.toggleDimension(SearchDimension.GENRE, comedy).filters.dimensions[SearchDimension.GENRE])
    }

    @Test
    fun withSubjectType_同类型不动_换类型分类带过去_用户标签与时间保留() {
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val mystery = option(SubjectType.ANIME, SearchDimension.GENRE, "悬疑")
        val criteria = criteria(
            SubjectType.ANIME,
            mapOf(SearchDimension.CATEGORY to listOf(tv), SearchDimension.GENRE to listOf(mystery)),
            tags = listOf(TagFilter.User("原创")),
            airtime = SearchAirtime(2010),
        )

        assertSame(criteria, criteria.withSubjectType(SubjectType.ANIME))

        // 切到「全部」：没有词典，两个维度都变成「分类」标签，跨类型搜索
        val all = criteria.withSubjectType(null)
        assertEquals(null, all.subjectType)
        assertTrue(all.filters.dimensions.isEmpty())
        assertEquals(
            listOf(TagFilter.Public("TV"), TagFilter.Public("悬疑"), TagFilter.User("原创")),
            all.filters.tags,
        )
        assertEquals(SearchAirtime(2010), all.filters.airtime)

        // 再切到书籍：悬疑在书籍词典里（题材），重新落成维度；TV 不在，保留为分类
        val book = all.withSubjectType(SubjectType.BOOK)
        assertEquals(
            mapOf(SearchDimension.THEME to listOf(option(SubjectType.BOOK, SearchDimension.THEME, "悬疑"))),
            book.filters.dimensions,
        )
        assertEquals(listOf(TagFilter.Public("TV"), TagFilter.User("原创")), book.filters.tags)

        // 切回动画：两个都回到原维度，与出发时一致
        assertEquals(criteria, book.withSubjectType(SubjectType.ANIME))

        // 游戏词典里两个都没有：全部保留为分类
        val game = criteria.withSubjectType(SubjectType.GAME)
        assertTrue(game.filters.dimensions.isEmpty())
        assertEquals(
            listOf(TagFilter.Public("TV"), TagFilter.Public("悬疑"), TagFilter.User("原创")),
            game.filters.tags,
        )
    }

    @Test
    fun withCategoriesPlaced_多选维度可以一直往里落() {
        val criteria = criteria(SubjectType.ANIME, tags = listOf(TagFilter.Public("悬疑"), TagFilter.Public("科幻")))
            .withCategoriesPlaced()
        // 悬疑与科幻同属动画 GENRE（多选）：两个都落成维度，不剩分类标签
        assertEquals(
            mapOf(
                SearchDimension.GENRE to listOf(
                    option(SubjectType.ANIME, SearchDimension.GENRE, "悬疑"),
                    option(SubjectType.ANIME, SearchDimension.GENRE, "科幻"),
                ),
            ),
            criteria.filters.dimensions,
        )
        assertEquals(emptyList<TagFilter>(), criteria.filters.tags)
    }

    @Test
    fun withCategoriesPlaced_单选维度被占用后多余的值留成分类() {
        // CATEGORY 是单选：TV 落成维度，OVA 留成分类
        val criteria = criteria(SubjectType.ANIME, tags = listOf(TagFilter.Public("TV"), TagFilter.Public("OVA")))
            .withCategoriesPlaced()
        assertEquals(
            mapOf(SearchDimension.CATEGORY to listOf(option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV"))),
            criteria.filters.dimensions,
        )
        assertEquals(listOf(TagFilter.Public("OVA")), criteria.filters.tags)
    }
}
