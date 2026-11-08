package io.github.touhougo.chogumi.feature.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.common.loadMoreFooter
import io.github.touhougo.chogumi.core.ui.composables.CollectionTypeBadge
import io.github.touhougo.chogumi.core.ui.composables.ConnectedChoiceGroup
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.PersonGridItem
import io.github.touhougo.chogumi.core.ui.composables.PersonGridItemSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.composables.RetryError
import io.github.touhougo.chogumi.core.ui.composables.iconRes
import io.github.touhougo.chogumi.core.ui.composables.labelRes

/**
 * 收藏 tab：分类由**右下角 FAB 菜单**切换（6 项）。
 * 「目录」不在收藏 tab（有独立的目录页）。
 *
 * 分类内容沿用自适应网格；「角色与人物」一档内部再分角色/人物两小类（小类随分类值走）。
 */
internal fun LazyGridScope.shelfItems(
    state: ShelfState,
    /** 看的是自己的收藏：资料主人就是「我」，只留右上角的统一角标。 */
    isSelf: Boolean,
    onCharacterPersonTabChange: (CharacterPersonTab) -> Unit,
    onSubjectClick: (Int) -> Unit,
    onCharacterClick: (Int) -> Unit,
    onPersonClick: (Int) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    endText: String?,
) {
    val category = state.shownCategory
    // 「角色与人物」档内部再分角色/人物：原型 `.cbg`。
    if (category is ShelfCategory.CharacterPerson) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ConnectedChoiceGroup(
                options = CharacterPersonTab.entries,
                selected = category.tab,
                label = { stringResource(it.labelRes) },
                onSelect = onCharacterPersonTabChange,
            )
        }
    }

    val content = shelfContentOf(state)
    val list = content.list
    val error = list.error
    if (error != null) {
        item(span = { GridItemSpan(maxLineSpan) }) { RetryError(error, onRetry = onRefresh, Modifier.fillMaxWidth()) }
        return
    }
    // 空页仍有游标时保留分页出口，不能误画成空收藏（[PagedList.isConfirmedEmpty]）。
    if (list.isConfirmedEmpty) {
        item(span = { GridItemSpan(maxLineSpan) }) { EmptyLine(stringResource(R.string.empty_shelf)) }
        return
    }
    when (content) {
        is ProfileShelfContent.Subjects -> if (list.isLoading) {
            items(6) { PosterCardSkeleton(PosterCardStyle.GRID) }
        } else {
            items(content.list.items, key = { it.subjectId }) { collection ->
                val subject = collection.subject
                PosterCard(
                    imageUrl = subject?.let { it.coverUrl },
                    title = subject?.let { displayNameOf(it) } ?: "#${collection.subjectId}",
                    style = PosterCardStyle.GRID,
                    onClick = { onSubjectClick(collection.subjectId) },
                    mark = rememberPosterMark(collection.subjectId, collection.subjectType),
                ) {
                    // 右上角是「我」的收藏状态（长按标记的角标，状态色）。看别人的收藏时左上再画资料主人的状态：
                    // 同一种角标、中性色（[CollectionTypeBadge] 的 tinted），和右上角的一眼分得清是谁的
                    if (!isSelf) {
                        CollectionTypeBadge(
                            collection.type,
                            collection.subjectType,
                            tinted = false,
                            labeled = false,
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        )
                    }
                }
            }
        }
        is ProfileShelfContent.Characters -> if (list.isLoading) {
            items(6) { PersonGridItemSkeleton() }
        } else {
            items(content.list.items, key = { it.id }) { character ->
                PersonGridItem(
                    title = character.name,
                    imageUrl = character.images?.avatarUrl(),
                    onClick = { onCharacterClick(character.id) },
                )
            }
        }
        is ProfileShelfContent.Persons -> if (list.isLoading) {
            items(6) { PersonGridItemSkeleton() }
        } else {
            items(content.list.items, key = { it.id }) { person ->
                PersonGridItem(
                    title = person.name,
                    imageUrl = person.images?.avatarUrl(),
                    onClick = { onPersonClick(person.id) },
                )
            }
        }
    }
    loadMoreFooter(list, onLoadMore, endText)
}


/** 收藏 FAB 的图标：条目域走 [SubjectType.iconRes]，角色与人物用 face 图标。 */
internal fun ShelfCategory.fabIconRes(): Int = when (this) {
    is ShelfCategory.SubjectDomain -> type.iconRes()
    is ShelfCategory.CharacterPerson -> R.drawable.face_24
}


/** 收藏 FAB 菜单项的测试标签（唯一来源）；仪器测试按它定位各项。 */
internal val ShelfCategory.fabOptionTestTag: String
    get() = when (this) {
        is ShelfCategory.SubjectDomain -> "shelf_fab_option_${type.name}"
        is ShelfCategory.CharacterPerson -> "shelf_fab_option_CHARACTER_PERSON"
    }
