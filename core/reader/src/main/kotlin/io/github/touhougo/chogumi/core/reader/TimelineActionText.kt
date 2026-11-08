package io.github.touhougo.chogumi.core.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.TimelineSegmentBo
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.res.R

// ---------------------------------------------------------------------------
// 描述片段：数值/数据来自服务端，**模板**在资源里。
//
// 这些函数拿纯值（数字、字符串）而非 `Context`，因此可直接单测；
// 带 `@Composable` 的重载负责取 `stringResource` 并委托过来。
// ---------------------------------------------------------------------------

/**
 * 批量收视/阅读进度描述。
 *
 * 优化排版与去冗余策略：
 * - 当存在用户更新进度时（`epsUpdate` 或 `volsUpdate`），直接表达进度动作（如「62 话、7 卷」，动作由前面的动词胶囊说），
 *   严禁在前缀冗余拼接「章节 62」与「卷数 7」，彻底消除数据重复与数字重叠，并避免占用横向空间；
 * - 若同时包含话数与卷数更新，合并为一条精炼动作（「X 话、Y 卷」）；
 * - 仅在完全缺失进度更新数据时，才作为兜底展示作品总章节/卷数信息。
 *
 * @param epsTotal 服务端 `epsTotal`，**是字符串**——旧数据（PHP 时代）缺字段时
 *   服务端会填 `??`，客户端不能把它当数字解析
 * @param volsTotal 同上
 * @param epsUpdateText 话数更新的文案（按数量取复数形式），[volsUpdateText] 同
 * @param epsTotalFormat 等模板取自资源（见 [timelineSegmentText]），这里不留默认值，免得出现第二份文案
 */
fun progressBatchDescription(
    epsTotal: String,
    epsUpdate: Int?,
    volsTotal: String,
    volsUpdate: Int?,
    separator: String,
    epsTotalFormat: String,
    epsUpdateText: (Int) -> String,
    volsTotalFormat: String,
    volsUpdateText: (Int) -> String,
): String {
    val hasEpsUpdate = epsUpdate != null && epsUpdate > 0
    val hasVolsUpdate = volsUpdate != null && volsUpdate > 0

    if (hasEpsUpdate && hasVolsUpdate) {
        return listOf(epsUpdateText(epsUpdate), volsUpdateText(volsUpdate)).joinToString(separator)
    }
    if (hasEpsUpdate) {
        return epsUpdateText(epsUpdate)
    }
    if (hasVolsUpdate) {
        return volsUpdateText(volsUpdate)
    }

    return buildList {
        if (epsTotal.isNotBlank() && epsTotal != "??" && epsTotal != "0") {
            add(epsTotalFormat.format(epsTotal))
        }
        if (volsTotal.isNotBlank() && volsTotal != "??" && volsTotal != "0") {
            add(volsTotalFormat.format(volsTotal))
        }
    }.joinToString(separator)
}

/**
 * 单集进度描述：**集号 + 集名**。
 *
 * 集号经 [formatEpisodeSort] 规整，整数显示为 "12"，不泄漏 "12.0"。
 */
fun episodeLabel(sort: Double, name: String): String {
    val sortStr = formatEpisodeSort(sort)
    return if (name.isNotBlank()) "$sortStr $name" else sortStr
}

/**
 * 把一条描述片段渲染成文本：模板取自资源，值来自服务端。
 *
 * 带 `@Composable` 是因为要读 `stringResource`；纯值部分各自有可直接单测的函数，
 * 这里只负责取资源并委托。
 */
@Composable
fun timelineSegmentText(segment: TimelineSegmentBo): String {
    val resources = LocalResources.current
    return when (segment) {
        is TimelineSegmentBo.GroupMembers -> pluralStringResource(R.plurals.timeline_group_members, segment.count, segment.count)
        is TimelineSegmentBo.IndexTotal -> pluralStringResource(R.plurals.timeline_index_total, segment.count, segment.count)
        is TimelineSegmentBo.EpisodeProgress -> episodeLabel(segment.sort, segment.name)
        is TimelineSegmentBo.ProgressBatch -> progressBatchDescription(
            epsTotal = segment.epsTotal,
            epsUpdate = segment.epsUpdate,
            volsTotal = segment.volsTotal,
            volsUpdate = segment.volsUpdate,
            separator = stringResource(R.string.list_separator),
            epsTotalFormat = stringResource(R.string.timeline_progress_eps_total),
            epsUpdateText = { resources.getQuantityString(R.plurals.timeline_progress_eps_update, it, it) },
            volsTotalFormat = stringResource(R.string.timeline_progress_vols_total),
            volsUpdateText = { resources.getQuantityString(R.plurals.timeline_progress_vols_update, it, it) },
        )
    }
}
