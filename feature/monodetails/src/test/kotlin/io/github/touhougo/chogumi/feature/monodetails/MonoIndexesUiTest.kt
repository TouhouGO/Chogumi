package io.github.touhougo.chogumi.feature.monodetails

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.testing.PageSheetTestHost
import io.github.touhougo.chogumi.core.ui.theme.ChogumiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "zh")
class MonoIndexesUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `短首屏与空首屏都用服务端总数显示入口`() {
        var state by mutableStateOf(PagedList(List(17) { index(it + 1) }, total = 31, nextOffset = 30, isLoading = false))
        compose.setContent {
            ChogumiTheme {
                MonoIndexesEntry(state = state, onOpen = {}, onRetry = {})
            }
        }

        compose.onNodeWithTag(MonoIndexesEntryTag).assertIsDisplayed()
        compose.onNodeWithText(entryText(31)).assertIsDisplayed()

        compose.runOnIdle {
            state = PagedList(emptyList(), total = 31, nextOffset = 30, isLoading = false)
        }
        compose.onNodeWithTag(MonoIndexesEntryTag).assertIsDisplayed()
        compose.onNodeWithText(entryText(31)).assertIsDisplayed()
    }

    @Test
    fun `total为0不画入口`() {
        compose.setContent {
            ChogumiTheme {
                MonoIndexesEntry(state = PagedList(isLoading = false), onOpen = {}, onRetry = {})
            }
        }

        compose.onNodeWithTag(MonoIndexesEntryTag).assertDoesNotExist()
        compose.onNodeWithTag(MonoIndexesRetryTag).assertDoesNotExist()
    }

    @Test
    fun `首屏失败在原位给出可点击重试`() {
        var retries = 0
        compose.setContent {
            ChogumiTheme {
                MonoIndexesEntry(
                    state = PagedList(isLoading = false, error = Failure.Http(500)),
                    onOpen = {},
                    onRetry = { retries++ },
                )
            }
        }

        compose.onNodeWithTag(MonoIndexesRetryTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `空首屏的面板自动续取后保留服务端总数`() {
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
    fun `31条目录可滚到最后一条且点击不关闭面板`() {
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

        compose.onNodeWithText(context.getString(R.string.mono_indexes_title_count, 31)).assertIsDisplayed()
        val list = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("目录", substring = true)))
        list.performScrollToNode(hasText("目录 31"))
        compose.onNodeWithText("目录 31").performClick()

        assertEquals(31, clicked)
        assertEquals(0, dismisses)
        compose.onNodeWithText(context.getString(R.string.mono_indexes_title_count, 31)).assertIsDisplayed()
    }

    @Test
    fun `面板按服务端顺序画 后到的页接在已看过的行后面`() {
        // 第二页里的「目录 3」比第一页的都新：按时间重排它会插到最前，已经看过的行整体往下挪。
        val state = PagedList(listOf(index(2), index(1), index(3)), total = 3, nextOffset = null, isLoading = false)
        compose.setContent {
            ChogumiTheme {
                PageSheetTestHost {
                    MonoIndexesSheet(state = state, onDismiss = {}, onRetry = {}, onLoadMore = {}, onIndexClick = {})
                }
            }
        }

        val rows = compose.onAllNodes(hasText("目录 ", substring = true)).fetchSemanticsNodes()
            .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString("")?.takeIf { it.startsWith("目录 ") } }
        assertEquals(listOf("目录 2", "目录 1", "目录 3"), rows)
    }

    private fun entryText(total: Int): String =
        context.resources.getQuantityString(R.plurals.mono_indexes_entry, total, total)

    private fun index(id: Int) = IndexBo(id = id, title = "目录 $id", total = 0, createdAt = id.toLong())
}
