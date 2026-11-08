package io.github.touhougo.chogumi.feature.monodetails.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.common.occurrenceKeys
import io.github.touhougo.chogumi.core.model.CastRole
import io.github.touhougo.chogumi.core.model.MonoWorkBo
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.collection.rememberPosterMark
import io.github.touhougo.chogumi.core.ui.common.LocalAppFeatures
import io.github.touhougo.chogumi.core.ui.composables.CoverBadge
import io.github.touhougo.chogumi.core.ui.composables.CoverBadgeInset
import io.github.touhougo.chogumi.core.ui.composables.MetaText
import io.github.touhougo.chogumi.core.ui.composables.PosterCard
import io.github.touhougo.chogumi.core.ui.composables.PosterCardStyle
import io.github.touhougo.chogumi.core.ui.composables.SegmentedItem
import io.github.touhougo.chogumi.core.ui.composables.SkeletonText
import io.github.touhougo.chogumi.core.ui.composables.SubjectPoster
import io.github.touhougo.chogumi.core.ui.composables.SubjectPosterSkeletonBox
import io.github.touhougo.chogumi.core.ui.composables.TextPill
import io.github.touhougo.chogumi.core.ui.composables.starScoreText
import io.github.touhougo.chogumi.core.ui.composables.labelRes
import io.github.touhougo.chogumi.core.ui.composables.subjectRatingText
import io.github.touhougo.chogumi.core.ui.theme.ChogumiDimens

/**
 * 详情页作品横排的一张海报卡（[PosterCardStyle.CAROUSEL]）：左上角标是定位（角色）或第一个职位（人物），与列表行的标签同源；
 * 右上是「我」的收藏状态（长按标记）。
 */
@Composable
internal fun MonoWorkPosterCard(work: MonoWorkBo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PosterCard(
        imageUrl = work.image,
        title = displayNameOf(work),
        style = PosterCardStyle.CAROUSEL,
        onClick = onClick,
        mark = rememberPosterMark(work.id, work.subjectType),
        modifier = modifier,
    ) {
        workTags(work).firstOrNull()?.let { CoverBadge(it.first, Modifier.align(Alignment.TopStart).padding(CoverBadgeInset)) }
    }
}

/** 作品的定位 / 职位标签（文案, 是否主角）：角色给定位，人物给全部职位。海报徽标与列表行共用。 */
@Composable
private fun workTags(work: MonoWorkBo): List<Pair<String, Boolean>> =
    work.role?.let { listOf(stringResource(it.labelRes()) to (it == CastRole.MAIN)) }
        ?: work.positions.map { it to false }

/**
 * 作品分段列表（原型 `.slist.works`）：「全部」子页用，**每部作品一个懒加载项**。
 * 不能整张表塞进一个列表项里画：人物动辄几百部，每点一次筛选就要在同一帧重组、测量全部行，
 * 选中态要等这一帧算完才出来，点起来发卡。左右留白由 [horizontalPadding] 给。
 */
internal fun LazyListScope.monoWorkItems(
    works: List<MonoWorkBo>,
    onWorkClick: (Int) -> Unit,
    horizontalPadding: Dp,
) {
    // key 不带下标：带下标时换一次排序所有行都换 key，整列重建；不带下标时行只移动、不重建。
    // 副信息块走 details 而不是 supportingContent（多层布局进副行槽位会在快滑时闪退，见 SegmentedItem）。
    val keys = workRowKeys(works)
    itemsIndexed(works, key = { index, _ -> keys[index] }) { index, work ->
        SegmentedItem(
            index = index,
            count = works.size,
            onClick = { onWorkClick(work.id) },
            // 分段列表项间距 2（`ListItemDefaults.SegmentedGap`），首项之前不留
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(top = if (index > 0) ListItemDefaults.SegmentedGap else 0.dp),
            verticalAlignment = Alignment.Top,
            leadingContent = { SubjectPoster(work.image, Modifier.width(ChogumiDimens.ListThumbWidth)) },
            details = { WorkMeta(work) },
        ) {
            Text(
                text = displayNameOf(work),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 作品行的占位（骨架用）：与 [monoWorkItems] 的行同一个 [SegmentedItem] 框架、同尺寸缩略图，
 * 作品名、年月类型、标签行各画一段字条。
 */
@Composable
internal fun MonoWorkRowSkeleton(index: Int, count: Int, modifier: Modifier = Modifier) {
    SegmentedItem(
        index = index,
        count = count,
        onClick = null,
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        leadingContent = { SubjectPosterSkeletonBox(Modifier.width(ChogumiDimens.ListThumbWidth)) },
        details = {
            Column {
                SkeletonText(MaterialTheme.typography.bodySmall, widthFraction = 0.4f)
                SkeletonText(MaterialTheme.typography.labelMedium, Modifier.padding(top = 4.dp), widthFraction = 0.2f)
            }
        },
    ) {
        SkeletonText(MaterialTheme.typography.bodyLarge, widthFraction = 0.7f)
    }
}

/** 作品行的 key：作品 id，同一部作品出现不止一次时按出现次序区分（[occurrenceKeys]）。 */
internal fun workRowKeys(works: List<MonoWorkBo>): List<String> = occurrenceKeys(works) { "work-${it.id}" }

/**
 * 作品项的副行与标签行：「2017年1月」「动画」两段用 MetaText 连成一行，下面是定位（角色）或职位（人物）chip + 「★ 7.4」。
 * 年月用与作品详情元信息行同一份文案；评分可见性走 [subjectRatingText]。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkMeta(work: MonoWorkBo) {
    Column {
        val date = work.date?.let { date ->
            date.month?.let { stringResource(R.string.detail_meta_year_month, date.year, it) }
                ?: stringResource(R.string.detail_meta_year, date.year)
        }
        MetaText(
            parts = listOfNotNull(date, stringResource(work.subjectType.labelRes())),
            style = MaterialTheme.typography.bodySmall,
        )
        val rating = subjectRatingText(work.ratingScore, LocalAppFeatures.current, ::starScoreText)
        val tags = workTags(work)
        if (tags.isNotEmpty() || rating != null) {
            // 职位多（导演、脚本、分镜、演出……）一行放不下就换行，分数是最后一项
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                tags.forEach { (label, primary) -> WorkTag(label, primary) }
                if (rating != null) {
                    Text(rating, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 定位 / 职位小牌（[TextPill]）：主角 `primaryContainer`，其余 `surfaceContainerHighest`。 */
@Composable
internal fun WorkTag(label: String, primary: Boolean) {
    val (container, content) = if (primary) {
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    TextPill(label, container, content)
}
