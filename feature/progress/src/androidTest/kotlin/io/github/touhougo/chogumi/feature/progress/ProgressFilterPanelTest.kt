package io.github.touhougo.chogumi.feature.progress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.ProgressCategory
import io.github.touhougo.chogumi.core.model.ProgressCategoryGroup
import io.github.touhougo.chogumi.core.model.ProgressFilterOption
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 筛选面板内容（不含面板外壳与动画）的版式、回调与网格几何。
 *
 * 面板放进一个最高 [tallHeight] / [shortHeight] 的列里，模拟页面面板「按内容长高、到顶后里面滚」：
 * 矮的那档故意让内容超出，断言标题行与底部按钮不随内容滚动。
 */
class ProgressFilterPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val tallHeight = 2400
    private val shortHeight = 360

    /** 380dp 手机：去掉两侧 16dp 后排两列（GridCell.FilterTile 最小 160）。 */
    private val panelWidth = 380

    private fun option(key: String, count: Int = 1) = ProgressFilterOption(key, count, emptyList())

    private fun category(dimension: SearchDimension, label: String, count: Int = 1) =
        ProgressFilterOption(ProgressCategory(dimension, SearchDimensionOption(label, label)), count, emptyList())

    /** 动画的「形式」（TV、剧场版）与「地区」（日本）。 */
    private val categoryGroups = listOf(
        ProgressCategoryGroup(SearchDimension.CATEGORY, listOf(category(SearchDimension.CATEGORY, "TV", 3), category(SearchDimension.CATEGORY, "剧场版"))),
        ProgressCategoryGroup(SearchDimension.REGION, listOf(category(SearchDimension.REGION, "日本", 4))),
    )

    private val manyTags = (1..18).map { option("标签$it") }

    private fun setPanel(
        subjectType: SubjectType? = SubjectType.ANIME,
        groups: List<ProgressCategoryGroup> = categoryGroups,
        selectedCategories: Set<ProgressCategory> = emptySet(),
        tags: List<ProgressFilterOption<String>> = emptyList(),
        selectedTags: Set<String> = emptySet(),
        resultCount: Int = 4,
        onToggleCategory: (ProgressCategory) -> Unit = {},
        onToggleTag: (String) -> Unit = {},
        onClear: () -> Unit = {},
        onDone: () -> Unit = {},
        height: Int = tallHeight,
    ) {
        compose.setContent {
            ChogumiTheme {
                Column(Modifier.width(panelWidth.dp).heightIn(max = height.dp).testTag(HOST)) {
                    ProgressFilterPanel(
                        subjectType = subjectType,
                        categoryGroups = groups,
                        selectedCategories = selectedCategories,
                        tags = tags,
                        selectedTags = selectedTags,
                        resultCount = resultCount,
                        onToggleCategory = onToggleCategory,
                        onToggleTag = onToggleTag,
                        onClear = onClear,
                        onDone = onDone,
                    )
                }
            }
        }
    }

    /** 动画的「题材」：比「形式」的托盘高得多。 */
    private val genreGroup = ProgressCategoryGroup(
        SearchDimension.GENRE,
        listOf("科幻", "喜剧", "百合", "校园", "恋爱", "奇幻", "悬疑", "运动", "音乐", "战斗").map { category(SearchDimension.GENRE, it) },
    )

    private fun expandTags() {
        compose.onNodeWithTag("ProgressFilterTagsHeader").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun boundsOf(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun topOfTag(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun 标签很多时标题行与按钮不随内容滚动() {
        var cleared = false
        var done = false
        setPanel(
            tags = manyTags,
            selectedTags = setOf("标签1"),
            onClear = { cleared = true },
            onDone = { done = true },
            height = shortHeight,
        )
        val clearTop = topOfTag("ProgressPanelClear")
        val doneTop = topOfTag("ProgressFilterDone")
        expandTags()
        compose.onNodeWithText("标签18").performScrollTo()
        assertEquals("清除不得随内容滚动", clearTop, topOfTag("ProgressPanelClear"), 0.5f)
        assertEquals("「显示 N 部」不得随内容滚动", doneTop, topOfTag("ProgressFilterDone"), 0.5f)
        compose.onNodeWithTag("ProgressPanelClear").assertIsDisplayed().performClick()
        compose.onNodeWithTag("ProgressFilterDone").assertIsDisplayed().performClick()
        assertTrue(cleared)
        assertTrue(done)
    }

    @Test
    fun 内容少时面板按内容高() {
        setPanel(groups = emptyList(), tags = listOf(option("补番")))
        val height = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot.height
        assertTrue("只有一个标签时面板不该撑到上限（$height px）", height < tallHeight / 4f * compose.density.density)
    }

    @Test
    fun 分类在标签之前() {
        setPanel(tags = manyTags)
        assertTrue(topOfTag("ProgressFilterCategories") < topOfTag("ProgressFilterTags"))
    }

    @Test
    fun 没有分类时标签直接摊开_不可收起() {
        // 「全部」页签没有分类：标签收着的话面板里只剩一个标题行
        setPanel(subjectType = null, groups = emptyList(), tags = manyTags, selectedTags = setOf("标签2"))
        compose.onNodeWithTag("ProgressFilterCategories").assertDoesNotExist()
        compose.onNodeWithText("标签1").assertIsDisplayed()
        compose.onNodeWithTag("ProgressFilterTagsHeader").assertDoesNotExist()
    }

    @Test
    fun 确认按钮在标题行右端_下面整块是滚动区_一直到面板底边() {
        setPanel(tags = manyTags, height = shortHeight)
        val title = boundsOf("筛选")
        val done = compose.onNodeWithTag("ProgressFilterDone").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithTag(HOST).fetchSemanticsNode().boundsInRoot
        assertTrue("「显示 N 部」与「筛选」在同一行（$done / $title）", done.center.y in title.top..title.bottom)
        assertEquals("「显示 N 部」靠右边 16dp", host.right - 16 * compose.density.density, done.right, 1f)
        val content = compose.onNodeWithTag("ProgressFilterPanel").fetchSemanticsNode().boundsInRoot
        assertEquals("滚动区下面没有任何东西：内容滚到底不会被盖住", host.bottom, content.bottom, 1f)
    }

    @Test
    fun 没有分类也没有标签时说明为空() {
        setPanel(subjectType = null, groups = emptyList(), tags = emptyList())
        compose.onNodeWithText("这里的作品还没有标签").assertIsDisplayed()
    }

    @Test
    fun 清除只在有已选时出现() {
        setPanel(tags = manyTags)
        compose.onNodeWithTag("ProgressPanelClear").assertDoesNotExist()
    }

    @Test
    fun 底部按钮写着筛完剩几部() {
        setPanel(resultCount = 7)
        compose.onNodeWithText("显示 7 部").assertIsDisplayed()
    }

    @Test
    fun 打开时先摊开题材维度() {
        setPanel(groups = categoryGroups + genreGroup)
        compose.onNodeWithText("科幻").assertIsDisplayed()
        compose.onNodeWithText("TV").assertDoesNotExist()
    }

    @Test
    fun 没有题材时先摊开第一个维度_点另一个换托盘_再点收起() {
        setPanel()
        compose.onNodeWithText("TV").assertIsDisplayed()
        compose.onNodeWithText("日本").assertDoesNotExist()

        compose.onNodeWithText("地区").performClick()
        compose.onNodeWithText("日本").assertIsDisplayed()
        compose.onNodeWithText("TV").assertDoesNotExist()

        compose.onNodeWithText("地区").performClick()
        compose.onNodeWithTag("ProgressFilterTray").assertDoesNotExist()
    }

    @Test
    fun 有已选取值的维度带数字_读屏念已选几项() {
        setPanel(selectedCategories = setOf(categoryGroups.first().options.first().key))
        compose.onNodeWithText("形式").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选 1 项"))
        compose.onNodeWithText("1", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun 维度叫法跟着条目类型() {
        // GENRE 在动画叫「题材」、游戏叫「玩法」（唯一映射 searchDimensionTitleRes）
        setPanel(
            subjectType = SubjectType.GAME,
            groups = listOf(ProgressCategoryGroup(SearchDimension.GENRE, listOf(category(SearchDimension.GENRE, "ACT")))),
        )
        compose.onNodeWithText("玩法").assertIsDisplayed()
        compose.onNodeWithText("题材").assertDoesNotExist()
    }

    @Test
    fun 我的标签默认收着_标题上是已选几个_点开才有格子() {
        setPanel(tags = manyTags, selectedTags = setOf("标签1", "标签2"))
        compose.onNodeWithText("标签1").assertDoesNotExist()
        compose.onNodeWithTag("ProgressFilterTagsHeader")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已选 2 项"))
        compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()

        expandTags()
        compose.onNodeWithText("标签1").assertIsDisplayed()
        compose.onNodeWithText("2", useUnmergedTree = true).assertDoesNotExist()
    }

    /** 点「显示 N 部」后面板还在滑下去时，又点了筛选入口（会话加一）：滑完之后面板是开着的，这一下没有落空。 */
    @Test
    fun 面板收起途中再打开_新面板照样打开() {
        var session by mutableIntStateOf(1)
        var openSession by mutableStateOf<Int?>(1)
        compose.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    if (openSession != null) {
                        ProgressFilterSheet(
                            session = session,
                            subjectType = null,
                            categoryGroups = emptyList(),
                            selectedCategories = emptySet(),
                            tags = manyTags,
                            selectedTags = emptySet(),
                            resultCount = 4,
                            onToggleCategory = {},
                            onToggleTag = {},
                            onClear = {},
                            // 与 ProgressViewModel.closeFilters 同一个规则：只关自己那一次
                            onDismiss = { closed -> if (closed == session) openSession = null },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("ProgressFilterDone").performClick()
        compose.mainClock.advanceTimeBy(80) // 正在滑下去
        session = 2
        openSession = 2
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("筛选").assertIsDisplayed()
        compose.onNodeWithText("标签1").assertIsDisplayed()
    }

    /** 在页面面板里：切到矮得多的托盘、展开我的标签，面板顶边都不动（内容往下长、在面板里滚）。 */
    @Test
    fun 切换托盘与展开标签时面板顶边不动() {
        compose.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    ProgressFilterSheet(
                        session = 1,
                        subjectType = SubjectType.ANIME,
                        categoryGroups = categoryGroups + genreGroup,
                        selectedCategories = emptySet(),
                        tags = manyTags,
                        selectedTags = emptySet(),
                        resultCount = 4,
                        onToggleCategory = {},
                        onToggleTag = {},
                        onClear = {},
                        onDismiss = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        val top = boundsOf("筛选").top
        compose.onNodeWithText("地区").performClick()
        compose.waitForIdle()
        assertEquals("换到矮的托盘，顶边不动", top, boundsOf("筛选").top, 1f)
        expandTags()
        assertEquals("展开我的标签，顶边不动", top, boundsOf("筛选").top, 1f)
    }

    @Test
    fun 点托盘里的取值回调带维度() {
        val calls = mutableListOf<ProgressCategory>()
        setPanel(onToggleCategory = { calls += it })
        compose.onNodeWithText("TV").performClick()
        assertEquals(listOf(categoryGroups.first().options.first().key), calls)
    }

    @Test
    fun 点标签格回调该标签() {
        val calls = mutableListOf<String>()
        setPanel(tags = manyTags, onToggleTag = { calls += it })
        expandTags()
        compose.onNodeWithText("标签3").performScrollTo().performClick()
        assertEquals(listOf("标签3"), calls)
    }

    @Test
    fun 标签格每行铺满_末行落单的格子占满一行() {
        setPanel(groups = emptyList(), tags = listOf(option("甲"), option("乙"), option("丙")))
        val first = boundsOf("甲")
        val second = boundsOf("乙")
        val last = boundsOf("丙")
        assertEquals("两列等宽", first.width, second.width, 1f)
        assertEquals("末行落单的格子与第一行左右对齐", first.left, last.left, 1f)
        assertEquals("末行落单的格子占满一行", second.right, last.right, 1f)
    }

    @Test
    fun 托盘里的格子与下面的标签格对齐() {
        setPanel(tags = listOf(option("甲"), option("乙")))
        expandTags()
        val tray = compose.onNodeWithTag("ProgressFilterTray").fetchSemanticsNode().boundsInRoot
        val tv = boundsOf("TV")
        val tag = boundsOf("甲")
        assertEquals("托盘里的格子与标签格同一条左边线", tag.left, tv.left, 1f)
        assertEquals("托盘里的格子与标签格同宽", tag.width, tv.width, 1f)
        val eight = 8 * compose.density.density
        assertEquals("托盘比网格左边宽 8dp", tag.left - eight, tray.left, 1f)
    }

    private companion object {
        const val HOST = "PanelHost"
    }
}
