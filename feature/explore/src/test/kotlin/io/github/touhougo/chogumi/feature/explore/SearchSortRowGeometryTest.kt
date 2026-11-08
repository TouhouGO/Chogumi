package io.github.touhougo.chogumi.feature.explore

import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SearchSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 搜索页吸顶的两行：搜索栏在上、排序行在下，不重叠；排序行是分开的按钮，相邻两格间距就是连通按钮组的格间距。
 * 必须画整页 [SearchContent]：两行一起放进吸顶条，才看得出它们是竖排还是叠在同一处。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh-w380dp-h700dp")
class SearchSortRowGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setPage() {
        compose.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    SearchContent(
                        currentYear = 2026,
                        state = SearchUiState(form = SearchForm(applied = SearchCriteria(subjectType = SubjectType.ANIME))),
                        actions = noActions,
                        onSubjectClick = {},
                        onBack = {},
                    )
                }
            }
        }
    }

    @Test
    fun 排序行在搜索栏下面_不压在搜索栏上() {
        setPage()
        val search = compose.onNodeWithTag("SearchQuery").getUnclippedBoundsInRoot()
        val sort = compose.onNodeWithTag("SearchSortGroup").getUnclippedBoundsInRoot()
        assertTrue("排序行顶 ${sort.top} 在搜索栏底 ${search.bottom} 之上", sort.top >= search.bottom)
    }

    @Test
    fun 相邻排序按钮之间是连通按钮组的格间距() {
        setPage()
        val buttons = SearchSort.entries.map { compose.onNodeWithTag("SearchSort-${it.name}").getUnclippedBoundsInRoot() }
        buttons.zipWithNext().forEach { (left, right) ->
            assertEquals(ButtonGroupDefaults.ConnectedSpaceBetween.value, (right.left - left.right).value, 0.5f)
        }
    }

    /** 这里只看画出来的样子，不点任何东西：事件都返回 Unit，`equals` / `hashCode` / `toString` 按对象本身答（重组比较参数要用）。 */
    private val noActions: SearchActions =
        Proxy.newProxyInstance(SearchActions::class.java.classLoader, arrayOf(SearchActions::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SearchActions(no-op)"
                else -> Unit
            }
        } as SearchActions
}
