package io.github.touhougo.chogumi.feature.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.touhougo.chogumi.core.model.SubjectBo
import io.github.touhougo.chogumi.core.model.timelineYearsOf
import io.github.touhougo.chogumi.core.ui.composables.CarouselRow
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVertical
import io.github.touhougo.chogumi.core.ui.composables.SubjectCardVerticalSkeleton

/**
 * 年表（原型 `.tline`）：这条货架本来那一页作品按年份排在一条横向的时间线上（分组只问 [timelineYearsOf]），
 * 一年一个节点：圆点加年份，后面一道线连到下一年；节点下面是那一年的海报卡。一部有日期的都没有时画成海报行。
 * 线要连过卡与卡之间，所以间隔画在每一项里（[CarouselRow] 的 `itemSpacing` 为 0）：同一年的卡隔 [TimelineCardGap]，
 * 一年的末一张隔 [TimelineYearGap]。[subjects] 为 null 时画同样几何的骨架。
 */
@Composable
internal fun TimelineRow(subjects: List<SubjectBo>?, onSubjectClick: (Int) -> Unit) {
    if (subjects == null) {
        CarouselRow(userScrollEnabled = false, itemSpacing = 0.dp) {
            items(TIMELINE_SKELETON_COUNT) { index ->
                TimelineStop(year = null, node = index % TIMELINE_SKELETON_PER_YEAR == 0, endGap = TimelineCardGap) {
                    SubjectCardVerticalSkeleton(Modifier.width(TimelineCardWidth))
                }
            }
        }
        return
    }
    val years = timelineYearsOf(subjects)
    if (years.isEmpty()) {
        PosterRow(subjects, onSubjectClick)
        return
    }
    CarouselRow(itemSpacing = 0.dp, modifier = Modifier.testTag("explore_timeline")) {
        years.forEachIndexed { yearIndex, year ->
            itemsIndexed(year.works, key = { _, subject -> subject.id }) { index, subject ->
                val endGap = when {
                    index < year.works.lastIndex -> TimelineCardGap
                    // 整行最后一张不再留间隔：横排自己留着右边距
                    yearIndex == years.lastIndex -> 0.dp
                    else -> TimelineYearGap
                }
                TimelineStop(year = year.year.takeIf { index == 0 }, node = index == 0, endGap = endGap) {
                    SubjectCardVertical(subject = subject, onClick = onSubjectClick, modifier = Modifier.width(TimelineCardWidth))
                }
            }
        }
    }
}

/**
 * 时间线上的一项：上面是线（[node] 时先画圆点与年份 [year]，骨架的年份为 null 只画圆点），下面是卡，右边留 [endGap]；
 * 线一直画到这一项的右边缘，与下一项的线接上。
 */
@Composable
private fun TimelineStop(year: Int?, node: Boolean, endGap: Dp, card: @Composable () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(TimelineCardWidth + endGap)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TimelineHeaderHeight),
        ) {
            if (node) {
                Box(Modifier.size(TimelineDotSize).background(primary, CircleShape))
                if (year != null) {
                    Text(text = year.toString(), style = MaterialTheme.typography.titleSmall, color = primary, maxLines = 1)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(TimelineLineWidth)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Box(Modifier.padding(end = endGap)) { card() }
    }
}

/** 骨架画几张、每几张一个节点：铺满一屏宽。 */
private const val TIMELINE_SKELETON_COUNT = 8
private const val TIMELINE_SKELETON_PER_YEAR = 3

/** 年表里的海报卡宽（原型 `.tg .card` 的 96，比海报行窄一点，一屏多放几年）。 */
private val TimelineCardWidth = 96.dp

/** 同一年的卡之间、一年与下一年之间的间隔（原型 `.tc` 的 gap 12 与 padding-right 16）。 */
private val TimelineCardGap = 12.dp
private val TimelineYearGap = 16.dp

/** 节点那一行的高、圆点直径、线宽（原型 `.yr`）。我的设计决定（not in M3）。 */
private val TimelineHeaderHeight = 24.dp
private val TimelineDotSize = 10.dp
private val TimelineLineWidth = 2.dp
