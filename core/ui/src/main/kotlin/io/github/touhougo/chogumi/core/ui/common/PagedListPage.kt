package io.github.touhougo.chogumi.core.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.ui.common.navigation.ScrollToTopOnTabReselect
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.ListPageSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PageScaffold
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.SkeletonCrossfade
import io.github.touhougo.chogumi.core.ui.composables.loadMoreFooter

/**
 * 整页就是一份 [PagedList] 的列表页：顶栏 + [PagedListContent]——全仓唯一。
 * 社区的讨论版 / 评论 / 目录、资料页的日志、角色的关联人物 / 出演角色都用它，只在行与页头上不同。
 * 一页分几个页签、每个页签一份分页列表时（资料页的目录），顶栏下是页签与左右翻页，每页一个 [PagedListContent]。
 */
@Composable
fun <T> PagedListPage(
    title: String,
    state: PagedList<T>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    emptyText: String,
    contentPadding: PaddingValues,
    skeletonRow: @Composable (index: Int, count: Int) -> Unit,
    endText: String? = null,
    headerSkeleton: (@Composable () -> Unit)? = null,
    header: (LazyListScope.() -> Unit)? = null,
    headerReady: Boolean = true,
    snackbarHost: (@Composable () -> Unit)? = null,
    rows: LazyListScope.(List<T>) -> Unit,
) {
    PageScaffold(title = title, onBack = onBack, snackbarHost = snackbarHost) { padding ->
        PagedListContent(
            state = state,
            onRetry = onRetry,
            onLoadMore = onLoadMore,
            emptyText = emptyText,
            contentPadding = contentPadding,
            skeletonRow = skeletonRow,
            modifier = Modifier.fillMaxSize().padding(padding),
            endText = endText,
            headerSkeleton = headerSkeleton,
            header = header,
            headerReady = headerReady,
            rows = rows,
        )
    }
}

/**
 * 一份 [PagedList] 画成的列表：首屏（骨架 → 列表 / 错误 / 空态）+ 分段列表 + 统一页脚——全仓唯一。
 *
 * - 首屏画 [skeletonRow] 铺成的整页骨架（与真实行同一组件的占位，不转圈）；[headerReady] 为 false（筛选还没取到）时也保持骨架，
 *   避免页头晚到插进来把列表往下顶。
 * - [header] 是列表的头几项、随滚动离开（筛选行），重取时留在原位，错误、空态画在它下面；没有时错误、空态居中画满。
 * - 首屏三态由 [sectionStateOf]（分页列表版）判定：过滤让一页没有可见条目、但还有下一页时不算空，页脚继续续取。
 * - 要跟着列表滚动做事（往下滚时收起扩展 FAB）时传 [listState]。
 * - [stateAlignment] 是没有 [header] 时错误、空态在页里的位置：默认居中；在首开半展开的满高面板里传 `TopCenter`，
 *   居中会落到看得见的那一半以外。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> PagedListContent(
    state: PagedList<T>,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    emptyText: String,
    contentPadding: PaddingValues,
    skeletonRow: @Composable (index: Int, count: Int) -> Unit,
    modifier: Modifier = Modifier,
    endText: String? = null,
    headerSkeleton: (@Composable () -> Unit)? = null,
    header: (LazyListScope.() -> Unit)? = null,
    headerReady: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    stateAlignment: Alignment = Alignment.Center,
    rows: LazyListScope.(List<T>) -> Unit,
) {
    val section = sectionStateOf(state)
    ScrollToTopOnTabReselect(listState)
    SkeletonCrossfade(
        loading = !headerReady || section == SectionState.LOADING,
        modifier = modifier,
        skeleton = { ListPageSkeleton(contentPadding = contentPadding, header = headerSkeleton, row = skeletonRow) },
    ) {
        val error = state.error
        when {
            header == null && error != null -> Box(Modifier.fillMaxSize(), contentAlignment = stateAlignment) {
                RetryError(error, onRetry = onRetry, Modifier.fillMaxWidth(), compact = true)
            }
            header == null && section == SectionState.EMPTY ->
                Box(Modifier.fillMaxSize(), contentAlignment = stateAlignment) { EmptyLine(emptyText) }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                header?.invoke(this)
                when (section) {
                    SectionState.LOADING -> Unit
                    SectionState.ERROR -> if (error != null) {
                        item(key = "error") { RetryError(error, onRetry = onRetry, Modifier.fillMaxWidth(), compact = true) }
                    }
                    SectionState.EMPTY -> item(key = "empty") { EmptyLine(emptyText) }
                    SectionState.CONTENT -> rows(state.items)
                }
                loadMoreFooter(state, onLoadMore, endText)
            }
        }
    }
}

/** 一份分页列表的页脚（续取中 / 失败重试 / 到底）；没有可说的时整项缺席。 */
fun LazyListScope.loadMoreFooter(list: PagedList<*>, onLoadMore: () -> Unit, endText: String? = null) = loadMoreFooter(
    loadingMore = list.isLoadingMore,
    hasMore = list.hasMore,
    error = list.loadMoreError,
    endText = endText,
    hasItems = list.items.isNotEmpty(),
    onLoadMore = onLoadMore,
)

/** 网格版（占满一行）。 */
fun LazyGridScope.loadMoreFooter(list: PagedList<*>, onLoadMore: () -> Unit, endText: String? = null) = loadMoreFooter(
    loadingMore = list.isLoadingMore,
    hasMore = list.hasMore,
    error = list.loadMoreError,
    endText = endText,
    hasItems = list.items.isNotEmpty(),
    onLoadMore = onLoadMore,
)
