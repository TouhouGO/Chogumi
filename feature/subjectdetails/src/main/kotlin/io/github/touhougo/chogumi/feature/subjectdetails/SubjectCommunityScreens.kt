package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.touhougo.chogumi.core.model.UserBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.PagedListPage
import io.github.touhougo.chogumi.core.ui.composables.IndexListRow
import io.github.touhougo.chogumi.core.ui.composables.IndexListRowSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PostLeading
import io.github.touhougo.chogumi.core.ui.composables.PostListItem
import io.github.touhougo.chogumi.core.ui.composables.PostListItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.relativeTimeText
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** 列表子页左右边距（原型 `.sec{padding:8px 16px 4px}`）。 */
private val ListGutter = 16.dp

/** 列表子页的上下留白：骨架与真实列表共用。 */
private val ListPadding = PaddingValues(top = 8.dp, bottom = 24.dp)

/** 讨论版（原型截图 23、`boardScreen()`）：行是 [PostListItem]（与评论、个人日志同一份）。 */
@Composable
fun SubjectTopicsScreen(
    subjectId: Int,
    onBack: () -> Unit,
    onTopicClick: (Int) -> Unit,
) {
    val viewModel: SubjectTopicsViewModel = koinViewModel { parametersOf(subjectId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PagedListPage(
        title = stringResource(R.string.community_board),
        state = state,
        emptyText = stringResource(R.string.community_board_empty),
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        contentPadding = ListPadding,
        endText = stringResource(R.string.community_list_end),
        skeletonRow = { index, count ->
            PostListItemSkeleton(index, count, Modifier.padding(horizontal = ListGutter), leading = PostLeading.Avatar(null))
        },
    ) { topics ->
        itemsIndexed(topics, key = { _, topic -> topic.id }) { index, topic ->
            PostRow(topic.title, topic.replyCount, topic.creator, topic.createdAt, index, topics.size, onClick = { onTopicClick(topic.id) })
        }
    }
}

/** 讨论版与评论的一行：[PostListItem]，带作者头像与相对时间；评论多一段摘要。 */
@Composable
private fun PostRow(
    title: String,
    replies: Int,
    author: UserBo?,
    createdAt: Long,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    summary: String = "",
) {
    PostListItem(
        title = title,
        replies = replies,
        repliesDescription = pluralStringResource(R.plurals.topic_reply_count, replies, replies),
        index = index,
        count = count,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = ListGutter),
        leading = PostLeading.Avatar(author?.avatarUrl),
        author = author?.let(::displayNameOf),
        summary = summary,
        time = relativeTimeText(createdAt),
    )
}

/** 评论 = 用户日志（原型截图 24、`reviewsScreen()`）：行是 [PostListItem]，比讨论版多一段摘要。 */
@Composable
fun SubjectReviewsScreen(
    subjectId: Int,
    onBack: () -> Unit,
    onBlogClick: (Int) -> Unit,
) {
    val viewModel: SubjectReviewsViewModel = koinViewModel { parametersOf(subjectId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PagedListPage(
        title = stringResource(R.string.community_reviews),
        state = state,
        emptyText = stringResource(R.string.community_reviews_empty),
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        contentPadding = ListPadding,
        endText = stringResource(R.string.community_list_end),
        skeletonRow = { index, count ->
            PostListItemSkeleton(index, count, Modifier.padding(horizontal = ListGutter), leading = PostLeading.Avatar(null), withSummary = true)
        },
    ) { blogs ->
        itemsIndexed(blogs, key = { _, blog -> blog.id }) { index, blog ->
            PostRow(blog.title, blog.replies, blog.author, blog.createdAt, index, blogs.size, onClick = { onBlogClick(blog.id) }, summary = blog.summary)
        }
    }
}

/** 收录它的目录（原型截图 25、`indexesScreen()`）：创建者头像 40、标题 1 行、「创建者、N 部」、›。 */
@Composable
fun SubjectIndexesScreen(
    subjectId: Int,
    onBack: () -> Unit,
    onIndexClick: (Int) -> Unit,
) {
    val viewModel: SubjectIndexesViewModel = koinViewModel { parametersOf(subjectId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PagedListPage(
        title = stringResource(R.string.community_indexes_title),
        state = state,
        emptyText = stringResource(R.string.community_indexes_empty),
        onBack = onBack,
        onRetry = viewModel::retry,
        onLoadMore = viewModel::loadMore,
        contentPadding = ListPadding,
        endText = stringResource(R.string.community_list_end),
        skeletonRow = { index, count -> IndexListRowSkeleton(index, count, Modifier.padding(horizontal = ListGutter)) },
    ) { indexes ->
        itemsIndexed(indexes, key = { _, index -> index.id }) { position, index ->
            IndexListRow(
                index = index,
                position = position,
                count = indexes.size,
                onClick = { onIndexClick(index.id) },
                modifier = Modifier.padding(horizontal = ListGutter),
            )
        }
    }
}
