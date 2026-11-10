package io.github.touhougo.chogumi.feature.progress

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.ProgressOrder
import io.github.touhougo.chogumi.core.model.ProgressSort
import io.github.touhougo.chogumi.core.model.SubjectType
import io.github.touhougo.chogumi.core.model.progressSortsFor
import io.github.touhougo.chogumi.core.res.R
import io.github.touhougo.chogumi.core.ui.composables.SortDirection
import io.github.touhougo.chogumi.core.ui.composables.SortMenuAction

/**
 * 进度页的排序按钮（搜索栏尾部）：全 App 同一个排序菜单 [SortMenuAction]，上一组是这一页有的档（[progressSortsFor]），
 * 分隔线下是这一档的两种顺序，直接点选。[order] 是这一页实际在用的（[ProgressUiState.pageOrder]），
 * [publicRatings] 是 [ProgressUiState.publicRatings]（关掉时没有「公共评分」档）。
 */
@Composable
internal fun ProgressSortAction(
    type: SubjectType?,
    publicRatings: Boolean,
    order: ProgressOrder,
    onSelectSort: (ProgressSort) -> Unit,
    onSelectDirection: (reversed: Boolean) -> Unit,
) {
    val (forward, backward) = progressDirectionLabels(order.sort)
    SortMenuAction(
        sort = order.sort,
        options = progressSortsFor(type, publicRatings),
        label = { stringResource(progressSortLabel(it, type)) },
        onSelect = onSelectSort,
        direction = SortDirection(
            reversed = order.reversed,
            forward = stringResource(forward),
            backward = stringResource(backward),
            onSelect = onSelectDirection,
        ),
    )
}

/**
 * 排序档的叫法——**唯一映射**。日期档按类型叫：按集播出的（动画、三次元，[EpisodeTracking.tracksEpisodes]）叫放送日期，
 * 书籍、游戏、音乐叫发售日期，混排的「全部」只叫日期。
 */
internal fun progressSortLabel(sort: ProgressSort, type: SubjectType?): Int = when (sort) {
    ProgressSort.RECENT -> R.string.sort_recent
    ProgressSort.LATEST_AIRED -> R.string.sort_latest_aired
    ProgressSort.DATE -> when {
        type == null -> R.string.sort_date
        EpisodeTracking.tracksEpisodes(type) -> R.string.sort_air_date
        else -> R.string.sort_release_date
    }
    ProgressSort.TITLE -> R.string.sort_title
    ProgressSort.PUBLIC_RATING -> R.string.sort_public_rating
    ProgressSort.MY_RATING -> R.string.sort_my_rating
}

/**
 * 一档的两种顺序怎么叫（默认的在前、反过来的在后）——**唯一映射**：时间类「新到旧 / 旧到新」，评分「高到低 / 低到高」，
 * 名称「A 到 Z / Z 到 A」。默认方向由 [ProgressOrder.descending] 定，与这里的先后一致。
 */
internal fun progressDirectionLabels(sort: ProgressSort): Pair<Int, Int> = when (sort) {
    ProgressSort.RECENT, ProgressSort.LATEST_AIRED, ProgressSort.DATE -> R.string.sort_new_to_old to R.string.sort_old_to_new
    ProgressSort.PUBLIC_RATING, ProgressSort.MY_RATING -> R.string.sort_high_to_low to R.string.sort_low_to_high
    ProgressSort.TITLE -> R.string.sort_a_to_z to R.string.sort_z_to_a
}
