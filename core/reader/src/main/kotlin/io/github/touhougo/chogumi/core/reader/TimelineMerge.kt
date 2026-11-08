package io.github.touhougo.chogumi.core.reader

import io.github.touhougo.chogumi.core.model.TimelineCategory
import io.github.touhougo.chogumi.core.model.TimelineItemBo
import io.github.touhougo.chogumi.core.model.TimelineSegmentBo
import io.github.touhougo.chogumi.core.model.timelineCategoryOf

/**
 * 时间线展示分组（供卡片呈现层使用）。
 *
 * 当用户连续收视同一作品的递增集数时，本地合并为单张卡片；其余情况 1:1 展示。
 *
 * @param anchor 交互锚点（取组内时间最新的一条真实条目，用于回复/点赞/删除与时间展示）
 * @param items 该组包含的所有原始条目（按列表原序排列）
 * @param episodeStart 连续集数起始（若合并则为最小集数，如 1.0）
 * @param episodeEnd 连续集数结束（若合并则为最大集数，如 3.0）
 */
data class TimelineDisplayGroup(
    val anchor: TimelineItemBo,
    val items: List<TimelineItemBo>,
    val episodeStart: Double? = null,
    val episodeEnd: Double? = null,
) {
    val isMerged: Boolean get() = items.size > 1
}

/**
 * 提取收视动态的单集进度集数（Double）与作品 ID。
 * 仅在进度类动态且包含 [TimelineSegmentBo.EpisodeProgress] 时返回。
 */
private fun TimelineItemBo.singleEpisodeInfo(): Pair<Int, Double>? {
    if (timelineCategoryOf(cat) != TimelineCategory.PROGRESS) return null
    val target = targets.firstOrNull { it.subject != null } ?: return null
    val subjectId = target.subject?.id ?: return null
    val segment = target.segments.firstOrNull { it is TimelineSegmentBo.EpisodeProgress } as? TimelineSegmentBo.EpisodeProgress
        ?: return null
    return subjectId to segment.sort
}

/**
 * 时间线连续剧集本地合并呈现函数（纯函数，O(n)）。
 *
 * 条件（全部满足才并）：
 * 1. 同一 uid
 * 2. 同一 subjectId
 * 3. 均为单集收视进度（进度类带 EpisodeProgress）
 * 4. 集数严格连续（在降序列表里，前一项与后一项集数差正好为 1.0）
 *
 * 遇任何中断（非连续、换人、换作品、非收视动作）立刻闭合当前组并开启新组。
 */
fun mergeConsecutiveTimelineItems(items: List<TimelineItemBo>): List<TimelineDisplayGroup> {
    val result = mutableListOf<TimelineDisplayGroup>()
    // 正在合并的一串单集收视：同一人、同一作品
    var run = mutableListOf<TimelineItemBo>()
    var runKey: Pair<Int?, Int>? = null
    var lastSort = 0.0
    var minSort = 0.0
    var maxSort = 0.0

    /** 闭合当前这串：首项是时间最新的一条；只有并了不止一条才写集数区间。 */
    fun closeRun() {
        if (run.isEmpty()) return
        val merged = run.size > 1
        result += TimelineDisplayGroup(
            anchor = run.first(),
            items = run.toList(),
            episodeStart = if (merged) minSort else null,
            episodeEnd = if (merged) maxSort else null,
        )
        run = mutableListOf()
        runKey = null
    }

    for (item in items) {
        val episode = item.singleEpisodeInfo()
        if (episode == null) {
            // 非单集收视进度：闭合当前这串，自己单独一组
            closeRun()
            result += TimelineDisplayGroup(anchor = item, items = listOf(item))
            continue
        }
        val (subjectId, sort) = episode
        val key = item.uid to subjectId
        // 列表默认时间降序，新集在上、旧集在下，相邻差值应为 1.0
        val continues = run.isNotEmpty() && runKey == key && lastSort - sort == 1.0
        if (!continues) {
            closeRun()
            runKey = key
            minSort = sort
            maxSort = sort
        }
        run += item
        minSort = minOf(minSort, sort)
        maxSort = maxOf(maxSort, sort)
        lastSort = sort
    }
    closeRun()
    return result
}

/**
 * 连续同作者动态分组（供动态页呈现）。
 * 同一个人连续发布的动态只显示一次头像与名字。
 */
data class TimelineUserGroup(
    val user: io.github.touhougo.chogumi.core.model.UserBo?,
    val uid: Int,
    val latestTime: Long,
    val items: List<TimelineDisplayGroup>,
)

/**
 * 把动态列表按连续作者分组（纯函数）。
 *
 * 同一 uid 连续出现的动态合并为同一个 [TimelineUserGroup]，
 * 只要作者变化立即开新组。
 */
fun groupConsecutiveByUser(items: List<TimelineDisplayGroup>): List<TimelineUserGroup> {
    if (items.isEmpty()) return emptyList()
    val result = mutableListOf<TimelineUserGroup>()
    for (item in items) {
        val last = result.lastOrNull()
        if (last != null && last.uid == item.anchor.uid) {
            result[result.size - 1] = last.copy(items = last.items + item)
        } else {
            result.add(
                TimelineUserGroup(
                    user = item.anchor.user,
                    uid = item.anchor.uid,
                    latestTime = item.anchor.createdAt,
                    items = listOf(item),
                ),
            )
        }
    }
    return result
}
