package io.github.touhougo.chogumi.feature.monodetails

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.model.MonoType
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.navigation.PageSheetRegistry
import io.github.touhougo.chogumi.core.ui.common.navigation.Route
import io.github.touhougo.chogumi.core.ui.common.navigation.rememberPageSheetNavEntryDecorator
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonoIndexesAndroidTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 空首屏total31保留入口并自动续取到第二页() {
        var loads = 0
        var state by mutableStateOf(PagedList<IndexBo>(emptyList(), total = 31, nextOffset = 30, isLoading = false))
        compose.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    MonoIndexesSheet(
                        state = state,
                        onDismiss = {},
                        onRetry = {},
                        onLoadMore = {
                            loads++
                            state = PagedList(listOf(index(31)), total = 31, nextOffset = null, isLoading = false)
                        },
                        onIndexClick = {},
                    )
                }
            }
        }

        compose.waitUntil { loads == 1 }
        compose.onNodeWithText(context.getString(R.string.mono_indexes_title_count, 31)).assertIsDisplayed()
        compose.onNodeWithText("目录 31").assertIsDisplayed()
    }

    @Test
    fun 三十一条目录可滚到末条且点击不关闭面板() {
        var clicked: Int? = null
        var dismisses = 0
        val state = PagedList(List(31) { index(it + 1) }, total = 31, nextOffset = null, isLoading = false)
        compose.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    MonoIndexesSheet(
                        state = state,
                        onDismiss = { dismisses++ },
                        onRetry = {},
                        onLoadMore = {},
                        onIndexClick = { clicked = it },
                    )
                }
            }
        }

        val list = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("目录", substring = true)))
        list.performScrollToNode(hasText("目录 31"))
        compose.onNodeWithText("目录 31").performClick()

        assertEquals(31, clicked)
        assertEquals(0, dismisses)
        compose.onNodeWithText(context.getString(R.string.mono_indexes_title_count, 31)).assertIsDisplayed()
    }

    @Test
    fun 点目录进子页再返回时面板原样恢复() {
        val state = PagedList(List(31) { index(it + 1) }, total = 31, nextOffset = null, isLoading = false)
        compose.setContent {
            ChogumiTheme {
                val backStack = remember { mutableStateListOf<NavKey>(Route.MonoDetails(MonoType.CHARACTER, 50576)) }
                val registry = remember { PageSheetRegistry() }
                NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberPageSheetNavEntryDecorator(registry),
                    ),
                    entryProvider = { key ->
                        NavEntry(key) {
                            when (key) {
                                is Route.MonoDetails -> MonoIndexesSheet(
                                    state = state,
                                    onDismiss = {},
                                    onRetry = {},
                                    onLoadMore = {},
                                    onIndexClick = { backStack += Route.IndexDetails(it) },
                                )
                                is Route.IndexDetails -> Column {
                                    Text("目录详情")
                                    TextButton(onClick = { backStack.removeAt(backStack.lastIndex) }) {
                                        Text("返回角色页")
                                    }
                                }
                                else -> error("不该打开其它页面")
                            }
                        }
                    },
                )
            }
        }

        val list = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("目录", substring = true)))
        list.performScrollToNode(hasText("目录 1"))
        compose.onNodeWithText("目录 1").performClick()
        compose.onNodeWithText("目录详情").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mono_indexes_title_count, 31)).assertDoesNotExist()

        compose.onNodeWithText("返回角色页").performClick()
        compose.onNodeWithText(context.getString(R.string.mono_indexes_title_count, 31)).assertIsDisplayed()
    }

    @Test
    fun 入口只按服务端总数显示且首屏失败可重试() {
        var state by mutableStateOf(PagedList<IndexBo>(emptyList(), total = 31, nextOffset = 30, isLoading = false))
        var retries = 0
        compose.setContent {
            ChogumiTheme {
                MonoIndexesEntry(
                    state = state,
                    onOpen = {},
                    onRetry = { retries++ },
                )
            }
        }

        compose.onNodeWithTag(MonoIndexesEntryTag).assertIsDisplayed()
        compose.onNodeWithText(entryText(31)).assertIsDisplayed()

        compose.runOnIdle {
            state = PagedList<IndexBo>(emptyList(), total = 0, isLoading = false)
        }
        compose.onNodeWithTag(MonoIndexesEntryTag).assertDoesNotExist()

        compose.runOnIdle {
            state = PagedList<IndexBo>(isLoading = false, error = Failure.Http(500))
        }
        compose.onNodeWithTag(MonoIndexesRetryTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        assertEquals(1, retries)
    }

    private fun entryText(total: Int): String =
        context.resources.getQuantityString(R.plurals.mono_indexes_entry, total, total)

    private fun index(id: Int) = IndexBo(id = id, title = "目录 $id", total = 0, createdAt = id.toLong())
}
