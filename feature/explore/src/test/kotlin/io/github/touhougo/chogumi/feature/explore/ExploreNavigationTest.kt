package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.model.ExploreEntry
import io.github.touhougo.chogumi.core.model.ExploreTitle
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.concreteOrNull
import io.github.touhougo.chogumi.core.model.exploreEntriesFor
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreNavigationTest {

    @Test
    fun `固定入口正确交出导航语义`() {
        var calendars = 0
        val searches = mutableListOf<Triple<SearchCriteria, String?, Boolean>>()
        val onSearch = { criteria: SearchCriteria, title: String?, openFilters: Boolean ->
            searches += Triple(criteria, title, openFilters)
        }

        // 1. Calendar
        openExploreEntry(ExploreEntry.Calendar, "ignored", onSearch) { calendars++ }
        assertEquals(1, calendars)
        assertEquals(emptyList<Triple<SearchCriteria, String?, Boolean>>(), searches)

        // 2. OpenFilters：带上该分类的条件，并自动展开面板
        val filtersCriteria = SearchCriteria(subjectType = SubjectType.ANIME)
        openExploreEntry(ExploreEntry.OpenFilters(filtersCriteria), "ignored", onSearch) { error("筛选入口不是放送表") }
        assertEquals(Triple(filtersCriteria, null, true), searches.removeLast())

        // 3. Search
        val criteria = SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.RANK)
        val searchEntry = ExploreEntry.Search(ExploreTitle.Ranking, criteria)
        openExploreEntry(searchEntry, "排行榜标题", onSearch) { error("搜索入口不是放送表") }
        assertEquals(Triple(criteria, "排行榜标题", false), searches.removeLast())
        assertEquals("每个入口只打开一次", emptyList<Triple<SearchCriteria, String?, Boolean>>(), searches)
    }

    @Test
    fun `每个分类的全部筛选入口带上该分类的条件`() {
        val today = LocalDate.of(2026, 10, 1)
        val types = SubjectType.entries.filter { it.concreteOrNull() != null }

        val searches = mutableListOf<Triple<SearchCriteria, String?, Boolean>>()
        for (type in types) {
            val entry = exploreEntriesFor(type, today)
                .filterIsInstance<ExploreEntry.OpenFilters>()
                .single()
            openExploreEntry(entry, "ignored", { criteria, title, openFilters ->
                searches += Triple(criteria, title, openFilters)
            }) { error("筛选入口不是放送表") }
            assertEquals("$type 的「全部筛选」必须带上只含该分类的条件", SearchCriteria(subjectType = type), searches.last().first)
            assertNull(searches.last().second)
            assertEquals(true, searches.last().third)
        }
    }
}
