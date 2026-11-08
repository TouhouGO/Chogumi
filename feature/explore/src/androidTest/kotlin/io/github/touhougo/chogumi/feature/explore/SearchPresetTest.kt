package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchDimension
import io.github.touhougo.chogumi.core.model.SearchDimensionOption
import io.github.touhougo.chogumi.core.model.SearchFilters
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * 搜索页顶部的名字行：图钉的可用性与选中态、名字随风、就地改名。
 *
 * 走真实组件 + 真实 reduce（[ReducingSearchActions]），所以「条件一动名字就没了」这条
 * 是整条链验过的，不是只断言回调参数。
 */
class SearchPresetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val tv = SearchDimensionOption("TV", "TV")

    private fun setContent(actions: ReducingSearchActions) {
        compose.setContent {
            ChogumiTheme {
                SearchContent(
                    currentYear = FIXTURE_TODAY.year,
                    state = SearchUiState(
                        form = actions.form,
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

    /** 动画 + 一个分类条件「TV」的搜索页。 */
    private fun withTv(title: String? = null) = ReducingSearchActions(
        SearchForm(
            applied = SearchCriteria(
                subjectType = SubjectType.ANIME,
                filters = SearchFilters(dimensions = mapOf(SearchDimension.CATEGORY to listOf(tv))),
            ),
            title = title,
        ),
    )

    @Test
    fun 页面上没有任何名字行_只有搜索栏里的图钉() {
        val actions = withTv(title = "排行榜")
        setContent(actions)

        // 名字不在页面上单开一行占竖向空间。
        compose.onNodeWithTag("SearchPresetTitle").assertDoesNotExist()
        compose.onNodeWithTag("SearchPresetPin").assertIsDisplayed()
            .assertContentDescriptionEquals(context.getString(R.string.search_pin))
    }

    @Test
    fun 图钉_关键词非空时不存在() {
        val actions = withTv().let { e ->
            // 输个关键词（走 reduce，与真实操作一致）
            e.onQueryChange("eva"); e.onSearch(); e
        }
        setContent(actions)
        // 有关键词就不是一组可固定的条件（isPinnable 要求 query 为空），图钉整个不渲染，
        // 搜索栏右侧因此也不会出现第三个图标（清除 + 筛选已占两个）。
        compose.onNodeWithTag("SearchPresetPin").assertDoesNotExist()
        compose.onNodeWithTag("SearchClearQuery").assertIsDisplayed()
        compose.onNodeWithTag("SearchOpenFilters").assertIsDisplayed()
    }

    @Test
    fun 图钉_无条件时不存在() {
        val actions = ReducingSearchActions(SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME)))
        setContent(actions)
        compose.onNodeWithTag("SearchPresetPin").assertDoesNotExist()
    }

    @Test
    fun 固定_图钉转选中态_默认名用路由带来的名字() {
        val actions = withTv(title = "排行榜")
        setContent(actions)

        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.runOnIdle {
            assertEquals("排行榜", actions.lastPinTitle)
        }
        compose.onNodeWithTag("SearchPresetPin")
            .assertContentDescriptionEquals(context.getString(R.string.search_unpin))
    }

    @Test
    fun 固定_没有名字时默认名由条件文字连成() {
        val actions = withTv(title = null)
        setContent(actions)
        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.runOnIdle {
            assertEquals("形式：TV", actions.lastPinTitle)
        }
    }

    @Test
    fun 已固定时点图钉开菜单_里面有重命名与取消固定() {
        val actions = withTv(title = "排行榜")
        setContent(actions)
        compose.onNodeWithTag("SearchPresetPin").performClick()

        // 未固定时点一下是直接固定；已固定后点一下开菜单，不会误把它取消掉。
        compose.onNodeWithTag("SearchPresetRename").assertDoesNotExist()
        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.onNodeWithTag("SearchPresetRename").assertIsDisplayed()
        compose.onNodeWithTag("SearchPresetUnpin").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("排行榜", actions.pinned?.name)
        }
    }

    @Test
    fun 取消固定_走菜单里的取消固定() {
        val actions = withTv(title = "排行榜")
        setContent(actions)
        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.onNodeWithTag("SearchPresetUnpin").performClick()
        compose.runOnIdle {
            assertNull(actions.pinned)
        }
        compose.onNodeWithTag("SearchPresetPin")
            .assertContentDescriptionEquals(context.getString(R.string.search_pin))
    }

    @Test
    fun 改名_走菜单里的重命名_对话框里写完保存() {
        val actions = withTv(title = "排行榜")
        setContent(actions)
        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.onNodeWithTag("SearchPresetRename").performClick()
        compose.onNodeWithTag("SearchPresetTitleEditor").assertIsDisplayed()
            .performTextReplacement("女孩子组乐队")
        compose.onNodeWithTag("SearchPresetRenameConfirm").performClick()
        compose.runOnIdle {
            assertEquals("女孩子组乐队", actions.lastRenameTitle)
            assertEquals("女孩子组乐队", actions.pinned?.name)
        }
        compose.onNodeWithTag("SearchPresetTitleEditor").assertDoesNotExist()
    }

    @Test
    fun 改名_空字符串不保存() {
        val actions = withTv(title = "排行榜")
        setContent(actions)
        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.onNodeWithTag("SearchPresetRename").performClick()
        compose.onNodeWithTag("SearchPresetTitleEditor").performTextReplacement("   ")
        compose.onNodeWithTag("SearchPresetRenameConfirm").performClick()
        compose.runOnIdle {
            // 名字没被清空，仍然是原来的
            assertEquals("排行榜", actions.pinned?.name)
        }
    }

    @Test
    fun 默认名_改排序后路由名字失效_退回条件连成的名字() {
        val actions = withTv(title = "排行榜")
        setContent(actions)
        actions.onSortChange(SearchSort.TRENDS)
        // 路由带来的「排行榜」在条件动过之后失效（判定在 SearchForm.reduce），
        // 页面上没有名字可看，所以直接验图钉存下去的默认名。
        compose.onNodeWithTag("SearchPresetPin").performClick()
        compose.runOnIdle {
            assertEquals("形式：TV", actions.lastPinTitle)
        }
    }
}
