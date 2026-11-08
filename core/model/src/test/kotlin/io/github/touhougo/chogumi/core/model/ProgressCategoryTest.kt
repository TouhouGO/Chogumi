package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [availableProgressCategories] 与 [matchesCategoryFilter] 的口径。
 *
 * 分类词典是搜索页的**唯一真源**（[searchDimensionsFor]），本文件只验证「收窄到实际出现过的取值」
 * 与「同维并集 / 跨维交集」两个判定，不重复词典的全部内容。
 */
class ProgressCategoryTest {

    private fun item(id: Int, vararg metaTags: String) = UserCollectionBo(
        subjectId = id,
        subjectType = SubjectType.ANIME,
        type = CollectionType.DOING,
        subject = SubjectBo(
            id = id, type = SubjectType.ANIME, name = "作品$id", nameCn = "作品$id",
            metaTags = metaTags.toList(),
        ),
    )

    private fun option(dimension: SearchDimension, label: String, metaTag: String = label) =
        ProgressCategory(dimension, SearchDimensionOption(label, metaTag))

    // ---------- 可选项收集 ----------

    @Test
    fun `只列当前列表里实际出现过的取值`() {
        val items = listOf(item(1, "TV", "日本"), item(2, "剧场版", "日本"))
        val groups = availableProgressCategories(SubjectType.ANIME, items, emptyMap())
        val byDimension = groups.associateBy { it.dimension }
        // CATEGORY 只出现 TV 与 剧场版（词典里还有 WEB/OVA/动态漫画，不出现就不列）。
        assertEquals(listOf("TV", "剧场版"), byDimension.getValue(SearchDimension.CATEGORY).options.map { it.key.option.metaTag })
        // REGION 只出现 日本。
        assertEquals(listOf("日本"), byDimension.getValue(SearchDimension.REGION).options.map { it.key.option.metaTag })
    }

    @Test
    fun `没有取值出现的维度整节不显示`() {
        val items = listOf(item(1, "TV"))
        val dimensions = availableProgressCategories(SubjectType.ANIME, items, emptyMap()).map { it.dimension }
        assertEquals(listOf(SearchDimension.CATEGORY), dimensions)
    }

    @Test
    fun `取值按部数从多到少 同部数沿用词典顺序 不按出现顺序`() {
        val items = listOf(item(1, "剧场版", "TV"), item(2, "OVA"), item(3, "OVA"))
        val category = availableProgressCategories(SubjectType.ANIME, items, emptyMap())
            .first { it.dimension == SearchDimension.CATEGORY }
        // OVA 2 部在前；TV 与剧场版各 1 部按词典顺序（TV → WEB → OVA → 剧场版），出现顺序（剧场版在前）不改变它。
        assertEquals(listOf("OVA", "TV", "剧场版"), category.options.map { it.key.option.metaTag })
        assertEquals(listOf(2, 1, 1), category.options.map { it.count })
    }

    @Test
    fun `取值的封面是带这个取值的最近动过的几部`() {
        val items = listOf(item(1, "TV").copy(updatedAt = 100L), item(2, "TV").copy(updatedAt = 300L), item(3, "剧场版").copy(updatedAt = 900L))
        val tv = availableProgressCategories(SubjectType.ANIME, items, emptyMap())
            .first { it.dimension == SearchDimension.CATEGORY }.options.first { it.key.option.metaTag == "TV" }
        assertEquals(listOf(2, 1), tv.recent.map { it.id })
    }

    @Test
    fun `全部页签没有跨类型维度 返回空`() {
        val items = listOf(item(1, "TV", "日本"))
        assertTrue(availableProgressCategories(null, items, emptyMap()).isEmpty())
        assertTrue(availableProgressCategories(SubjectType.UNKNOWN, items, emptyMap()).isEmpty())
    }

    @Test
    fun `跨类型取值不串味 动画取值在书籍词典里不列`() {
        // 书籍词典的 CATEGORY 是 漫画/小说/画集/绘本/公式书，没有 TV。
        val items = listOf(item(1, "TV", "漫画"))
        val groups = availableProgressCategories(SubjectType.BOOK, items, emptyMap())
        val category = groups.firstOrNull { it.dimension == SearchDimension.CATEGORY }
        assertEquals(listOf("漫画"), category?.options?.map { it.key.option.metaTag })
    }

    @Test
    fun `别名取值按 metaTag 匹配 显示名与请求值不同也命中`() {
        // 地区「中国香港」在词典里的 metaTag 是 `香港`；条目上带的是 `香港`。
        val items = listOf(item(1, "香港"))
        val region = availableProgressCategories(SubjectType.ANIME, items, emptyMap())
            .firstOrNull { it.dimension == SearchDimension.REGION }
        assertEquals(listOf(SearchDimensionOption("中国香港", "香港")), region?.options?.map { it.key.option })
    }

    @Test
    fun `面板先摊开题材 动画是 GENRE 书籍是 THEME 都没有给第一个`() {
        fun groups(vararg dimensions: SearchDimension) = dimensions.map { ProgressCategoryGroup(it, emptyList()) }
        assertEquals(SearchDimension.GENRE, progressFilterOpening(groups(SearchDimension.CATEGORY, SearchDimension.SOURCE, SearchDimension.GENRE)).dimension)
        assertEquals(SearchDimension.THEME, progressFilterOpening(groups(SearchDimension.CATEGORY, SearchDimension.THEME, SearchDimension.REGION)).dimension)
        assertEquals(SearchDimension.REGION, progressFilterOpening(groups(SearchDimension.REGION, SearchDimension.CATEGORY)).dimension)
        assertEquals(null, progressFilterOpening(emptyList()).dimension)
    }

    @Test
    fun `有分类时我的标签收着 没有分类时标签直接摊开不可收起`() {
        assertTrue(progressFilterOpening(listOf(ProgressCategoryGroup(SearchDimension.GENRE, emptyList()))).tagsCollapsible)
        assertFalse(progressFilterOpening(emptyList()).tagsCollapsible)
    }

    // ---------- 匹配判定 ----------

    @Test
    fun `空选恒通过`() {
        assertTrue(matchesCategoryFilter(emptyList(), emptySet()))
        assertTrue(matchesCategoryFilter(listOf("TV"), emptySet()))
    }

    @Test
    fun `同一维度内取并集`() {
        val selected = setOf(option(SearchDimension.CATEGORY, "TV"), option(SearchDimension.CATEGORY, "剧场版"))
        // 命中任一即可：既是 TV 也是剧场版的作品通过，只满足其中一项的也通过。
        assertTrue(matchesCategoryFilter(listOf("TV", "剧场版"), selected))
        assertTrue(matchesCategoryFilter(listOf("TV"), selected))
        assertTrue(matchesCategoryFilter(listOf("剧场版"), selected))
        // 两个都不沾才不通过。
        assertFalse(matchesCategoryFilter(listOf("OVA"), selected))
    }

    @Test
    fun `不同维度之间取交集`() {
        val selected = setOf(option(SearchDimension.CATEGORY, "TV"), option(SearchDimension.REGION, "日本"))
        assertTrue(matchesCategoryFilter(listOf("TV", "日本"), selected))
        // 只满足其中一个维度 → 不通过（若退化成并集，这两条会误判为通过）。
        assertFalse(matchesCategoryFilter(listOf("TV", "中国"), selected))
        assertFalse(matchesCategoryFilter(listOf("剧场版", "日本"), selected))
    }

    @Test
    fun `同名取值同时满足含它的各个维度`() {
        // 构造两个维度各有一个 metaTag 相同的取值（现实中词典已实测无重名，
        // 2026-09-27 实测；这里刻意制造以钉住分组语义）。
        // 条目带一个 X 时，两个维度各自都能命中它 → 通过；这是「按维度分组取交集」的必然结果，
        // 不是把维度串味——判定始终在各维度内独立进行。
        val categoryHit = ProgressCategory(SearchDimension.CATEGORY, SearchDimensionOption("X", "X"))
        val regionHit = ProgressCategory(SearchDimension.REGION, SearchDimensionOption("Y", "X"))
        val selected = setOf(categoryHit, regionHit)
        assertTrue(matchesCategoryFilter(listOf("X"), selected))
        assertFalse(matchesCategoryFilter(listOf("Z"), selected))
    }

    @Test
    fun `无 metaTags 而选中非空 不通过`() {
        assertFalse(matchesCategoryFilter(emptyList(), setOf(option(SearchDimension.CATEGORY, "TV"))))
    }

    @Test
    fun `别名取值用 metaTag 匹配而非显示名`() {
        val selected = setOf(option(SearchDimension.REGION, "中国香港", metaTag = "香港"))
        assertTrue(matchesCategoryFilter(listOf("香港"), selected))
        assertFalse(matchesCategoryFilter(listOf("中国香港"), selected))
    }
}
