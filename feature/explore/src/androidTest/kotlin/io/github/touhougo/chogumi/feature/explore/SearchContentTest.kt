package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagFilter
import io.github.touhougo.chogumi.core.model.dimensionMetaTags
import io.github.touhougo.chogumi.core.model.searchDimensionsFor
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 整页行为测试：真实组件 + 真实 reduce（[ReducingSearchActions]）。
 * 网络是否发出、请求体全文由 JVM 的 SearchViewModelTest 证明，不在此混报。
 */
class SearchContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setContent(actions: ReducingSearchActions, history: List<String> = emptyList()) {
        compose.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    SearchContent(
                        currentYear = FIXTURE_TODAY.year,
                        state = SearchUiState(
                            form = actions.form,
                            history = history,
                            pinned = actions.pinned,
                            renaming = actions.renaming,
                        ),
                        actions = actions,
                        onSubjectClick = {},
                        onBack = {},
                    )
                }
            }
        }
    }

    private fun option(type: SubjectType, dimension: SearchDimension, label: String) =
        searchDimensionsFor(type).single { it.dimension == dimension }.options.single { it.label == label }

    private fun openPanel() {
        compose.onNodeWithTag("SearchOpenFilters").performClick()
        compose.onNodeWithTag("SearchFilterPanel").assertIsDisplayed()
    }

    /** 经右下角类型 FAB 菜单切换条目类型。 */
    private fun pickType(type: SubjectType?) {
        compose.onNodeWithTag("SelectionFab").performClick()
        compose.onNodeWithTag("ExploreSearchType-${type?.name ?: "ALL"}").performClick()
    }

    private fun typeFabDescription(labelRes: Int) =
        context.getString(R.string.search_type_fab, context.getString(labelRes))

    @Test
    fun 空态_类型与筛选可见_无排序组_无清空与已生效条件_筛选按钮无数量() {
        val actions = ReducingSearchActions()
        setContent(actions)

        // 类型在右下角 FAB：收起时只露出 FAB，描述当前类型；菜单项不在界面上
        compose.onNodeWithTag("SelectionFab").assertIsDisplayed()
            .assertContentDescriptionEquals(typeFabDescription(R.string.all))
        // 原生 FAB 菜单收起后菜单项仍在语义树里（宽 0、语义被清空），故断言「不显示」
        compose.onNodeWithTag("ExploreSearchType-ALL").assertIsNotDisplayed()
        compose.onNodeWithTag("SearchOpenFilters").assertIsDisplayed()
            .assertContentDescriptionEquals(context.getString(R.string.search_filters))
        // 搜索框右侧无文字时只有筛选按钮，无清空无搜索按钮（最多两个）
        compose.onNodeWithTag("SearchClearQuery").assertDoesNotExist()
        compose.onNodeWithTag("SearchSubmit").assertDoesNotExist()
        // 空态没有可排序的结果：排序组不显示
        compose.onNodeWithTag("SearchSortGroup").assertDoesNotExist()
        // 空态：无条件行
        compose.onNodeWithTag("SearchActiveConditions").assertDoesNotExist()
        assertEquals(0, actions.calls.size)
    }

    @Test
    fun 搜索页不存在页面级条件行_有条件时条件行依然不存在_靠筛选按钮与输入框提示表达() {
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val actions = ReducingSearchActions(SearchForm())
        setContent(actions)

        // 空态没有条件行
        compose.onNodeWithTag("SearchActiveConditions").assertDoesNotExist()

        // 单个维度取值：页面级条件行依然不存在（已挪入筛选面板内），筛选按钮显示 1 项已选
        actions.form = SearchForm(
            applied = SearchCriteria(
                subjectType = SubjectType.ANIME,
                filters = SearchFilters(dimensions = mapOf(SearchDimension.CATEGORY to listOf(tv))),
            ),
        )
        compose.onNodeWithTag("SearchActiveConditions").assertDoesNotExist()
        compose.onNodeWithTag("SearchOpenFilters").assertContentDescriptionEquals(
            context.getString(R.string.search_filters_selected, 1),
        )
    }

    @Test
    fun 多条件生效时_页面级条件行不存在_排序只剩排名与收藏() {
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    filters = SearchFilters(
                        tags = listOf(TagFilter.User("治愈"), TagFilter.User("菅野洋子")),
                        airtime = SearchAirtime(1990, 1999),
                    ),
                ),
            ),
        )
        setContent(actions)
        // 页面级条件行不渲染（无 SearchActiveConditions）
        compose.onNodeWithTag("SearchActiveConditions").assertDoesNotExist()
        // 筛选按钮指示 3 项已选
        compose.onNodeWithTag("SearchOpenFilters").assertContentDescriptionEquals(
            context.getString(R.string.search_filters_selected, 3),
        )
        // 多标签 + 时间 → 只剩排名、收藏
        compose.onNodeWithTag("SearchSort-RANK").assertIsDisplayed()
        compose.onNodeWithTag("SearchSort-COLLECTS").assertIsDisplayed()
        compose.onNodeWithTag("SearchSort-TRENDS").assertDoesNotExist()
    }

    @Test
    fun 面板里选维度_保存后按钮计数_无条件行与清空按钮_翻译出实测标签() {
        val actions = ReducingSearchActions()
        setContent(actions)
        pickType(SubjectType.ANIME)
        openPanel()

        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        // 满高面板半展开时只排屏内的行：类型分区在题材区下面，先滚到它
        compose.onNodeWithTag("SearchFilterPanel")
            .performScrollToNode(hasTestTag(dimensionOptionTag(SearchDimension.CATEGORY, tv)))
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.CATEGORY, tv)).performClick()
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.CATEGORY, tv)).assertIsSelected()
        // 未保存：外面没有生效条件
        compose.runOnIdle { assertTrue(actions.form.applied.filters.dimensions.isEmpty()) }

        compose.onNodeWithTag("SearchPanelSave").performClick()
        compose.onNodeWithTag("SearchFilterPanel").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(mapOf(SearchDimension.CATEGORY to listOf(tv)), actions.form.applied.filters.dimensions)
            // 请求用的 meta_tags 只由唯一翻译点派生（映射层同样调用它）
            assertEquals(listOf("TV"), dimensionMetaTags(actions.form.applied.subjectType, actions.form.applied.filters.dimensions))
        }
        // 页面级条件行不渲染，筛选按钮指示 1 项已选
        compose.onNodeWithTag("SearchActiveConditions").assertDoesNotExist()
        compose.onNodeWithTag("SearchOpenFilters").assertContentDescriptionEquals(
            context.getString(R.string.search_filters_selected, 1),
        )
    }

    @Test
    fun 面板里删掉标签并保存_applied不再有该标签_提示文字与图标恢复() {
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子"))),
                ),
            ),
        )
        setContent(actions)

        // 有标签时搜索框提示文字为「在标签「菅野洋子」下搜索」，筛选按钮有计数
        val hintText = context.getString(R.string.search_hint_single, "菅野洋子")
        compose.onNodeWithTag("SearchQuery").assert(hasText(hintText))
        compose.onNodeWithTag("SearchOpenFilters").assertContentDescriptionEquals(
            context.getString(R.string.search_filters_selected, 1),
        )

        openPanel()
        compose.onNodeWithTag("SearchDraftTag-USER:菅野洋子").assertIsDisplayed().performClick()
        compose.onNodeWithTag("SearchDraftTag-USER:菅野洋子").assertDoesNotExist()

        // 点击保存后写回 applied
        compose.onNodeWithTag("SearchPanelSave").performClick()
        compose.runOnIdle {
            assertTrue(actions.form.applied.filters.tags.isEmpty())
        }

        // 提示文字回到跟随类型的写法（仍是动画），筛选按钮恢复无条件态
        val typedHint = context.getString(R.string.search_hint_typed, context.getString(R.string.anime))
        compose.onNodeWithTag("SearchQuery").assert(hasText(typedHint))
        compose.onNodeWithTag("SearchOpenFilters").assertContentDescriptionEquals(
            context.getString(R.string.search_filters),
        )
    }

    @Test
    fun 面板清空并保存_标签与全部条件清空() {
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    filters = SearchFilters(
                        dimensions = mapOf(SearchDimension.CATEGORY to listOf(tv)),
                        tags = listOf(TagFilter.User("原创")),
                        airtime = SearchAirtime(2024, 2024, 10),
                    ),
                ),
            ),
        )
        setContent(actions)

        openPanel()
        compose.onNodeWithTag("SearchDraftTag-USER:原创").assertIsDisplayed()
        compose.onNodeWithTag("SearchPanelClear").performClick()

        // 清空后草稿全部清除
        compose.onNodeWithTag("SearchDraftTag-USER:原创").assertDoesNotExist()

        compose.onNodeWithTag("SearchPanelSave").performClick()
        compose.runOnIdle {
            assertEquals(SearchFilters(), actions.form.applied.filters)
        }
    }

    @Test
    fun 提示文字_单标签多标签与无标签状态切换() {
        val actions = ReducingSearchActions()
        setContent(actions)

        val defaultHint = context.getString(R.string.search_hint)
        compose.onNodeWithTag("SearchQuery").assert(hasText(defaultHint))

        // 单分类状态（PUBLIC）
        actions.form = SearchForm(
            applied = SearchCriteria(
                filters = SearchFilters(tags = listOf(TagFilter.Public("原创"))),
            ),
        )
        compose.onNodeWithTag("SearchQuery").assert(hasText(context.getString(R.string.search_hint_single, "原创")))

        // 单标签状态（USER）
        actions.form = SearchForm(
            applied = SearchCriteria(
                filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子"))),
            ),
        )
        compose.onNodeWithTag("SearchQuery").assert(hasText(context.getString(R.string.search_hint_single, "菅野洋子")))

        // 多条件状态（中性表述）
        actions.form = SearchForm(
            applied = SearchCriteria(
                filters = SearchFilters(tags = listOf(TagFilter.User("百合"), TagFilter.User("日常"))),
            ),
        )
        compose.onNodeWithTag("SearchQuery").assert(hasText(context.getString(R.string.search_hint_multi_tag, 2)))

        // 单个维度也写明它属于哪个分区：动画 GENRE 叫「题材」
        val mystery = option(SubjectType.ANIME, SearchDimension.GENRE, "悬疑")
        actions.form = SearchForm(
            applied = SearchCriteria(
                subjectType = SubjectType.ANIME,
                filters = SearchFilters(dimensions = mapOf(SearchDimension.GENRE to listOf(mystery))),
            ),
        )
        compose.onNodeWithTag("SearchQuery").assert(hasText(context.getString(R.string.search_hint_single, "悬疑")))

        // 两个标签 + 年份：提示里的数量与筛选按钮上的数量同源（都是 3）
        actions.form = SearchForm(
            applied = SearchCriteria(
                filters = SearchFilters(
                    tags = listOf(TagFilter.User("百合"), TagFilter.User("日常")),
                    airtime = SearchAirtime(2020, 2020),
                ),
            ),
        )
        compose.onNodeWithTag("SearchQuery").assert(hasText(context.getString(R.string.search_hint_multi_tag, 3)))
        compose.onNodeWithTag("SearchOpenFilters").assertContentDescriptionEquals(
            context.getString(R.string.search_filters_selected, 3),
        )

        // 只有时间：跟随类型
        actions.form = SearchForm(
            applied = SearchCriteria(subjectType = SubjectType.BOOK, filters = SearchFilters(airtime = SearchAirtime(2020, 2020))),
        )
        compose.onNodeWithTag("SearchQuery").assert(
            hasText(context.getString(R.string.search_hint_typed, context.getString(R.string.book))),
        )
    }

    @Test
    fun 面板_同名跨来源在两个区各显示一次_读屏与标签正常() {
        val publicSciFi = TagFilter.Public("科幻")
        val userSciFi = TagFilter.User("科幻")
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    filters = SearchFilters(tags = listOf(publicSciFi, userSciFi)),
                ),
            ),
        )
        setContent(actions)
        openPanel()

        // 「其他已选」一行里两个来源的同名标签各显示一次；落不进维度的分类标签与用户标签都叫「标签」
        val expectedDesc = context.getString(
            R.string.search_remove_condition,
            context.getString(R.string.search_condition_labeled, context.getString(R.string.search_dim_tag), "科幻"),
        )
        compose.onNodeWithText(context.getString(R.string.search_other_selected)).assertIsDisplayed()
        compose.onNodeWithTag("SearchDraftTag-PUBLIC:科幻").assertIsDisplayed()
            .assertContentDescriptionEquals(expectedDesc)
        compose.onNodeWithTag("SearchDraftTag-USER:科幻").assertIsDisplayed()
            .assertContentDescriptionEquals(expectedDesc)
    }

    @Test
    fun 切换类型_分类随之落位_用户标签与时间保留() {
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    filters = SearchFilters(
                        dimensions = mapOf(SearchDimension.CATEGORY to listOf(tv)),
                        tags = listOf(TagFilter.Public("科幻"), TagFilter.User("原创")),
                        airtime = SearchAirtime(2010, 2010),
                    ),
                ),
            ),
        )
        setContent(actions)

        pickType(SubjectType.BOOK)

        // 书籍词典有科幻（题材）、没有 TV：科幻落成维度，TV 保留为分类
        compose.runOnIdle {
            assertEquals(SubjectType.BOOK, actions.form.applied.subjectType)
            assertEquals(
                mapOf(SearchDimension.THEME to listOf(option(SubjectType.BOOK, SearchDimension.THEME, "科幻"))),
                actions.form.applied.filters.dimensions,
            )
            assertEquals(listOf(TagFilter.Public("TV"), TagFilter.User("原创")), actions.form.applied.filters.tags)
            assertEquals(SearchAirtime(2010, 2010), actions.form.applied.filters.airtime)
        }
    }

    @Test
    fun 详情分类进来切到全部_条件不丢_跨类型按分类搜索() {
        val mystery = option(SubjectType.ANIME, SearchDimension.GENRE, "悬疑")
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    filters = SearchFilters(dimensions = mapOf(SearchDimension.GENRE to listOf(mystery))),
                ),
            ),
        )
        setContent(actions)

        pickType(null)

        compose.runOnIdle {
            assertEquals(null, actions.form.applied.subjectType)
            assertEquals(listOf(TagFilter.Public("悬疑")), actions.form.applied.filters.tags)
        }
        // 不是空态：提示文字写明分类，排序组仍在（全部下只有排名与收藏）
        compose.onNodeWithTag("SearchQuery").assert(hasText(context.getString(R.string.search_hint_single, "悬疑")))
        compose.onNodeWithTag("SearchSort-RANK").assertIsDisplayed()
        compose.onNodeWithTag("SearchSort-COLLECTS").assertIsDisplayed()
        compose.onNodeWithTag("SearchSort-TRENDS").assertDoesNotExist()
    }

    @Test
    fun 未开始搜索且无历史_正文给出引导_不重复搜索框提示() {
        val actions = ReducingSearchActions()
        setContent(actions)
        compose.onNodeWithText(context.getString(R.string.search_empty_guide)).assertIsDisplayed()
        // 搜索框提示是「全部」的写法，正文不重复同一句
        compose.onNodeWithTag("SearchQuery").assert(hasText(context.getString(R.string.search_hint)))
        compose.onAllNodesWithText(context.getString(R.string.search_hint)).assertCountEquals(1)
    }

    @Test
    fun 排序组_浏览时五项可点_默认收藏选中_选热度只回调一次() {
        val actions = ReducingSearchActions(SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME)))
        setContent(actions)
        for (sort in SearchSort.entries) compose.onNodeWithTag("SearchSort-${sort.name}").assertIsEnabled()
        compose.onNodeWithTag("SearchSort-COLLECTS").assertIsOn()

        compose.onNodeWithTag("SearchSort-TRENDS").performClick()
        compose.onNodeWithTag("SearchSort-TRENDS").assertIsOn()
        compose.onNodeWithTag("SearchSort-COLLECTS").assertIsOff()
        // 再点已选中的项不回调
        compose.onNodeWithTag("SearchSort-TRENDS").performClick()
        compose.runOnIdle {
            assertEquals(SearchSort.TRENDS, actions.form.applied.sort)
            assertEquals(1, actions.calls.count { it == "onSortChange" })
        }
    }

    @Test
    fun 排序组_单标签浏览只出现四项排序_收藏文案为标注数_热度不存在() {
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    sort = SearchSort.TRENDS,
                    filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子"))),
                ),
            ),
        )
        setContent(actions)
        for (sort in listOf(SearchSort.RANK, SearchSort.COLLECTS, SearchSort.DATE, SearchSort.TITLE)) {
            compose.onNodeWithTag("SearchSort-${sort.name}").assertIsEnabled()
        }
        compose.onNodeWithTag("SearchSort-COLLECTS").assertIsOn()
            .assert(hasText(context.getString(R.string.search_sort_tagged)))
        compose.onNodeWithTag("SearchSort-TRENDS").assertDoesNotExist()
    }

    @Test
    fun 排序组_多标签时只出现排名收藏() {
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    sort = SearchSort.TRENDS,
                    filters = SearchFilters(tags = listOf(TagFilter.User("百合"), TagFilter.User("日常"))),
                ),
            ),
        )
        setContent(actions)
        compose.onNodeWithTag("SearchSort-RANK").assertIsEnabled()
        compose.onNodeWithTag("SearchSort-COLLECTS").assertIsEnabled().assertIsOn()
            .assert(hasText(context.getString(R.string.search_sort_collects)))
        for (sort in listOf(SearchSort.TRENDS, SearchSort.DATE, SearchSort.TITLE)) {
            compose.onNodeWithTag("SearchSort-${sort.name}").assertDoesNotExist()
        }
    }

    @Test
    fun 排序组_标签浏览删掉标签并保存后_热度出现_收藏文案恢复() {
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    filters = SearchFilters(tags = listOf(TagFilter.User("菅野洋子"))),
                ),
            ),
        )
        setContent(actions)
        compose.onNodeWithTag("SearchSort-TRENDS").assertDoesNotExist()

        openPanel()
        compose.onNodeWithTag("SearchDraftTag-USER:菅野洋子").performClick()
        compose.onNodeWithTag("SearchPanelSave").performClick()

        for (sort in SearchSort.entries) compose.onNodeWithTag("SearchSort-${sort.name}").assertIsDisplayed()
        compose.onNodeWithTag("SearchSort-COLLECTS").assertIsOn()
            .assert(hasText(context.getString(R.string.search_sort_collects)))
    }

    @Test
    fun 排序组_选中项被隐藏时落到收藏_恢复类型后原选择回来() {
        // 带年份：去掉类型后仍有条件（不是空态），排序组只剩排名与收藏
        val actions = ReducingSearchActions(
            SearchForm(
                applied = SearchCriteria(
                    subjectType = SubjectType.ANIME,
                    filters = SearchFilters(airtime = SearchAirtime(2024, 2024)),
                ),
            ),
        )
        setContent(actions)
        compose.onNodeWithTag("SearchSort-DATE").performClick()
        compose.onNodeWithTag("SearchSort-DATE").assertIsOn()

        // 不选类型只剩排名与收藏：日期消失，选中态落到收藏
        pickType(null)
        compose.onNodeWithTag("SearchSort-DATE").assertDoesNotExist()
        compose.onNodeWithTag("SearchSort-RANK").assertIsDisplayed()
        compose.onNodeWithTag("SearchSort-COLLECTS").assertIsOn()

        // 选回动画：日期重新出现，用户原来的选择仍生效
        pickType(SubjectType.ANIME)
        compose.onNodeWithTag("SearchSort-DATE").assertIsDisplayed().assertIsOn()
    }

    @Test
    fun 排序组_有关键词时不显示() {
        val actions = ReducingSearchActions(SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME)))
        setContent(actions)
        compose.onNodeWithTag("SearchSortGroup").assertIsDisplayed()
        compose.onNodeWithTag("SearchQuery").performTextReplacement("eva")
        compose.onNodeWithTag("SearchQuery").performSemanticsAction(SemanticsActions.OnImeAction) { it() }
        compose.onNodeWithTag("SearchSortGroup").assertDoesNotExist()
    }

    @Test
    fun 结果区左滑_落定后换到下一个排序_只换一次_排序条跟着走() {
        val actions = ReducingSearchActions(SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME)))
        setContent(actions)
        // 默认按收藏：排名、热度、收藏、日期、名称里的第三页
        compose.onNodeWithTag("SearchSort-COLLECTS").assertIsOn()
        compose.onNodeWithTag("SearchGrid_COLLECTS").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(SearchSort.DATE, actions.form.applied.sort)
            assertEquals(1, actions.calls.count { it == "onSortChange" })
        }
        compose.onNodeWithTag("SearchSort-DATE").assertIsOn()
        compose.onNodeWithTag("SearchGrid_DATE").assertIsDisplayed()
    }

    @Test
    fun 有关键词时只有一页_左滑不换排序() {
        val actions = ReducingSearchActions(SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME, query = "eva")))
        setContent(actions)
        compose.onNodeWithTag("SearchGrid_NONE").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertEquals(SearchSort.COLLECTS, actions.form.applied.sort)
            assertTrue(actions.calls.none { it == "onSortChange" })
        }
    }

    @Test
    fun 输入只改草稿_最多两个按钮_IME提交_清输入单独回调() {
        val actions = ReducingSearchActions()
        setContent(actions)

        // 无输入时只有筛选按钮，无清空无搜索按钮（最多两个）
        compose.onNodeWithTag("SearchOpenFilters").assertIsDisplayed()
        compose.onNodeWithTag("SearchClearQuery").assertDoesNotExist()
        compose.onNodeWithTag("SearchSubmit").assertDoesNotExist()

        compose.onNodeWithTag("SearchQuery").performTextReplacement("eva")
        compose.runOnIdle {
            assertEquals("eva", actions.form.draftQuery)
            assertEquals("", actions.form.applied.query)
        }

        // 有输入时右侧为 [清空, 筛选]，共两个按钮；无 SearchSubmit 按钮
        compose.onNodeWithTag("SearchClearQuery").assertIsDisplayed()
        compose.onNodeWithTag("SearchOpenFilters").assertIsDisplayed()
        compose.onNodeWithTag("SearchSubmit").assertDoesNotExist()

        // 仅靠 IME Action 提交
        compose.onNodeWithTag("SearchQuery").performSemanticsAction(SemanticsActions.OnImeAction) { it() }
        compose.runOnIdle {
            assertEquals(1, actions.calls.count { it == "onSearch" })
            assertEquals("eva", actions.form.applied.query)
        }

        compose.onNodeWithTag("SearchClearQuery").performClick()
        compose.runOnIdle {
            assertEquals(1, actions.calls.count { it == "onQueryClear" })
        }
    }

    @Test
    fun 面板开关只来自表单草稿_界面重建后仍打开且不产生事件() {
        val actions = ReducingSearchActions()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ChogumiTheme { PageSheetTestHost { SearchContent(SearchUiState(form = actions.form), actions, {}, {}, currentYear = FIXTURE_TODAY.year) } }
        }
        openPanel()
        val callsBefore = actions.calls.toList()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("SearchFilterPanel").assertIsDisplayed()
        assertEquals(listOf("onFiltersOpen"), callsBefore)
        assertEquals(callsBefore, actions.calls)
    }

    @Test
    fun 类型FAB_展开列出全部类型_选三次元后收起并切换_FAB描述随之更新() {
        val actions = ReducingSearchActions()
        setContent(actions)
        compose.onNodeWithTag("SelectionFab").performClick()
        for (tag in listOf("ALL", "ANIME", "BOOK", "GAME", "MUSIC", "REAL")) {
            compose.onNodeWithTag("ExploreSearchType-$tag").assertIsDisplayed()
        }
        compose.onNodeWithTag("ExploreSearchType-REAL").performClick()
        compose.runOnIdle {
            assertEquals(SubjectType.REAL, actions.form.applied.subjectType)
            assertEquals(1, actions.calls.count { it == "onFilterTypeChange" })
        }
        compose.onNodeWithTag("ExploreSearchType-REAL").assertIsNotDisplayed()
        compose.onNodeWithTag("SelectionFab").assertContentDescriptionEquals(typeFabDescription(R.string.real))
    }

    @Test
    fun 全部类型下面板提示先选类型_仍可选年份() {
        val actions = ReducingSearchActions()
        setContent(actions)
        openPanel()

        compose.onNodeWithTag("SearchDimensionHint").assertIsDisplayed()
        compose.onNodeWithTag("SearchFilterPanel")
            .performScrollToNode(hasTestTag("SearchTimeSlider"))
        compose.onNodeWithTag("SearchTimeSlider").assertIsDisplayed()
    }
}
