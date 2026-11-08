package io.github.touhougo.chogumi.core.ui.composables

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.touhougo.chogumi.core.model.CollectionType
import io.github.touhougo.chogumi.core.model.EpisodeTracking
import io.github.touhougo.chogumi.core.model.ProgressCount
import io.github.touhougo.chogumi.core.model.SubjectType

/** 骨架里海报占位块的 testTag（几何测试据此与真实海报的位置对位）。 */
const val SkeletonPosterTag = "skeleton_poster"

/** 骨架里标题占位的 testTag（几何测试据此与真实标题的位置对位）。 */
const val SkeletonTitleTag = "skeleton_title"

/**
 * 列表行骨架（首页「继续」、进度页）。框架与内容都取自 [CollectionListItemFrame] / [CollectionRowDetails]，
 * 与真实的 [CollectionListItem] 同源。
 *
 * 骨架阶段拿不到条目，只能由调用方给出这一页**将要出现的行**的类型：[subjectType] 决定要不要画进度，
 * [subjectType] 与 [collectionType] 一起经 [EpisodeTracking.reservesEpSlot]（唯一判定）决定留不留 EP 槽位。
 * 标题只画一行：行内内容垂直居中，常见的单行标题若按两行占位，内容出现时标题会下移。
 */
@Composable
fun CollectionListItemSkeleton(
    subjectType: SubjectType,
    collectionType: CollectionType,
    modifier: Modifier = Modifier,
) {
    CollectionListItemFrame(
        modifier = modifier,
        poster = { posterModifier -> SubjectPosterSkeletonBox(posterModifier) },
        title = { style -> SkeletonText(style, Modifier.testTag(SkeletonTitleTag), widthFraction = 0.8f) },
        details = { CollectionRowDetails(subjectType, ProgressCount.Loading, ongoing = false) },
        epSlot = if (EpisodeTracking.reservesEpSlot(subjectType, collectionType)) {
            { EpButtonPlaceholder(showTarget = false) }
        } else {
            null
        },
    )
}
