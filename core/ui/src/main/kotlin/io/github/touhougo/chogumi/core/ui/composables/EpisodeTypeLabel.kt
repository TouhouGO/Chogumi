package io.github.touhougo.chogumi.core.ui.composables

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.touhougo.chogumi.core.model.EpisodeBo
import io.github.touhougo.chogumi.core.model.EpisodeType
import io.github.touhougo.chogumi.core.model.displayNameOf
import io.github.touhougo.chogumi.core.model.formatEpisodeSort
import io.github.touhougo.chogumi.core.res.R

/**
 * 集类型 → 显示前缀的**唯一一份 labelRes 映射**（中英文都在 `core:res` 里）。
 *
 * 为什么在这里而不在 `core:model`：文案资源属于界面层；`core:model` 是纯 Kotlin、
 * 不依赖 Android 资源。类型取值本身（与接口对齐的那 7 个）仍在
 * [io.github.touhougo.chogumi.core.model.EpisodeType]，两边靠这个 when 接通。
 *
 * **未知取值返回 null**：`when` 逐个列出 [EpisodeType] 的合法取值，脏数据（如 `type = 99`）落到
 * `else -> null`，和正片一样不带前缀。这是刻意的：与其给未知类型编一个「SP」，不如让它压根不显示。
 */
@StringRes
fun episodeTypeLabelRes(type: Int): Int? = when (type) {
    EpisodeType.NORMAL -> null // 正片没有前缀
    EpisodeType.SPECIAL -> R.string.episode_type_sp
    EpisodeType.OP -> R.string.episode_type_op
    EpisodeType.ED -> R.string.episode_type_ed
    EpisodeType.PRE -> R.string.episode_type_pre
    EpisodeType.MAD -> R.string.episode_type_mad
    EpisodeType.OTHER -> R.string.episode_type_other
    else -> null
}

/**
 * 特别篇的文案——**唯一一份**：`SP 21 NERV、诞生`（类型前缀 + 集号 + 标题，中文名缺则原名）。
 * 特别篇方格的读屏描述用完整文案；单集弹层的小字行只要 `SP 21`（[withTitle] = false，标题另起一行大字）。
 */
@Composable
fun specialEpisodeLabel(episode: EpisodeBo, withTitle: Boolean = true): String {
    val prefix = episodeTypeLabelRes(episode.type)?.let { stringResource(it) }.orEmpty()
    val sort = if (episode.sort > 0) formatEpisodeSort(episode.sort) else ""
    val title = if (withTitle) displayNameOf(episode) else ""
    return listOf(prefix, sort, title)
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}

/** 一集的集号——**唯一一份**：正片「第 12 集」（[episodeProgressLabel]），其余按类型「SP 21」（[specialEpisodeLabel] 不带标题）。 */
@Composable
fun episodeNumberLabel(episode: EpisodeBo): String =
    if (EpisodeType.isMain(episode.type)) episodeProgressLabel(episode.sort) else specialEpisodeLabel(episode, withTitle = false)
