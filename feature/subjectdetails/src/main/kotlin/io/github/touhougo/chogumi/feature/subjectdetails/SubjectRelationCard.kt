package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectRelationGroup
import io.github.touhougo.chogumi.core.model.SubjectRelationKind
import io.github.touhougo.chogumi.core.model.SubjectRelationWeight
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.composables.AdaptiveGridRows
import io.github.touhougo.chogumi.core.ui.composables.AdaptiveGridSkeleton
import io.github.touhougo.chogumi.core.ui.composables.CoverBadge
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeInset
import io.github.touhougo.chogumi.core.ui.composables.GridCell
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardSkeleton
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.composables.PosterCardWidth
import io.github.touhougo.chogumi.core.ui.composables.labelRes

/** 关联作品卡片的 testTag。 */
internal const val DetailsRelationCardTag = "details_relation_card"

/**
 * 一张关联卡要画的东西：对面条目 + 它在本条目里的关系名（左上角徽标）。
 * [label] 为 null 不画徽标：单行本 tab 里每张都是单行本，徽标没有信息量。
 */
internal data class RelationCardItem(val relation: SubjectRelationBo, @StringRes val label: Int?)

/**
 * 分组结果（[io.github.touhougo.chogumi.core.model.groupSubjectRelations]，已按权重排好）→ 卡片列表，关系名取自所在分组。
 * 概览与关联子页共用这一条展开规则。
 */
internal fun List<SubjectRelationGroup>.toCardItems(): List<RelationCardItem> =
    flatMap { group -> group.items.map { RelationCardItem(it, group.labelRes()) } }

/** 关联卡（[PosterCardStyle.CAROUSEL]），左上角标是关系名，右上是「我」的收藏状态（长按标记）。宽度由调用方给：概览横排是 [PosterCardWidth]，关联子页的网格是一格的宽。 */
@Composable
internal fun SubjectRelationCard(
    item: RelationCardItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val relation = item.relation
    val label = item.label?.let { stringResource(it) }
    PosterCard(
        imageUrl = relation.images?.posterUrl(),
        title = displayNameOf(relation),
        style = PosterCardStyle.CAROUSEL,
        onClick = onClick,
        mark = rememberPosterMark(relation.id, relation.type),
        modifier = modifier.testTag(DetailsRelationCardTag),
    ) {
        label?.let { CoverBadge(it, Modifier.align(Alignment.TopStart).padding(CoverBadgeInset)) }
    }
}

/** 关联卡的骨架。 */
@Composable
internal fun SubjectRelationCardSkeleton(modifier: Modifier = Modifier) {
    PosterCardSkeleton(PosterCardStyle.CAROUSEL, modifier.testTag(DetailsRelationCardTag))
}

/** 骨架画几行网格（设计决定，not in M3：两行铺满首屏即可）。 */
private const val RELATION_GRID_SKELETON_ROWS = 2

/**
 * 关联卡网格（原型 `.rgrid`）：列数随屏宽（[GridCell.Poster]）、行距 12、左右 16、下 8。
 * 关联子页每个权重组一个，单行本 tab 一个。
 * 放在可整页滚动的列里，不是惰性网格：一组最多几十张，整页滚动才能和头部、分组标题一起走。
 */
@Composable
internal fun RelationCardGrid(
    items: List<RelationCardItem>,
    onSubjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AdaptiveGridRows(
        items = items,
        cell = GridCell.Poster,
        modifier = modifier.padding(bottom = 8.dp),
    ) { item ->
        SubjectRelationCard(item = item, onClick = { onSubjectClick(item.relation.id) })
    }
}

/** 网格骨架：与 [RelationCardGrid] 同一套列数、间距与内边距。 */
@Composable
internal fun RelationCardGridSkeleton() {
    AdaptiveGridSkeleton(
        rows = RELATION_GRID_SKELETON_ROWS,
        cell = GridCell.Poster,
        modifier = Modifier.padding(vertical = 8.dp),
    ) { SubjectRelationCardSkeleton() }
}

/** 权重组 → 关联子页的分组标题（主线 / 衍生 / 音乐与周边 / 客串与其他）。 */
@StringRes
internal fun SubjectRelationWeight.labelRes(): Int = when (this) {
    SubjectRelationWeight.MAIN -> R.string.relation_weight_main
    SubjectRelationWeight.DERIVATIVE -> R.string.relation_weight_derivative
    SubjectRelationWeight.MUSIC_AND_MERCH -> R.string.relation_weight_music_and_merch
    SubjectRelationWeight.OTHER -> R.string.relation_weight_other
}

/** 关系分组 → 关系名（卡片徽标）；改编组按对面条目类型显示（书籍 / 动画 / 游戏…）。全仓唯一的关系名映射。 */
@StringRes
private fun SubjectRelationGroup.labelRes(): Int = when (kind) {
    SubjectRelationKind.ADAPTATION -> (adaptationType ?: SubjectType.UNKNOWN).labelRes()
    SubjectRelationKind.SEQUEL -> R.string.relation_sequel
    SubjectRelationKind.PREQUEL -> R.string.relation_prequel
    SubjectRelationKind.PARENT_STORY -> R.string.relation_parent_story
    SubjectRelationKind.SUMMARY -> R.string.relation_summary
    SubjectRelationKind.FULL_STORY -> R.string.relation_full_story
    SubjectRelationKind.SIDE_STORY -> R.string.relation_side_story
    SubjectRelationKind.SPIN_OFF -> R.string.relation_spin_off
    SubjectRelationKind.SAME_SETTING -> R.string.relation_same_setting
    SubjectRelationKind.ALTERNATIVE_VERSION -> R.string.relation_alternative_version
    SubjectRelationKind.ALTERNATIVE_SETTING -> R.string.relation_alternative_setting
    SubjectRelationKind.CHARACTER -> R.string.relation_character
    SubjectRelationKind.SERIES -> R.string.relation_series
    SubjectRelationKind.MAIN_VERSION -> R.string.relation_main_version
    SubjectRelationKind.VERSION -> R.string.relation_version
    SubjectRelationKind.DLC -> R.string.relation_dlc
    SubjectRelationKind.COLLECTION -> R.string.relation_collection
    SubjectRelationKind.IN_COLLECTION -> R.string.relation_in_collection
    SubjectRelationKind.OFFPRINT -> R.string.relation_offprint
    SubjectRelationKind.ALBUM -> R.string.relation_album
    SubjectRelationKind.OST -> R.string.relation_ost
    SubjectRelationKind.OPENING_SONG -> R.string.relation_opening_song
    SubjectRelationKind.ENDING_SONG -> R.string.relation_ending_song
    SubjectRelationKind.CHARACTER_SONG -> R.string.relation_character_song
    SubjectRelationKind.INSERT_SONG -> R.string.relation_insert_song
    SubjectRelationKind.IMAGE_SONG -> R.string.relation_image_song
    SubjectRelationKind.DRAMA -> R.string.relation_drama
    SubjectRelationKind.COLLABORATION -> R.string.relation_collaboration
    SubjectRelationKind.OTHER -> R.string.relation_other
}
