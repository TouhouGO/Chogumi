package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.searchDimensionTitleRes
import kotlin.test.Test
import kotlin.test.assertEquals

/** 搜索条件与筛选维度的叫法：按来源、题材区与条目类型给。 */
class SearchConditionTitleTest {

    @Test
    fun `条件叫法按来源与题材区给_题材区里的用户标签标题材`() {
        val anime = SearchCriteria(subjectType = SubjectType.ANIME)
        // 题材区里的用户标签（如动画加进题材区的「治愈」）标「题材」
        assertEquals(R.string.search_condition_theme, anime.conditionTitleRes(TagFilter.User("治愈")))
        // 题材区以外的用户标签标「标签」
        assertEquals(R.string.search_dim_tag, anime.conditionTitleRes(TagFilter.User("菅野洋子")))
        // 落不进维度的「分类」标签也标「标签」（它没能放进任何维度，不冒用维度叫法）
        assertEquals(R.string.search_dim_tag, anime.conditionTitleRes(TagFilter.Public("TV")))
        // 分类标签即便与题材区的词同名，也不算题材区的词
        assertEquals(R.string.search_dim_tag, anime.conditionTitleRes(TagFilter.Public("奇幻")))
    }

    @Test
    fun `维度标题按类型给叫法_同一维度不同类型不同名`() {
        // GENRE 在动画是题材、游戏是玩法、音乐是形式
        assertEquals(R.string.search_dim_genre, searchDimensionTitleRes(SubjectType.ANIME, SearchDimension.GENRE))
        assertEquals(R.string.search_dim_genre_game, searchDimensionTitleRes(SubjectType.GAME, SearchDimension.GENRE))
        assertEquals(R.string.search_dim_genre_music, searchDimensionTitleRes(SubjectType.MUSIC, SearchDimension.GENRE))
        // CATEGORY 统一叫形式、SERIES 叫系列或单本、PUBLICATION_STATUS 叫连载状态
        assertEquals(R.string.search_dim_category, searchDimensionTitleRes(SubjectType.ANIME, SearchDimension.CATEGORY))
        assertEquals(R.string.search_dim_series, searchDimensionTitleRes(SubjectType.BOOK, SearchDimension.SERIES))
        assertEquals(
            R.string.search_dim_publication_status,
            searchDimensionTitleRes(SubjectType.BOOK, SearchDimension.PUBLICATION_STATUS),
        )
    }
}
