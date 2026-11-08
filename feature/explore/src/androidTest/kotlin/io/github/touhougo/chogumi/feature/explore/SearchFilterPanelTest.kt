package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.touhougo.chogumi.core.model.SearchAirtime
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.TagCatalogEntry
import io.github.touhougo.chogumi.core.model.WordPick
import io.github.touhougo.chogumi.core.model.searchDimensionsFor
import io.github.touhougo.chogumi.core.model.themeWordsFor
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 筛选面板内容（不含 sheet 外壳）：题材区、维度分组、单选替换/多选叠加、折叠展开、
 * 搜索建议与「其他已选」、时间滑块、清空与保存。面板只改草稿；已提交条件只在点「保存」后变化。
 */
class SearchFilterPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val year = 2026
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 与 SearchContent 宿主同规则：草稿非 null 才渲染面板。 */
    private fun setPanel(actions: ReducingSearchActions) {
        compose.setContent {
            ChogumiTheme {
                actions.form.filterDraft?.let { draft ->
                    SearchFilterPanel(
                        subjectType = actions.form.applied.subjectType,
                        draft = draft,
                        actions = actions,
                        panelQuery = actions.panelQuery,
                        catalogSuggestions = actions.catalogSuggestions,
                        currentYear = year,
                        onSave = actions::onFiltersSave,
                    )
                }
            }
        }
    }

    /** 已打开面板（草稿 = 已提交条件的副本）的指定类型表单。 */
    private fun typed(type: SubjectType, catalog: List<TagCatalogEntry> = emptyList()) =
        ReducingSearchActions(
            SearchForm(applied = SearchCriteria(subjectType = type)),
            catalogSuggestions = catalog,
        ).also { it.onFiltersOpen() }

    private fun option(type: SubjectType, dimension: SearchDimension, label: String) =
        searchDimensionsFor(type).single { it.dimension == dimension }.options.single { it.label == label }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("SearchFilterPanel").performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun 题材区_动画题材维度取值与人工词合成一组_点选可多选() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        // 动画题材维度是 GENRE，由题材区承担：没有单独的 GENRE 分区标题
        val themeWords = themeWordsFor(SubjectType.ANIME)
        assertTrue("动画题材区必须含题材维度取值", "奇幻" in themeWords)
        assertTrue("动画题材区必须含人工挑的题材词", "异世界" in themeWords)

        scrollTo("SearchTheme-奇幻")
        compose.onNodeWithTag("SearchTheme-奇幻").performClick()
        compose.onNodeWithTag("SearchTheme-异世界").performClick()
        compose.runOnIdle {
            val dims = actions.form.filterDraft?.dimensions.orEmpty()
            assertEquals(
                listOf(option(SubjectType.ANIME, SearchDimension.GENRE, "奇幻")),
                dims[SearchDimension.GENRE],
            )
            // 人工词目录外，落成用户标签
            assertEquals(listOf("异世界"), actions.form.filterDraft?.tags?.map { it.value })
        }
    }

    @Test
    fun 题材区_自由词点两次_chip未选中_草稿里没有这个标签() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        scrollTo("SearchTheme-异世界")
        compose.onNodeWithTag("SearchTheme-异世界").performClick()
        compose.onNodeWithTag("SearchTheme-异世界").assertIsSelected()
        compose.onNodeWithTag("SearchTheme-异世界").performClick()
        compose.onNodeWithTag("SearchTheme-异世界").assertIsNotSelected()
        compose.runOnIdle {
            assertTrue(actions.form.filterDraft?.tags.orEmpty().none { it.value == "异世界" })
        }
    }

    @Test
    fun 游戏面板_玩法叫法_题材区只有人工词() {
        val actions = typed(SubjectType.GAME)
        setPanel(actions)
        // GENRE 在游戏叫「玩法」，题材区的人工词单独成区
        compose.onNodeWithTag("SearchFilterPanel").assertIsDisplayed()
        scrollTo("SearchTheme-魂系")
        compose.onNodeWithTag("SearchTheme-魂系").assertIsDisplayed()
        // GENRE 的取值（如 ACT）不在题材区，而在「玩法」分区里
        scrollTo("SearchDim-GENRE-ACT")
        compose.onNodeWithTag("SearchDim-GENRE-ACT").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.search_dim_genre_game)).assertIsDisplayed()
    }

    @Test
    fun 单选维度_选另一值替换_再点已选值取消() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        val ova = option(SubjectType.ANIME, SearchDimension.CATEGORY, "OVA")

        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.CATEGORY, tv)).performClick()
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.CATEGORY, ova)).performClick()
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.CATEGORY, ova)).assertIsSelected()
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.CATEGORY, tv)).assertIsNotSelected()
        compose.runOnIdle {
            assertEquals(listOf(ova), actions.form.filterDraft?.dimensions?.get(SearchDimension.CATEGORY))
            assertTrue("未保存前已提交条件不变", actions.form.applied.filters.dimensions.isEmpty())
        }

        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.CATEGORY, ova)).performClick()
        compose.runOnIdle { assertTrue(actions.form.filterDraft?.dimensions.orEmpty().isEmpty()) }
    }

    @Test
    fun 多选维度_点一下加_再点去掉() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        val sciFi = option(SubjectType.ANIME, SearchDimension.GENRE, "科幻")
        val comedy = option(SubjectType.ANIME, SearchDimension.GENRE, "喜剧")
        // 题材区里的两个 GENRE 取值：都点亮后同属 GENRE 维度的多选列表
        compose.onNodeWithTag("SearchTheme-${sciFi.label}").performClick()
        compose.onNodeWithTag("SearchTheme-${comedy.label}").performClick()
        compose.runOnIdle {
            assertEquals(listOf(sciFi, comedy), actions.form.filterDraft?.dimensions?.get(SearchDimension.GENRE))
        }
        compose.onNodeWithTag("SearchTheme-${comedy.label}").performClick()
        compose.runOnIdle {
            assertEquals(listOf(sciFi), actions.form.filterDraft?.dimensions?.get(SearchDimension.GENRE))
        }
    }

    @Test
    fun 游戏平台_网页文字显示_选中后存的是实测别名标签() {
        val actions = typed(SubjectType.GAME)
        setPanel(actions)
        val switch = option(SubjectType.GAME, SearchDimension.PLATFORM, "Nintendo Switch")
        val tag = dimensionOptionTag(SearchDimension.PLATFORM, switch)

        scrollTo(tag)
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("NS", actions.form.filterDraft?.dimensions?.get(SearchDimension.PLATFORM)?.single()?.metaTag)
        }
    }

    @Test
    fun 三次元只有实测命中的分类_无日剧() {
        val actions = typed(SubjectType.REAL)
        setPanel(actions)
        val labels = searchDimensionsFor(SubjectType.REAL)
            .single { it.dimension == SearchDimension.CATEGORY }.options.map { it.label }
        assertEquals(listOf("电视剧", "电影", "演出", "综艺"), labels)
        compose.onNodeWithTag("SearchDim-CATEGORY-日剧").assertDoesNotExist()
        compose.onNodeWithTag("SearchDim-CATEGORY-电视剧").assertIsDisplayed()
    }

    @Test
    fun 折叠_超过12个取值的分区先只显示前12个_展开后全部出现() {
        val actions = typed(SubjectType.GAME)
        setPanel(actions)
        // 平台分区取值远超 12 个：折叠时第 13 个之后的不在树上，展开后出现
        val platform = searchDimensionsFor(SubjectType.GAME).single { it.dimension == SearchDimension.PLATFORM }
        assertTrue("平台取值必须超过 12 个才有折叠", platform.options.size > 12)
        val hidden = platform.options[12]
        val expandTag = "SearchDim-PLATFORM-EXPAND"

        scrollTo(expandTag)
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.PLATFORM, hidden)).assertDoesNotExist()
        compose.onNodeWithTag(expandTag).performClick()
        scrollTo(dimensionOptionTag(SearchDimension.PLATFORM, hidden))
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.PLATFORM, hidden)).assertIsDisplayed()
    }

    @Test
    fun 折叠_12个以外已选的值在折叠时也显示() {
        val actions = typed(SubjectType.GAME)
        val platform = searchDimensionsFor(SubjectType.GAME).single { it.dimension == SearchDimension.PLATFORM }
        val hidden = platform.options[12]
        // 预置一个落在折叠区的已选取值
        actions.onDraftDimensionToggle(SearchDimension.PLATFORM, hidden)
        setPanel(actions)
        scrollTo(dimensionOptionTag(SearchDimension.PLATFORM, hidden))
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.PLATFORM, hidden)).assertIsDisplayed().assertIsSelected()
    }

    @Test
    fun 搜索框_输入列出建议_点选后落成维度或标签() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        compose.onNodeWithTag("SearchFilterField").performTextReplacement("TV")
        // 建议里出现 CATEGORY 的取值 TV（备注写它属于哪个分区）
        compose.onNodeWithTag("SearchSuggestion-${tv.label}").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf(tv), actions.form.filterDraft?.dimensions?.get(SearchDimension.CATEGORY))
        }
    }

    /**
     * 目录建议：走假仓库给的目录结果，不出网。辅助文本显示使用次数（缩写规则见 formatAbbreviatedCount）。
     * 输入用「京」而不是维度取值，专挑一条只可能来自目录的建议。
     */
    @Test
    fun 搜索框_目录结果进建议_辅助文本写使用次数() {
        val actions = typed(SubjectType.ANIME, listOf(TagCatalogEntry("京阿尼", 215952, 1)))
        setPanel(actions)
        compose.onNodeWithTag("SearchFilterField").performTextReplacement("京")
        compose.onNodeWithTag("SearchSuggestion-京阿尼").assertIsDisplayed()
        // 辅助文本是「21.5W 次标记」（215952 截断到一位小数）
        compose.onNodeWithText(context.getString(R.string.search_suggestion_catalog_count, "21.5W"))
            .assertIsDisplayed()
        compose.onNodeWithTag("SearchSuggestion-京阿尼").performClick()
        compose.runOnIdle {
            assertEquals(listOf("京阿尼"), actions.form.filterDraft?.tags?.map { it.value })
        }
    }

    /** 目录还没拉下来（默认空）时只显示本地建议，不显示加载态。 */
    @Test
    fun 搜索框_目录为空时只给本地建议_不显示加载态() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        compose.onNodeWithTag("SearchFilterField").performTextReplacement("TV")
        compose.onNodeWithTag("SearchSuggestion-TV").assertIsDisplayed()
        compose.onNodeWithTag("SearchFilterSuggestions").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.loading)).assertDoesNotExist()
    }

    @Test
    fun 搜索框_输入法搜索动作等于选末行_目录外的词按标签收_已选的再回车不变() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        compose.onNodeWithTag("SearchFilterField").performTextInput("不存在的词")
        compose.onNodeWithTag("SearchFilterField").performImeAction()
        compose.runOnIdle {
            assertEquals(
                listOf("不存在的词"),
                actions.form.filterDraft?.tags?.map { it.value },
            )
        }
        // 只加不减：同一个词再回车一次，草稿保持不变（不会被取消）
        compose.onNodeWithTag("SearchFilterField").performTextInput("不存在的词")
        compose.onNodeWithTag("SearchFilterField").performImeAction()
        compose.runOnIdle {
            assertEquals(
                listOf("不存在的词"),
                actions.form.filterDraft?.tags?.map { it.value },
            )
        }
    }

    @Test
    fun 其他已选_题材区以外的词与落不进维度的分类标签在此行可删() {
        val actions = typed(SubjectType.ANIME)
        actions.onDraftWordPick("菅野洋子", WordPick.ADD)
        setPanel(actions)
        compose.onNodeWithTag("SearchDraftTag-USER:菅野洋子").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(actions.form.filterDraft?.tags.orEmpty().isEmpty()) }
    }

    @Test
    fun 时间滑块_同一年时月份可点_范围时月份不可点() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        scrollTo("SearchTimeSlider")
        compose.onNodeWithTag("SearchTimeSlider").assertIsDisplayed()
        // 不限：月份整排置灰不可点
        compose.onNodeWithTag("SearchMonth-5").assertIsNotEnabled()
        // 设成单独一年（2019–2019）：月份可点
        actions.onDraftAirtimeChange(SearchAirtime(2019, 2019))
        scrollTo("SearchMonth-5")
        compose.onNodeWithTag("SearchMonth-5").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(SearchAirtime(2019, 2019, 5), actions.form.filterDraft?.airtime)
        }
        // 设成范围：月份又不可点
        actions.onDraftAirtimeChange(SearchAirtime(2015, 2019))
        scrollTo("SearchMonth-5")
        compose.onNodeWithTag("SearchMonth-5").assertIsNotEnabled()
    }

    @Test
    fun 保存_草稿写入已提交条件并关闭面板() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.CATEGORY, tv)).performClick()
        compose.onNodeWithTag("SearchPanelSave").performClick()
        compose.runOnIdle {
            assertEquals(mapOf(SearchDimension.CATEGORY to listOf(tv)), actions.form.applied.filters.dimensions)
            assertNull(actions.form.filterDraft)
        }
        compose.onNodeWithTag("SearchFilterPanel").assertDoesNotExist()
    }

    @Test
    fun 清空_只清草稿_无内容时禁用() {
        val actions = typed(SubjectType.ANIME)
        setPanel(actions)
        compose.onNodeWithTag("SearchPanelClear").assertIsNotEnabled()
        val tv = option(SubjectType.ANIME, SearchDimension.CATEGORY, "TV")
        compose.onNodeWithTag(dimensionOptionTag(SearchDimension.CATEGORY, tv)).performClick()
        compose.onNodeWithTag("SearchPanelClear").assertIsEnabled().performClick()
        compose.onNodeWithTag("SearchPanelClear").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(actions.form.filterDraft?.dimensions.orEmpty().isEmpty())
            assertEquals(listOf("onFiltersOpen", "onDraftDimensionToggle", "onDraftClear"), actions.calls)
        }
    }
}
