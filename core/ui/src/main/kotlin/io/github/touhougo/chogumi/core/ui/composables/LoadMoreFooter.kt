package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.res.R
import kotlinx.coroutines.delay

/** M3 等待时长规则（feedback.md:11）：200ms 以内完成不显示加载指示器。 */
internal const val LOAD_INDICATOR_DELAY_MS = 200L

/** 加载页脚 UI 测试 Tag。 */
internal const val LoadMoreIndicatorTag = "load_more_indicator"
internal const val LoadMoreRetryButtonTag = "load_more_retry_button"
internal const val LoadMoreEndTextTag = "load_more_end_text"

/**
 * 加载页脚呈现状态（单一真源）。
 */
enum class LoadMoreStatus {
    IDLE,
    LOADING,
    ERROR,
    PAUSED,
    END,
    ABSENT,
}

/**
 * 决定加载页脚状态与是否缺席的唯一纯函数（单一真源）。
 *
 * 判定优先级：
 * 1. [error] != null -> [LoadMoreStatus.ERROR]（出错优先）
 * 2. [loadingMore] -> [LoadMoreStatus.LOADING]（加载中）
 * 3. [pausedText] 非空 -> [LoadMoreStatus.PAUSED]（还有更多，但调用方决定先停下、等用户点「继续」；空列表也显示）
 * 4. ![hasMore] -> 列表有内容（[hasItems]）且 [endText] 非空白时为 [LoadMoreStatus.END]，
 *    否则为 [LoadMoreStatus.ABSENT]（整项缺席不渲染；空列表由各页自己的空态说话，不叠一句「没有更多」）
 * 5. 其他情况（[hasMore] 为 true 且未在加载中） -> [LoadMoreStatus.IDLE]（待续取）
 *
 * [hasItems] 只在给了 [endText] 时才起作用，所以默认 true：不给到底文案的调用方不必关心它。
 */
fun resolveLoadMoreStatus(
    loadingMore: Boolean,
    hasMore: Boolean,
    error: Failure?,
    endText: String? = null,
    hasItems: Boolean = true,
    pausedText: String? = null,
): LoadMoreStatus = when {
    error != null -> LoadMoreStatus.ERROR
    loadingMore -> LoadMoreStatus.LOADING
    hasMore && pausedText != null -> LoadMoreStatus.PAUSED
    !hasMore -> if (hasItems && !endText.isNullOrBlank()) LoadMoreStatus.END else LoadMoreStatus.ABSENT
    else -> LoadMoreStatus.IDLE
}

/**
 * 分页页脚内容区（唯一真源）。
 *
 * 一个组件管三种状态（以及进入组合自动触发的待续取态 [LoadMoreStatus.IDLE] 与整项缺席 [LoadMoreStatus.ABSENT]）：
 * - 加载中（[LoadMoreStatus.LOADING]）：M3 [ContentLoadingIndicator] 默认尺寸（容器 48dp、active 38dp）；
 *   加载在 200ms 内完成时不显示（M3 等待时长规则：feedback.md:11）；读屏念「正在加载更多」并具备进度条语义；
 * - 出错（[LoadMoreStatus.ERROR]）：一行「没加载出来」+ [FilledTonalButton]「重试」，点击重试同一页；
 * - 暂停（[LoadMoreStatus.PAUSED]）：一行 [pausedText]（为什么停下）+ [FilledTonalButton]「继续往前找」，点了调 [onLoadMore]；
 *   不自动续取——调用方按自己的条件停下（动态按类别往前翻了一大段没找到），由用户决定要不要再找；
 * - 到底（[LoadMoreStatus.END]）：一行小字（bodySmall、onSurfaceVariant，居中）。文案由调用方给；
 * - 到底无文案（[LoadMoreStatus.ABSENT]）：整项缺席，不渲染且不占高度；
 * - 待续取（[LoadMoreStatus.IDLE]）：进入组合通过 [LaunchedEffect] 自动触发 [onLoadMore]，自身不占视觉空间。
 */
@Composable
fun LoadMoreFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    error: Failure?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    endText: String? = null,
    hasItems: Boolean = true,
    pausedText: String? = null,
) {
    val status = resolveLoadMoreStatus(
        loadingMore = loadingMore,
        hasMore = hasMore,
        error = error,
        endText = endText,
        hasItems = hasItems,
        pausedText = pausedText,
    )

    if (status == LoadMoreStatus.ABSENT) {
        return
    }

    if (status == LoadMoreStatus.IDLE) {
        LaunchedEffect(Unit) { onLoadMore() }
        return
    }

    var indicatorVisible by remember { mutableStateOf(false) }
    LaunchedEffect(loadingMore) {
        if (loadingMore) {
            delay(LOAD_INDICATOR_DELAY_MS)
            indicatorVisible = true
        } else {
            indicatorVisible = false
        }
    }

    if (status == LoadMoreStatus.LOADING && !indicatorVisible) {
        return
    }

    if (status == LoadMoreStatus.END) {
        // END 只在 endText 非空白时由 [resolveLoadMoreStatus] 给出
        ListEndLine(endText.orEmpty(), modifier)
        return
    }

    // 原型 phase3-v3 `.loadmore`：上 20 / 左右 16 / 下 8
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            LoadMoreStatus.LOADING -> {
                val loadingDescription = stringResource(R.string.load_more_loading)
                ContentLoadingIndicator(
                    modifier = Modifier
                        .semantics {
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            contentDescription = loadingDescription
                        }
                        .testTag(LoadMoreIndicatorTag),
                )
            }
            LoadMoreStatus.ERROR -> FooterAction(
                text = stringResource(R.string.load_more_failed),
                action = stringResource(R.string.retry),
                icon = R.drawable.refresh_24,
                onClick = onLoadMore,
                modifier = Modifier.testTag(LoadMoreRetryButtonTag),
            )
            LoadMoreStatus.PAUSED -> FooterAction(
                text = pausedText.orEmpty(),
                action = stringResource(R.string.load_more_continue),
                icon = R.drawable.arrow_downward_24,
                onClick = onLoadMore,
            )
            LoadMoreStatus.IDLE, LoadMoreStatus.ABSENT, LoadMoreStatus.END -> Unit
        }
    }
}

/**
 * 列表到底的一行小字（bodySmall、onSurfaceVariant，居中；`.endnote` 左右 24、上 20、下 8，本项目设计决定，not in M3）。
 * 分页列表的页脚和帖子楼层的末尾都是它。
 */
@Composable
fun ListEndLine(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(LoadMoreEndTextTag),
        )
    }
}

/**
 * 页脚的「一行说明 + 一个按钮」：出错时「没加载出来 / 重试」，暂停时「为什么停下 / 继续往前找」。
 * Small 按钮带前置图标：图标 20dp、图文间距 8dp、左右 16dp（component-tokens ButtonSmall）。
 */
@Composable
private fun FooterAction(text: String, action: String, icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(
            onClick = onClick,
            contentPadding = ButtonDefaults.SmallContentPadding,
            modifier = modifier,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.SmallIconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(text = action)
        }
    }
}

/**
 * 供 [LazyListScope] 使用的无缝加载页脚扩展。
 */
fun LazyListScope.loadMoreFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    error: Failure?,
    endText: String? = null,
    hasItems: Boolean = true,
    pausedText: String? = null,
    onLoadMore: () -> Unit,
) {
    if (resolveLoadMoreStatus(loadingMore, hasMore, error, endText, hasItems, pausedText) == LoadMoreStatus.ABSENT) return
    item {
        LoadMoreFooter(
            loadingMore = loadingMore,
            hasMore = hasMore,
            error = error,
            endText = endText,
            hasItems = hasItems,
            pausedText = pausedText,
            onLoadMore = onLoadMore,
        )
    }
}

/**
 * 供 [LazyGridScope] 使用的无缝加载页脚扩展。
 */
fun LazyGridScope.loadMoreFooter(
    loadingMore: Boolean,
    hasMore: Boolean,
    error: Failure?,
    endText: String? = null,
    hasItems: Boolean = true,
    pausedText: String? = null,
    onLoadMore: () -> Unit,
) {
    if (resolveLoadMoreStatus(loadingMore, hasMore, error, endText, hasItems, pausedText) == LoadMoreStatus.ABSENT) return
    item(span = { GridItemSpan(maxLineSpan) }) {
        LoadMoreFooter(
            loadingMore = loadingMore,
            hasMore = hasMore,
            error = error,
            endText = endText,
            hasItems = hasItems,
            pausedText = pausedText,
            onLoadMore = onLoadMore,
        )
    }
}
