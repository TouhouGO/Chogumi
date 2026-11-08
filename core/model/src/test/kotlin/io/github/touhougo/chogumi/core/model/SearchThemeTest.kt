package io.github.touhougo.chogumi.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchThemeTest {

    private val concreteTypes = listOf(
        SubjectType.ANIME,
        SubjectType.BOOK,
        SubjectType.GAME,
        SubjectType.MUSIC,
        SubjectType.REAL,
    )

    // --- 唯一判定：题材维度 ---

    @Test
    fun 题材维度按类型给_动画是类型书名三次元是题材_游戏音乐没有() {
        assertEquals(SearchDimension.GENRE, themeDimensionFor(SubjectType.ANIME))
        assertEquals(SearchDimension.THEME, themeDimensionFor(SubjectType.BOOK))
        assertEquals(SearchDimension.THEME, themeDimensionFor(SubjectType.REAL))
        assertNull(themeDimensionFor(SubjectType.GAME))
        assertNull(themeDimensionFor(SubjectType.MUSIC))
        assertNull(themeDimensionFor(null))
        assertNull(themeDimensionFor(SubjectType.UNKNOWN))
    }

    // --- 守卫：词表结构 ---

    @Test
    fun 题材词不得与同类型任何维度取值重名() {
        for (type in concreteTypes) {
            val dimensionLabels = searchDimensionsFor(type).flatMap { it.options }.map { it.label }.toSet()
            val themeDimension = themeDimensionFor(type)
            // 人工词（题材区里不属于题材维度的那些）不得与任何维度取值重名
            val extra = themeWordsFor(type).filter { word ->
                word !in searchDimensionsFor(type)
                    .filter { it.dimension == themeDimension }
                    .flatMap { group -> group.options.map { it.label } }
            }
            for (word in extra) {
                assertTrue("$type 的人工题材词「$word」与维度取值重名", word !in dimensionLabels)
            }
        }
    }

    @Test
    fun 排序表里的每个词都必须在题材区里() {
        // 动画与书籍有排序表：表里的词必须都在素材（维度取值 + 人工词）里，否则是残留
        for (type in listOf(SubjectType.ANIME, SubjectType.BOOK)) {
            val words = themeWordsFor(type).toSet()
            val order = themeOrderFor(type)
            assertTrue("$type 排序表不得为空", order.isNotEmpty())
            for (word in order) {
                assertTrue("$type 排序表里的「$word」不在题材区里", word in words)
            }
        }
    }

    @Test
    fun 题材区_动画含维度取值与人工词_排序表在前其余按词典顺序接后() {
        val words = themeWordsFor(SubjectType.ANIME)
        // 动画排序表第一个是「恋爱」（词典里第一个是「科幻」，说明人工排序真的生效）
        assertEquals("恋爱", words.first())
        // 人工词都在
        assertTrue("异世界" in words)
        assertTrue("女主控" in words)
        // GENRE 的全部取值也都在（题材维度取值合成一组）
        val genres = searchDimensionsFor(SubjectType.ANIME)
            .single { it.dimension == SearchDimension.GENRE }.options.map { it.label }
        assertTrue(words.containsAll(genres))
        assertEquals("题材区不得有重复词", words.size, words.toSet().size)
    }

    @Test
    fun 题材区_游戏只有人工词_音乐与三次元为空或只有维度取值() {
        // 游戏没有题材维度（GENRE 是玩法），题材区=人工词
        val game = themeWordsFor(SubjectType.GAME)
        assertEquals(
            listOf("纯爱", "百合", "解谜", "推理", "恐怖", "开放世界", "魂系", "科幻", "奇幻", "悬疑"),
            game,
        )
        // 音乐没有题材维度、也没有人工词：题材区为空（界面据此整区不渲染）
        assertTrue(themeWordsFor(SubjectType.MUSIC).isEmpty())
        // 三次元没有人工词，题材区就是 THEME 维度取值
        assertEquals(
            searchDimensionsFor(SubjectType.REAL)
                .single { it.dimension == SearchDimension.THEME }.options.map { it.label },
            themeWordsFor(SubjectType.REAL),
        )
    }

    @Test
    fun 全部与UNKNOWN没有题材区() {
        assertTrue(themeWordsFor(null).isEmpty())
        assertTrue(themeWordsFor(SubjectType.UNKNOWN).isEmpty())
    }

    // --- 唯一解析：输入 → 维度取值 / 用户标签 ---

    @Test
    fun resolveTagInput_显示名优先_metaTag也认() {
        assertEquals(
            TagInputResolution.Dimension(
                SearchDimension.REGION,
                SearchDimensionOption("中国香港", "香港"),
            ),
            resolveTagInput(SubjectType.ANIME, "中国香港"),
        )
        // 别名：只给 metaTag「香港」也落到地区
        assertEquals(
            TagInputResolution.Dimension(
                SearchDimension.REGION,
                SearchDimensionOption("中国香港", "香港"),
            ),
            resolveTagInput(SubjectType.ANIME, "香港"),
        )
        assertEquals(
            TagInputResolution.Dimension(
                SearchDimension.PLATFORM,
                SearchDimensionOption("Nintendo Switch", "NS"),
            ),
            resolveTagInput(SubjectType.GAME, "NS"),
        )
    }

    @Test
    fun resolveTagInput_目录外的词也收成用户标签_空输入返回null() {
        assertEquals(
            TagInputResolution.UserTag("目录外的词"),
            resolveTagInput(SubjectType.ANIME, "目录外的词"),
        )
        assertNull(resolveTagInput(SubjectType.ANIME, "   "))
        assertNull(resolveTagInput(SubjectType.BOOK, ""))
        // 一定长时收敛
        assertEquals(TagInputResolution.UserTag("治愈"), resolveTagInput(null, "治愈"))
    }

    @Test
    fun isThemeWord按当前类型的题材区判定_只认用户标签() {
        assertTrue(TagFilter.User("异世界").isThemeWord(SubjectType.ANIME))
        assertTrue(TagFilter.User("奇幻").isThemeWord(SubjectType.ANIME))
        assertTrue(TagFilter.User("魂系").isThemeWord(SubjectType.GAME))
        assertTrue(!TagFilter.User("魂系").isThemeWord(SubjectType.MUSIC))
        assertTrue(!TagFilter.User("菅野洋子").isThemeWord(SubjectType.ANIME))
        // 落不进维度的「分类」标签不算题材区的词，叫「标签」
        assertTrue(!TagFilter.Public("奇幻").isThemeWord(SubjectType.ANIME))
    }

    // --- 唯一更新点：选一个词（题材区切换 / 搜索框只加不减） ---

    private val anime = SearchCriteria(subjectType = SubjectType.ANIME)

    private fun option(type: SubjectType, dimension: SearchDimension, label: String) =
        searchDimensionsFor(type).single { it.dimension == dimension }.options.single { it.label == label }

    @Test
    fun pickWord_切换_自由词再点一次即取消() {
        val picked = anime.pickWord("异世界", WordPick.TOGGLE)
        assertEquals(listOf(TagFilter.User("异世界")), picked.filters.tags)
        assertTrue(picked.isWordSelected("异世界"))

        val cancelled = picked.pickWord("异世界", WordPick.TOGGLE)
        assertEquals(emptyList<TagFilter>(), cancelled.filters.tags)
        assertTrue(!cancelled.isWordSelected("异世界"))
    }

    @Test
    fun pickWord_切换_维度取值再点一次即取消() {
        val comedy = option(SubjectType.ANIME, SearchDimension.GENRE, "喜剧")
        val picked = anime.pickWord("喜剧", WordPick.TOGGLE)
        assertEquals(mapOf(SearchDimension.GENRE to listOf(comedy)), picked.filters.dimensions)
        assertEquals(anime, picked.pickWord("喜剧", WordPick.TOGGLE))
    }

    @Test
    fun pickWord_只加不减_已选的自由词与维度取值再选一次保持不变() {
        val picked = anime.pickWord("异世界", WordPick.ADD).pickWord("喜剧", WordPick.ADD)
        assertEquals(picked, picked.pickWord("异世界", WordPick.ADD))
        assertEquals(picked, picked.pickWord("喜剧", WordPick.ADD))
        assertEquals(listOf(TagFilter.User("异世界")), picked.filters.tags)
    }

    @Test
    fun pickWord_只加不减_单选维度照旧替换() {
        val ova = option(SubjectType.ANIME, SearchDimension.CATEGORY, "OVA")
        val replaced = anime.pickWord("TV", WordPick.ADD).pickWord("OVA", WordPick.ADD)
        assertEquals(mapOf(SearchDimension.CATEGORY to listOf(ova)), replaced.filters.dimensions)
    }

    @Test
    fun pickWord_空白输入原样返回() {
        assertEquals(anime, anime.pickWord("  ", WordPick.ADD))
        assertEquals(anime, anime.pickWord("", WordPick.TOGGLE))
        assertTrue(!anime.isWordSelected(" "))
    }
}
