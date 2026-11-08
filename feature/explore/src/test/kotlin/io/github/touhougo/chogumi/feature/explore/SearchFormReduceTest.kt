package io.github.touhougo.chogumi.feature.explore

import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.effectiveSort
import io.github.touhougo.chogumi.core.model.searchDimensionsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFormReduceTest {

    private val a = SearchCriteria(
        query = "eva",
        subjectType = SubjectType.ANIME,
        sort = SearchSort.RANK,
        filters = SearchFilters(tags = listOf(TagFilter.Public("科幻")), airtime = SearchAirtime(2010, 2010)),
    )

    /** 面板路径：打开 → 改草稿 → 保存。返回保存这一步的归约。 */
    private fun saveViaPanel(form: SearchForm, vararg edits: SearchIntent): SearchReduction {
        var f = reduce(form, SearchIntent.FiltersOpened).form
        for (edit in edits) f = reduce(f, edit).form
        return reduce(f, SearchIntent.FiltersSaved)
    }

    // --- 草稿隔离 ---

    @Test
    fun 草稿隔离_改时间只动目标字段且不读输入草稿() {
        val form = SearchForm(draftQuery = " 未提交的新词 ", applied = a)
        val ranged = saveViaPanel(form, SearchIntent.DraftAirtimeChanged(SearchAirtime(2024, 2024, 10)))
        assertEquals("eva", ranged.form.applied.query)
        assertEquals(" 未提交的新词 ", ranged.form.draftQuery)
        assertEquals(SearchAirtime(2024, 2024, 10), ranged.form.applied.filters.airtime)
        assertTrue(ranged.submit)
        assertFalse(ranged.writeHistory)
    }

    // --- 显式提交 ---

    @Test
    fun 显式提交_用草稿trim并保留其他条件() {
        val form = SearchForm(draftQuery = " 未提交的新词 ", applied = a)
        val submitted = reduce(form, SearchIntent.SearchSubmitted)
        assertEquals("未提交的新词", submitted.form.applied.query)
        assertEquals(a.filters, submitted.form.applied.filters)
        assertEquals(a.sort, submitted.form.applied.sort)
        assertTrue(submitted.submit)
        assertTrue(submitted.writeHistory)
    }

    @Test
    fun 显式提交_相同条件仍提交() {
        val form = SearchForm(draftQuery = "eva", applied = a)
        val submitted = reduce(form, SearchIntent.SearchSubmitted)
        assertTrue(submitted.submit)
    }

    // --- 重复事件无副作用 ---

    @Test
    fun 重复排序_不提交() {
        val form = SearchForm(applied = a)
        assertEquals(SearchReduction(form), reduce(form, SearchIntent.SortChanged(SearchSort.RANK)))
    }

    // --- QueryChanged / QueryCleared ---

    @Test
    fun 输入变化_不提交() {
        val form = SearchForm(draftQuery = "", applied = a)
        val r = reduce(form, SearchIntent.QueryChanged("新词"))
        assertEquals("新词", r.form.draftQuery)
        assertEquals(a, r.form.applied)
        assertFalse(r.submit)
    }

    @Test
    fun 清除输入_清草稿与已提交词保留其他() {
        val form = SearchForm(draftQuery = "草稿", applied = a)
        val r = reduce(form, SearchIntent.QueryCleared)
        assertEquals("", r.form.draftQuery)
        assertEquals("", r.form.applied.query)
        assertEquals(a.sort, r.form.applied.sort)
        assertEquals(a.filters, r.form.applied.filters)
    }

    @Test
    fun 清除输入_applied已空时零动作() {
        val form = SearchForm(draftQuery = "", applied = SearchCriteria(subjectType = SubjectType.ANIME))
        val r = reduce(form, SearchIntent.QueryCleared)
        assertFalse(r.submit)
        assertEquals(form.applied, r.form.applied)
    }

    // --- HistoryClicked ---

    @Test
    fun 历史点击_保留附加条件且不写历史() {
        val form = SearchForm(draftQuery = "draft", applied = a)
        val r = reduce(form, SearchIntent.HistoryClicked(" 钢炼 "))
        assertEquals("钢炼", r.form.applied.query)
        assertEquals(" 钢炼 ", r.form.draftQuery)
        assertEquals(a.filters, r.form.applied.filters)
        assertTrue(r.submit)
        assertFalse(r.writeHistory)
    }

    // --- 非法输入 ---

    @Test
    fun 非法时间_保存失败_已提交条件不变且面板关闭() {
        val form = SearchForm(applied = a)
        val r = saveViaPanel(form, SearchIntent.DraftAirtimeChanged(SearchAirtime(2024, 2024, 13)))
        assertEquals(form, r.form)
        assertFalse(r.submit)
        assertTrue(r.errors.isNotEmpty())
    }

    @Test
    fun 非法类型_保留旧form且报错() {
        val form = SearchForm(applied = a)
        val r = reduce(form, SearchIntent.TypeChanged(SubjectType.UNKNOWN))
        assertEquals(form, r.form)
        assertFalse(r.submit)
        assertTrue(r.errors.isNotEmpty())
    }

    // --- 维度 ---

    private fun opt(type: SubjectType, dimension: SearchDimension, label: String) =
        searchDimensionsFor(type).single { it.dimension == dimension }.options.single { it.label == label }

    @Test
    fun 维度选择_点选触发提交_再点已选值取消() {
        val tv = opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val form = SearchForm(draftQuery = "未提交", applied = a)

        val selected = saveViaPanel(form, SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, tv))
        assertTrue(selected.submit)
        assertFalse(selected.writeHistory)
        assertEquals(mapOf(SearchDimension.CATEGORY to listOf(tv)), selected.form.applied.filters.dimensions)
        assertEquals("eva", selected.form.applied.query)
        assertEquals("未提交", selected.form.draftQuery)

        val cleared = saveViaPanel(selected.form, SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, tv))
        assertTrue(cleared.submit)
        assertTrue(cleared.form.applied.filters.dimensions.isEmpty())
    }

    @Test
    fun 多选维度_再点一次是同维度取消() {
        val sciFi = opt(SubjectType.ANIME, SearchDimension.GENRE, "科幻")
        val comedy = opt(SubjectType.ANIME, SearchDimension.GENRE, "喜剧")
        val added = saveViaPanel(
            SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME)),
            SearchIntent.DraftDimensionToggled(SearchDimension.GENRE, sciFi),
            SearchIntent.DraftDimensionToggled(SearchDimension.GENRE, comedy),
        )
        assertEquals(listOf(sciFi, comedy), added.form.applied.filters.dimensions[SearchDimension.GENRE])

        val removed = saveViaPanel(added.form, SearchIntent.DraftDimensionToggled(SearchDimension.GENRE, comedy))
        assertEquals(listOf(sciFi), removed.form.applied.filters.dimensions[SearchDimension.GENRE])
    }

    @Test
    fun 单选维度_点别的值直接替换() {
        val tv = opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val ova = opt(SubjectType.ANIME, SearchDimension.CATEGORY, "OVA")
        val replaced = saveViaPanel(
            SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME)),
            SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, tv),
            SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, ova),
        )
        assertEquals(listOf(ova), replaced.form.applied.filters.dimensions[SearchDimension.CATEGORY])
    }

    @Test
    fun 选词_题材区切换可取消自由词_搜索框只加不减_只改草稿() {
        val opened = reduce(SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME)), SearchIntent.FiltersOpened).form

        val toggled = reduce(opened, SearchIntent.DraftWordPicked("异世界", WordPick.TOGGLE))
        assertFalse("选词只改草稿，不发请求", toggled.submit)
        assertEquals(listOf(TagFilter.User("异世界")), toggled.form.filterDraft?.tags)
        val untoggled = reduce(toggled.form, SearchIntent.DraftWordPicked("异世界", WordPick.TOGGLE)).form
        assertEquals(emptyList<TagFilter>(), untoggled.filterDraft?.tags)

        val added = reduce(opened, SearchIntent.DraftWordPicked("异世界", WordPick.ADD)).form
        val addedAgain = reduce(added, SearchIntent.DraftWordPicked("异世界", WordPick.ADD)).form
        assertEquals(listOf(TagFilter.User("异世界")), addedAgain.filterDraft?.tags)
        assertTrue("已提交条件不变", addedAgain.applied.filters.tags.isEmpty())
    }

    @Test
    fun 维度选择_不属于当前类型时报错且表单不变() {
        val comic = opt(SubjectType.BOOK, SearchDimension.CATEGORY, "漫画")
        val form = SearchForm(applied = a)
        val result = saveViaPanel(form, SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, comic))
        assertEquals(a, result.form.applied)
        assertFalse(result.submit)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun 切换类型_分类随之落位_同类型不动_时间保留() {
        val tv = opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val withDim = saveViaPanel(SearchForm(applied = a), SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, tv)).form

        val sameType = reduce(withDim, SearchIntent.TypeChanged(SubjectType.ANIME))
        assertEquals(mapOf(SearchDimension.CATEGORY to listOf(tv)), sameType.form.applied.filters.dimensions)
        assertFalse(sameType.submit)

        // 游戏词典里没有 TV 与科幻：都保留为分类，不丢
        val switched = reduce(withDim, SearchIntent.TypeChanged(SubjectType.GAME))
        assertTrue(switched.submit)
        assertTrue(switched.form.applied.filters.dimensions.isEmpty())
        assertEquals(listOf(TagFilter.Public("TV"), TagFilter.Public("科幻")), switched.form.applied.filters.tags)
        assertEquals(a.filters.airtime, switched.form.applied.filters.airtime)

        // 「全部」：同样保留为分类，跨类型搜索
        val all = reduce(withDim, SearchIntent.TypeChanged(null))
        assertTrue(all.errors.isEmpty())
        assertTrue(all.form.applied.filters.dimensions.isEmpty())
        assertEquals(listOf(TagFilter.Public("TV"), TagFilter.Public("科幻")), all.form.applied.filters.tags)
    }

    // --- 筛选面板草稿与标签管理 ---

    @Test
    fun 面板_打开复制已提交条件_编辑只改草稿不提交() {
        val tv = opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val opened = reduce(SearchForm(applied = a), SearchIntent.FiltersOpened)
        assertEquals(a.filters, opened.form.filterDraft)
        assertFalse(opened.submit)

        val edits = listOf(
            SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, tv),
            SearchIntent.DraftAirtimeChanged(SearchAirtime(2024, 2024, 10)),
        )
        var form = opened.form
        for (edit in edits) {
            val r = reduce(form, edit)
            assertFalse("$edit", r.submit)
            assertEquals("$edit 不得改已提交条件", a, r.form.applied)
            form = r.form
        }
        assertEquals(mapOf(SearchDimension.CATEGORY to listOf(tv)), form.filterDraft?.dimensions)
        assertEquals(SearchAirtime(2024, 2024, 10), form.filterDraft?.airtime)
    }

    @Test
    fun 面板_移除标签只改草稿不提交() {
        val form = SearchForm(applied = a)
        val opened = reduce(form, SearchIntent.FiltersOpened).form
        val tagToRemove = TagFilter.Public("科幻")
        val removed = reduce(opened, SearchIntent.DraftTagRemoved(tagToRemove))
        assertFalse(removed.submit)
        assertEquals(a, removed.form.applied)
        assertEquals(emptyList<TagFilter>(), removed.form.filterDraft?.tags)
    }

    @Test
    fun 面板_同名跨来源标签_移除只删对应来源那一个() {
        val publicSciFi = TagFilter.Public("科幻")
        val userSciFi = TagFilter.User("科幻")
        val form = SearchForm(
            applied = a.copy(filters = SearchFilters(tags = listOf(publicSciFi, userSciFi))),
        )
        val opened = reduce(form, SearchIntent.FiltersOpened).form

        // 移除 Public("科幻")，User("科幻") 仍保留在草稿中
        val removedPublic = reduce(opened, SearchIntent.DraftTagRemoved(publicSciFi))
        assertFalse(removedPublic.submit)
        assertEquals(listOf(userSciFi), removedPublic.form.filterDraft?.tags)

        // 移除 User("科幻")，Public("科幻") 仍保留在草稿中
        val removedUser = reduce(opened, SearchIntent.DraftTagRemoved(userSciFi))
        assertFalse(removedUser.submit)
        assertEquals(listOf(publicSciFi), removedUser.form.filterDraft?.tags)
    }

    @Test
    fun 面板_移除不存在的标签是空操作() {
        val form = SearchForm(applied = a)
        val opened = reduce(form, SearchIntent.FiltersOpened).form
        val nonExistent = TagFilter.User("不存在的标签")
        val r = reduce(opened, SearchIntent.DraftTagRemoved(nonExistent))
        assertFalse(r.submit)
        assertEquals(opened.filterDraft?.tags, r.form.filterDraft?.tags)
    }

    @Test
    fun 面板_保存采用草稿中的标签() {
        val form = SearchForm(applied = a)
        val tagToRemove = TagFilter.Public("科幻")
        val r = saveViaPanel(form, SearchIntent.DraftTagRemoved(tagToRemove))
        assertTrue(r.submit)
        assertEquals(emptyList<TagFilter>(), r.form.applied.filters.tags)
        assertNull(r.form.filterDraft)
    }

    @Test
    fun 面板_保存一次写回并提交_关闭面板() {
        val tv = opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val r = saveViaPanel(
            SearchForm(applied = a),
            SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, tv),
            SearchIntent.DraftAirtimeChanged(SearchAirtime(2024, 2024, 10)),
        )
        assertTrue(r.submit)
        assertEquals(null, r.form.filterDraft)
        assertEquals(mapOf(SearchDimension.CATEGORY to listOf(tv)), r.form.applied.filters.dimensions)
        assertEquals(SearchAirtime(2024, 2024, 10), r.form.applied.filters.airtime)
        assertEquals(a.filters.tags, r.form.applied.filters.tags)
    }

    @Test
    fun 面板_未改动直接保存_关闭且不提交() {
        val r = saveViaPanel(SearchForm(applied = a))
        assertFalse(r.submit)
        assertEquals(null, r.form.filterDraft)
        assertEquals(a, r.form.applied)
    }

    @Test
    fun 面板_关闭丢弃草稿_已提交条件不变() {
        val tv = opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        var form = reduce(SearchForm(applied = a), SearchIntent.FiltersOpened).form
        form = reduce(form, SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, tv)).form
        val dismissed = reduce(form, SearchIntent.FiltersDismissed)
        assertFalse(dismissed.submit)
        assertEquals(null, dismissed.form.filterDraft)
        assertEquals(a, dismissed.form.applied)
    }

    @Test
    fun 面板_清空连同标签一并清空_保存后才生效() {
        val tv = opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val withDim = saveViaPanel(SearchForm(applied = a), SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, tv)).form
        var form = reduce(withDim, SearchIntent.FiltersOpened).form
        val cleared = reduce(form, SearchIntent.DraftCleared)
        assertFalse(cleared.submit)
        assertEquals(withDim.applied, cleared.form.applied)
        assertEquals(SearchFilters(), cleared.form.filterDraft)

        form = cleared.form
        val saved = reduce(form, SearchIntent.FiltersSaved)
        assertTrue(saved.submit)
        assertEquals(SearchFilters(), saved.form.applied.filters)
        assertEquals(a.sort, saved.form.applied.sort)
    }

    @Test
    fun 面板_未打开时的草稿事件与保存都是空操作() {
        val form = SearchForm(applied = a)
        val tv = opt(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        assertEquals(SearchReduction(form), reduce(form, SearchIntent.DraftTagRemoved(TagFilter.Public("科幻"))))
        assertEquals(SearchReduction(form), reduce(form, SearchIntent.DraftDimensionToggled(SearchDimension.CATEGORY, tv)))
        assertEquals(SearchReduction(form), reduce(form, SearchIntent.DraftCleared))
        assertEquals(SearchReduction(form), reduce(form, SearchIntent.FiltersSaved))
    }

    // --- 排序：选择始终保留，当前不可用时临时按收藏 ---

    @Test
    fun 排序_选择写入并提交_重复选择不提交() {
        val form = SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME))
        val r = reduce(form, SearchIntent.SortChanged(SearchSort.TRENDS))
        assertTrue(r.submit)
        assertEquals(SearchSort.TRENDS, r.form.applied.sort)
        assertFalse(reduce(r.form, SearchIntent.SortChanged(SearchSort.TRENDS)).submit)
    }

    @Test
    fun 排序_输关键词后按收藏_清掉关键词恢复原选择() {
        val browsing = SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.RANK))
        val searched = reduce(browsing.copy(draftQuery = "eva"), SearchIntent.SearchSubmitted).form
        assertEquals(SearchSort.RANK, searched.applied.sort)
        assertEquals(SearchSort.COLLECTS, searched.applied.effectiveSort())

        val back = reduce(searched, SearchIntent.QueryCleared).form
        assertEquals(SearchSort.RANK, back.applied.effectiveSort())
    }

    // --- 路由带来的名字：条件一变就作废（唯一判定点在 reduce） ---

    /** 带名字进搜索页：探索页的「排行榜」= 动画 + 排名排序。 */
    private fun named(tag: String? = "排行榜") = SearchForm(
        applied = SearchCriteria(subjectType = SubjectType.ANIME, sort = SearchSort.RANK),
        title = tag,
    )

    @Test
    fun 名字_不动条件时保留() {
        val form = named()
        // 只看不改：打开再关掉面板，条件没变，名字还在。
        val opened = reduce(form, SearchIntent.FiltersOpened).form
        assertEquals("排行榜", opened.title)
        val closed = reduce(opened, SearchIntent.FiltersDismissed).form
        assertEquals("排行榜", closed.title)
    }

    @Test
    fun 名字_改关键词后消失() {
        val typed = reduce(named().copy(draftQuery = "eva"), SearchIntent.SearchSubmitted)
        assertNull("改了关键词，名字就对不上了", typed.form.title)
    }

    @Test
    fun 名字_换排序后消失() {
        val changed = reduce(named(), SearchIntent.SortChanged(SearchSort.TRENDS))
        assertNull(changed.form.title)
    }

    @Test
    fun 名字_换类型后消失() {
        val changed = reduce(named(), SearchIntent.TypeChanged(SubjectType.GAME))
        assertNull(changed.form.title)
    }

    @Test
    fun 名字_面板保存了不同条件后消失() {
        val saved = saveViaPanel(named(), SearchIntent.DraftDimensionToggled(
            SearchDimension.GENRE,
            searchDimensionsFor(SubjectType.ANIME).single { it.dimension == SearchDimension.GENRE }.options.first(),
        ))
        assertNull(saved.form.title)
    }

    @Test
    fun 名字_面板保存相同条件后仍在() {
        // 打开面板什么都没改就保存：条件一个字节没变，名字不该被清掉。
        val saved = saveViaPanel(named())
        assertEquals("排行榜", saved.form.title)
    }

    @Test
    fun 名字_清关键词回到原条件时不复现_因为条件已变过一次() {
        // 名字失效是不可逆的：先加词再清掉，条件回到了原样，但名字已经丢过——
        // 这条钉住「以条件为准的一次性判定」，避免有人改成「条件相等就恢复名字」
        // （那会让名字在用户已经改过一轮之后又冒出来）。
        val search = reduce(named(), SearchIntent.TypeChanged(SubjectType.GAME))
        assertNull(search.form.title)
        val back = reduce(search.form, SearchIntent.TypeChanged(SubjectType.ANIME))
        assertNull(back.form.title)
    }
}
