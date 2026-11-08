package io.github.touhougo.chogumi.feature.explore

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.model.SearchCriteria
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import java.lang.reflect.Proxy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 整页里的排序组能按标签找到：排序组把调用方的 modifier 与自己的标签挂在同一个节点上，调用方再挂标签会盖掉它。
 * 必须画整页 [SearchContent]：只画排序条时没有调用方的 modifier。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class SearchSortGroupTagTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 整页里的排序组带着自己的标签() {
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
        compose.onNodeWithTag("SearchSortGroup").assertIsDisplayed()
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
