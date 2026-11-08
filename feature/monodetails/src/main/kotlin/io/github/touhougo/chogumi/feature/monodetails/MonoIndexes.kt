package io.github.touhougo.chogumi.feature.monodetails

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.paging.PagedList
import io.github.touhougo.chogumi.core.model.IndexBo
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListContent
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.IndexListRow
import io.github.touhougo.chogumi.core.ui.composables.IndexListRowSkeleton
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.ScrollableModalSheet
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SheetTitleRow
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

internal const val MonoIndexesEntryTag = "mono_indexes_entry"
internal const val MonoIndexesRetryTag = "mono_indexes_retry"

/**
 * 页面里的关联目录入口：服务端总数为正就保留，哪怕第一页全被过滤；首屏失败时在原位给重试。
 */
@Composable
internal fun MonoIndexesEntry(
    state: PagedList<IndexBo>,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.total > 0 -> SegmentedItem(
            index = 0,
            count = 1,
            onClick = onOpen,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = ChogumiDimens.ScreenPadding, vertical = 12.dp)
                .testTag(MonoIndexesEntryTag),
            leadingContent = { IndexEntryIcon() },
            trailingContent = { IndexEntryChevron() },
        ) {
            IndexEntryText(state.total)
        }

        sectionStateOf(state) == SectionState.ERROR -> state.error?.let { failure ->
            RetryError(
                failure = failure,
                onRetry = onRetry,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag(MonoIndexesRetryTag),
                compact = true,
            )
        }
    }
}

/**
 * 关联目录的「全部」面板：标题与入口共用服务端总数，列表只画已到页面，续取与失败由统一分页列表内容处理。
 * 按服务端顺序画（与条目页的关联目录一样）：本机再排一遍，后到的页会插进已经看过的行之间。
 */
@Composable
internal fun MonoIndexesSheet(
    state: PagedList<IndexBo>,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onIndexClick: (Int) -> Unit,
) {
    ScrollableModalSheet(onDismiss = onDismiss) {
        SheetTitleRow(stringResource(R.string.mono_indexes_title_count, state.total))
        PagedListContent(
            state = state,
            onRetry = onRetry,
            onLoadMore = onLoadMore,
            emptyText = stringResource(R.string.no_content),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            skeletonRow = { index, count ->
                IndexListRowSkeleton(
                    index = index,
                    count = count,
                    modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            endText = stringResource(R.string.load_more_end_indexes),
            stateAlignment = Alignment.TopCenter,
        ) { indexes ->
            itemsIndexed(indexes, key = { _, index -> index.id }) { position, index ->
                IndexListRow(
                    index = index,
                    position = position,
                    count = indexes.size,
                    onClick = { onIndexClick(index.id) },
                    modifier = Modifier.padding(horizontal = ChogumiDimens.ScreenPadding),
                )
            }
        }
    }
}

@Composable
private fun IndexEntryIcon() {
    Icon(
        painter = painterResource(R.drawable.bookmark_24),
        contentDescription = null,
    )
}

@Composable
private fun IndexEntryChevron() {
    Icon(
        painter = painterResource(R.drawable.chevron_right_24),
        contentDescription = null,
    )
}

@Composable
private fun IndexEntryText(total: Int) {
    Text(pluralStringResource(R.plurals.mono_indexes_entry, total, total))
}
