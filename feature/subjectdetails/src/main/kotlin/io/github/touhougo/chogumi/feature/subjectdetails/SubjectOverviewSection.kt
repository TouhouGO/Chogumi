package io.github.touhougo.chogumi.feature.subjectdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.foundation.Failure
import io.github.touhougo.chogumi.core.foundation.Load
import io.github.touhougo.chogumi.core.foundation.failureOrNull
import io.github.touhougo.chogumi.core.foundation.orEmpty
import io.github.touhougo.chogumi.core.foundation.valueOrNull
import io.github.touhougo.chogumi.core.common.occurrenceKeys
import io.github.touhougo.chogumi.core.model.RelatedCharacterBo
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.SubjectRelationBo
import io.github.touhougo.chogumi.core.model.SubjectSeries
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.groupSubjectRelations
import io.github.touhougo.chogumi.core.reader.CollapsibleSynopsis
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.common.SectionState
import io.github.touhougo.chogumi.core.ui.common.sectionStateOf
import io.github.touhougo.chogumi.core.ui.composables.CAROUSEL_SKELETON_COUNT
import io.github.touhougo.chogumi.core.ui.composables.CarouselPerson
import io.github.touhougo.chogumi.core.ui.composables.CarouselPersonSkeleton
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.EmptyLine
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.PosterCardWidth

/** 概览「关联作品」横排露出的张数（设计稿：前 8 个，按权重）。 */
private const val OVERVIEW_RELATION_COUNT = 8

/** 概览角色横排最多露几个；更多的点「全部」去人物 tab。 */
private const val OVERVIEW_CHARACTER_COUNT = 10

/** 一个三态区块：这一拍的列表与重试。概览、人物 tab 与第 2 个 tab 共用这一份描述。 */
internal data class AsyncList<T>(val load: Load<List<T>>, val onRetry: () -> Unit) {
    val items: List<T> get() = load.orEmpty()
    val error: Failure? get() = load.failureOrNull()
    val state: SectionState get() = sectionStateOf(load)
}

/**
 * 概览 tab（原型 `tabBody` overview）：简介（折叠 3 行）→ 角色横排（前 10 个，副行是剧中定位）→ 关联作品横排（前 8 个）
 * → 系列（沿前传、续集走出的同类型作品，与统计 tab 同一个 [SeriesRow]，这里不画分数折线；没有系列不出现）。
 * 制作人员不在概览露出（概览的横排位置留给关联作品），到人物 tab 的「制作人员」分组看。
 *
 * 每块的三态由 [AsyncList.state]（[sectionStateOf]）决定；两块都确认为空时画一行说明。
 * 「全部 ›」：角色切到人物 tab 的角色分组，关联作品进关联子页。
 */
@Composable
internal fun SubjectOverviewSection(
    synopsis: SlotFill<String>,
    synopsisExpanded: Boolean,
    onSynopsisToggle: () -> Unit,
    characters: AsyncList<RelatedCharacterBo>,
    relations: AsyncList<SubjectRelationBo>,
    subject: SubjectBo,
    series: Load<SubjectSeries>,
    onCharacterClick: (Int) -> Unit,
    onSubjectClick: (Int) -> Unit,
    onCharactersSeeAll: () -> Unit,
    onRelationsSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val relationCards = remember(relations.items) { groupSubjectRelations(relations.items).toCardItems() }

    Column(modifier = modifier.fillMaxWidth()) {
        if (synopsis != SlotFill.Absent) {
            CollapsibleSynopsis(
                text = (synopsis as? SlotFill.Real)?.value,
                expanded = synopsisExpanded,
                onToggle = onSynopsisToggle,
            )
        }

        ListSection(
            title = stringResource(R.string.characters),
            state = characters.state,
            error = characters.error,
            onRetry = characters.onRetry,
            onSeeAll = onCharactersSeeAll,
            skeleton = { CarouselPersonSkeleton() },
        ) {
            CarouselRow {
                items(characters.items.take(OVERVIEW_CHARACTER_COUNT), key = { it.id }) { character ->
                    CarouselPerson(
                        name = displayNameOf(character),
                        // 副行写剧中定位（主角 / 配角），不写声优：声优在人物 tab 与角色页
                        caption = character.role?.let { stringResource(it.labelRes()) },
                        imageUrl = character.images?.avatarUrl(),
                        onClick = { onCharacterClick(character.id) },
                    )
                }
            }
        }

        ListSection(
            title = stringResource(R.string.detail_related_works),
            state = relations.state,
            error = relations.error,
            onRetry = relations.onRetry,
            count = relations.items.size,
            onSeeAll = onRelationsSeeAll,
            skeleton = {
                CarouselRow(userScrollEnabled = false) {
                    items(CAROUSEL_SKELETON_COUNT) { SubjectRelationCardSkeleton(Modifier.width(PosterCardWidth)) }
                }
            },
        ) {
            CarouselRow {
                val shown = relationCards.take(OVERVIEW_RELATION_COUNT)
                val keys = occurrenceKeys(shown) { "${it.relation.id}_${it.relation.relationId}" }
                itemsIndexed(shown, key = { index, _ -> keys[index] }) { _, card ->
                    SubjectRelationCard(
                        item = card,
                        onClick = { onSubjectClick(card.relation.id) },
                        modifier = Modifier.width(PosterCardWidth),
                    )
                }
            }
        }

        ListSection(
            title = stringResource(R.string.stats_series_title),
            state = seriesSectionState(series),
            error = null,
            onRetry = {},
            skeleton = { SeriesRowSkeleton(scores = false) },
        ) {
            SeriesRow(subject, requireNotNull(series.valueOrNull()), scores = false, onSubjectClick = onSubjectClick)
        }

        if (listOf(characters.state, relations.state).all { it == SectionState.EMPTY }) {
            EmptyLine(stringResource(R.string.detail_overview_empty))
        }
    }
}
